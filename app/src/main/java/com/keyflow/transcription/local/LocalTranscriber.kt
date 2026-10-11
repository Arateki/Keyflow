package com.keyflow.transcription.local

import com.keyflow.i18n.tr
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.keyflow.core.ErrorKind
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.EffectiveConfig
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelStore
import com.keyflow.overlay.LocalTranscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Mensagens dos erros locais, já para o usuário (NAT-04/07). */
object LocalErrors {
    // Getters, não constantes: a mensagem sai no idioma da interface na hora do erro.
    val PROCESS_DIED: String get() = tr("A transcrição local foi interrompida — tente de novo.")
    val UNAVAILABLE: String get() = tr("Não foi possível iniciar a transcrição local.")

    fun message(kind: EngineErrorKind): String = when (kind) {
        EngineErrorKind.MODEL_MISSING -> tr("Arquivos do modelo local sumiram — baixe de novo na aba Transcrição.")
        EngineErrorKind.DECODE -> tr("Não foi possível ler o áudio gravado.")
        EngineErrorKind.OOM -> tr("Memória insuficiente para o modelo local — feche outros apps ou escolha um modelo menor.")
        EngineErrorKind.ENGINE -> tr("O modelo local falhou ao transcrever.")
    }

    fun error(message: String) = TranscriptionResult.Error(ErrorKind.LOCAL, message)

    /** Selecionado mas não baixado (ou fora do catálogo): o mesmo erro de config das origens incompletas (NAT-03). */
    val notInstalled = TranscriptionResult.Error(ErrorKind.CONFIG_MISSING, "modelo local não instalado")
}

/** Seleção de Settings → modelo do engine, se o modelo existe no catálogo e está instalado. */
class LocalModelResolver(
    private val store: ModelStore,
    private val catalog: (String) -> ModelSpec? = ModelCatalog::byId,
) {
    fun resolve(local: EffectiveConfig.Local): EngineModel? {
        val spec = catalog(local.modelId) ?: return null
        if (!store.isInstalled(spec.id)) return null
        return EngineModel.from(spec, store.dirFor(spec.id), local.language)
    }
}

/**
 * Cliente do processo `:inference` no processo principal (NM-T9). Uma instância por processo
 * ([get]): overlay e teclado dividem o mesmo bind e o mesmo modelo residente (G10).
 *
 * - O bind é feito no primeiro uso e mantido — é ele que segura o `:inference` vivo com o modelo
 *   carregado. Só é desfeito quando a origem deixa de ser Local ([sync]).
 * - Se o `:inference` morre (o SO matou por memória, crash nativo), o `DeathRecipient` responde erro a
 *   quem estava esperando e esquece o bind; o próximo uso refaz bind e carga (NAT-05).
 */
