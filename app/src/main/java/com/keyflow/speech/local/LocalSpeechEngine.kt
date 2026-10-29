package com.keyflow.speech.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.keyflow.i18n.tr
import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * O que o processo principal pede ao `:speech`. Implementação de teste não abre binder. Arquivo ausente não chega aqui:
 * [LocalSpeechEngine] devolve `MODEL_MISSING` antes.
 */
interface SpeechEndpoint {
    suspend fun speak(
        model: File,
        tokens: File,
        text: String,
        speed: Float,
        onPcm: (pcm: ByteArray, sampleRate: Int) -> Unit,
    ): SpeechOutcome
}

sealed interface SpeechOutcome {
    data class Ok(val peakRssKb: Long) : SpeechOutcome
    data class Err(val kind: Int, val detail: String) : SpeechOutcome
}

/**
 * Voz local no contrato [SpeechEngine] (FAL-T11). Sem os dois arquivos, `MODEL_MISSING` e o [endpoint] não é chamado.
 * A frase da tela sai daqui (`tr`); o `:speech` só manda o código.
 */
class LocalSpeechEngine(
    private val files: () -> Pair<File, File>?,
    private val endpoint: SpeechEndpoint,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : SpeechEngine {
    override fun speak(request: SpeechRequest): Flow<SpeechEvent> = channelFlow {
        val pair = files()
        val model = pair?.first
        val tokens = pair?.second
        if (model == null || tokens == null || !model.isFile || !tokens.isFile) {
            send(SpeechEvent.Error(SpeechError.MODEL_MISSING, tr("Baixe e selecione um modelo para testar.")))
            return@channelFlow
        }
        val events = Channel<SpeechEvent>(Channel.UNLIMITED)
        val clock = com.keyflow.speech.SpeechClock(nowMs)
        val job = launch {
            val outcome = try {
                endpoint.speak(model, tokens, request.text, request.speed) { pcm, rate ->
                    val first = clock.onAudio(pcm, rate)
                    events.trySend(SpeechEvent.Audio(pcm, rate))
                    if (first != null) events.trySend(first)
                }
            } catch (e: Exception) {
                SpeechOutcome.Err(SpeechProtocol.ENGINE, e.message ?: e.javaClass.simpleName)
            }
            when (outcome) {
                is SpeechOutcome.Ok -> events.trySend(clock.done())
                is SpeechOutcome.Err -> events.trySend(SpeechEvent.Error(kindOf(outcome.kind), messageOf(outcome.kind)))
            }
            events.close()
        }
        try {
            for (event in events) send(event)
        } finally {
            job.cancel()
        }
    }.flowOn(Dispatchers.Default)

    private fun kindOf(kind: Int): SpeechError = when (kind) {
        SpeechProtocol.MODEL_MISSING -> SpeechError.MODEL_MISSING
        else -> SpeechError.ENGINE
    }

    private fun messageOf(kind: Int): String = when (kind) {
        SpeechProtocol.MODEL_MISSING -> tr("Baixe e selecione um modelo para testar.")
        SpeechProtocol.NOT_ENOUGH_RAM -> tr("Este aparelho não suporta os modelos locais de voz.")
        SpeechProtocol.OUT_OF_MEMORY -> tr("Não consegui gerar a voz: faltou memória.")
        else -> tr("Não consegui gerar a voz neste aparelho.")
    }
}

/** Liga ao [SpeechService] e espera o callback. O PCM chega na thread do binder; [onPcm] só enfileira. */
class AidlSpeechEndpoint(
    private val connection: SpeechConnection,
    private val ramEstimateMb: Int,
) : SpeechEndpoint {
    override suspend fun speak(
        model: File,
        tokens: File,
        text: String,
        speed: Float,
        onPcm: (pcm: ByteArray, sampleRate: Int) -> Unit,
    ): SpeechOutcome {
        val service = connection.acquire()
        try {
            return suspendCancellableCoroutine { cont ->
                val cb = object : ISpeechCallback.Stub() {
                    override fun onPcm(pcm: ByteArray, sampleRate: Int) {
                        onPcm(pcm, sampleRate)
                    }

                    override fun onDone(peakRssKb: Long) {
                        if (cont.isActive) cont.resume(SpeechOutcome.Ok(peakRssKb))
                    }

                    override fun onError(kind: Int, message: String) {
                        if (cont.isActive) cont.resume(SpeechOutcome.Err(kind, message))
                    }
                }
                cont.invokeOnCancellation { runCatching { service.cancel() } }
                try {
                    service.speak(model.absolutePath, tokens.absolutePath, text, speed, ramEstimateMb, cb)
                } catch (e: RemoteException) {
                    if (cont.isActive) cont.resume(SpeechOutcome.Err(SpeechProtocol.ENGINE, e.message ?: "binder"))
                }
            }
        } finally {
            connection.release()
        }
    }
}

/**
 * Uma conexão com o `:speech`. O bind fica mais [SpeechProtocol.IDLE_UNLOAD_MS] depois do último uso, para a sessão
 * ociosa fechar no serviço antes de o processo cair. Não é espera para ganhar corrida: é a folga da descarga.
 */
class SpeechConnection(
    private val context: Context,
    private val lingerMs: Long = LINGER_MS,
    private val bindTimeoutMs: Long = BIND_TIMEOUT_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindMutex = Mutex()
    private val lock = Any()
    private var service: ISpeechService? = null
    private var serviceConnection: ServiceConnection? = null
    private var death: Pair<IBinder, IBinder.DeathRecipient>? = null
    private var uses = 0
    private var lingerJob: Job? = null

    suspend fun acquire(): ISpeechService {
        synchronized(lock) {
            lingerJob?.cancel()
            lingerJob = null
            uses++
        }
        try {
            return synchronized(lock) { service } ?: bindMutex.withLock {
                synchronized(lock) { service } ?: withTimeout(bindTimeoutMs) { bind() }
            }
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    fun release() {
        synchronized(lock) {
            uses = (uses - 1).coerceAtLeast(0)
            if (uses == 0 && service != null) {
                lingerJob?.cancel()
                lingerJob = scope.launch {
                    delay(lingerMs)
                    synchronized(lock) { if (uses == 0) unbindLocked() }
                }
            }
        }
    }

    fun close() {
        synchronized(lock) {
            lingerJob?.cancel()
            lingerJob = null
            unbindLocked()
        }
    }

    private fun unbindLocked() {
        death?.let { (binder, recipient) -> runCatching { binder.unlinkToDeath(recipient, 0) } }
        death = null
        serviceConnection?.let { runCatching { context.unbindService(it) } }
        serviceConnection = null
        service = null
    }

    private suspend fun bind(): ISpeechService = suspendCancellableCoroutine { cont ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val connected = ISpeechService.Stub.asInterface(binder)
                val recipient = IBinder.DeathRecipient {
                    synchronized(lock) { if (service === connected) unbindLocked() }
                }
                try {
                    binder.linkToDeath(recipient, 0)
                } catch (e: RemoteException) {
                    runCatching { context.unbindService(this) }
                    if (cont.isActive) cont.resumeWithException(e)
                    return
                }
                synchronized(lock) {
                    service = connected
                    serviceConnection = this
                    death = binder to recipient
                }
                if (cont.isActive) cont.resume(connected)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(lock) { if (serviceConnection === this) unbindLocked() }
            }

            override fun onBindingDied(name: ComponentName) {
                synchronized(lock) { if (serviceConnection === this) unbindLocked() }
                if (cont.isActive) cont.resumeWithException(IllegalStateException("o :speech morreu ao ligar"))
            }

            override fun onNullBinding(name: ComponentName) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("o :speech não devolveu binder"))
            }
        }
        val bound = runCatching {
            context.bindService(Intent(context, SpeechService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            cont.resumeWithException(IllegalStateException("bindService recusado para o :speech"))
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { runCatching { context.unbindService(connection) } }
    }

    companion object {
        const val LINGER_MS = SpeechProtocol.IDLE_UNLOAD_MS + 5_000L
        const val BIND_TIMEOUT_MS = 10_000L

        @Volatile private var instance: SpeechConnection? = null

        fun get(context: Context): SpeechConnection = instance ?: synchronized(this) {
            instance ?: SpeechConnection(context.applicationContext).also { instance = it }
        }
    }
}