class LocalTranscriber internal constructor(
    private val context: Context,
    private val resolver: LocalModelResolver,
) : LocalTranscription {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var connection: Connection? = null
    @Volatile
    private var preloaded: EffectiveConfig.Local? = null

    /** Há bind vivo com o `:inference` — para os testes esperarem a morte ser notada. */
    internal val isBound: Boolean get() = synchronized(lock) { connection != null }

    override suspend fun transcribe(local: EffectiveConfig.Local, audio: File): TranscriptionResult {
        val model = resolver.resolve(local) ?: return LocalErrors.notInstalled
        return call { engine, cb -> engine.transcribe(model.toParcel(), audio.absolutePath, cb) }
    }

    /**
     * Acompanha a origem configurada: Local com modelo → pré-carrega (uma vez por seleção); qualquer
     * outra → solta o bind e, com ele, a RAM do modelo. Chamado quando um modo ativa e quando as
     * Settings mudam com o modo ativo.
     */
    fun sync(effective: EffectiveConfig) {
        if (effective is EffectiveConfig.Local) {
            if (effective != preloaded) preload(effective)
        } else {
            release()
        }
    }

    private fun preload(local: EffectiveConfig.Local) {
        val model = resolver.resolve(local) ?: return
        preloaded = local
        scope.launch {
            val result = call { engine, cb -> engine.preload(model.toParcel(), cb) }
            if (result is TranscriptionResult.Error) {
                Log.w(TAG, "preload de ${local.modelId} falhou: ${result.message}")
                // Tenta de novo no próximo sync (ex.: a RAM liberou).
                if (preloaded == local) preloaded = null
            }
        }
    }

    /** Solta o bind; uma transcrição em andamento termina antes (o bind só cai com a última resposta). */
    fun release() {
        preloaded = null
        val current = synchronized(lock) { connection.also { connection = null } } ?: return
        current.retire()
    }

    private suspend fun call(send: (ITranscriptionEngine, IEngineCallback) -> Unit): TranscriptionResult {
        val conn = synchronized(lock) {
            connection ?: Connection().also { fresh ->
                connection = fresh
                val bound = runCatching {
                    context.bindService(Intent(context, InferenceService::class.java), fresh, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
                if (!bound) {
                    connection = null
                    return LocalErrors.error(LocalErrors.UNAVAILABLE)
                }
            }
        }
        val engine = withTimeoutOrNull(BIND_TIMEOUT_MS) { conn.engine.await() }
            ?: return LocalErrors.error(LocalErrors.UNAVAILABLE)
        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            val finish: (TranscriptionResult) -> Unit = { result ->
                if (done.compareAndSet(false, true)) cont.resume(result)
            }
            conn.pending[finish] = Unit
            cont.invokeOnCancellation { conn.settle(finish) }
            val callback = object : IEngineCallback.Stub() {
                override fun onSuccess(text: String) {
                    conn.settle(finish)
                    finish(TranscriptionResult.Success(text))
                }

                override fun onError(kind: Int, message: String) {
                    conn.settle(finish)
                    Log.w(TAG, "erro do :inference ($kind): $message")
                    finish(LocalErrors.error(LocalErrors.message(EngineErrorKind.fromCode(kind))))
                }
            }
            try {
                send(engine, callback)
            } catch (e: RemoteException) {
                conn.settle(finish)
                finish(LocalErrors.error(LocalErrors.PROCESS_DIED))
            }
        }
    }

    private fun onDied(dead: Connection) {
        val wasCurrent = synchronized(lock) { (connection === dead).also { if (it) connection = null } }
        if (wasCurrent) preloaded = null
        dead.dispose()
    }

    /** Um bind. Morto ou solto, não é reaproveitado: o próximo uso cria outro. */
    private inner class Connection : ServiceConnection, IBinder.DeathRecipient {
        val engine = CompletableDeferred<ITranscriptionEngine>()
        val pending = ConcurrentHashMap<(TranscriptionResult) -> Unit, Unit>()
        private val disposed = AtomicBoolean(false)
        @Volatile
        private var retiring = false
        private var binder: IBinder? = null

        /** Tira uma espera da lista; se o bind já foi solto, cai junto com a última. */
        fun settle(waiter: (TranscriptionResult) -> Unit) {
            pending.remove(waiter)
            if (retiring && pending.isEmpty()) dispose()
        }

        fun retire() {
            retiring = true
            if (pending.isEmpty()) dispose()
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            runCatching { service.linkToDeath(this, 0) }.onFailure { binderDied() }
            engine.complete(ITranscriptionEngine.Stub.asInterface(service))
        }

        override fun onServiceDisconnected(name: ComponentName) = onDied(this)

        override fun binderDied() = onDied(this)

        fun dispose() {
            if (!disposed.compareAndSet(false, true)) return
            binder?.let { runCatching { it.unlinkToDeath(this, 0) } }
            runCatching { context.unbindService(this) }
            engine.cancel()
            val waiting = pending.keys.toList()
            pending.clear()
            waiting.forEach { it(LocalErrors.error(LocalErrors.PROCESS_DIED)) }
        }
    }

    companion object {
        private const val TAG = "LocalTranscriber"
        private const val BIND_TIMEOUT_MS = 30_000L

        @Volatile
        private var instance: LocalTranscriber? = null

        /**
         * Acompanha a origem de voz: com a Local, cria (se preciso) e pré-carrega o modelo; com outra, só solta o que estiver
         * preso — sem criar o transcritor, cuja criação varre a pasta de modelos no disco. O teclado chama isto ao abrir, na
         * thread principal (StrictModeKeyboardTest).
         */
        fun syncWith(context: Context, effective: EffectiveConfig) {
            if (effective is EffectiveConfig.Local) get(context).sync(effective) else instance?.sync(effective)
        }

        fun get(context: Context): LocalTranscriber = instance ?: synchronized(this) {
            instance ?: LocalTranscriber(
                context.applicationContext,
                LocalModelResolver(ModelStore.get(context)),
            ).also { instance = it }
        }
    }
}
