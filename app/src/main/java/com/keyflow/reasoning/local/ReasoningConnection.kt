package com.keyflow.reasoning.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Uma conexão compartilhada com o `:reasoning`: liga sob demanda e só desliga [lingerMs] depois do último uso.
 *
 * Sem isto o bind cairia a cada geração e o serviço seria destruído junto — o modelo seria descarregado e recarregado
 * (segundos) a cada Testar. Com o bind mantido, é o descarregamento por ociosidade do próprio serviço
 * ([LlmModels.IDLE_UNLOAD_MS]) que devolve a memória (R8). Se o processo morre, esquece a conexão: o próximo uso liga
 * de novo (RRUN-05). Quem gera ouve a própria morte (ver [LocalReasoningEngine]).
 */
internal class ReasoningConnection(
    private val context: Context,
    private val lingerMs: Long = LINGER_MS,
    private val bindTimeoutMs: Long = BIND_TIMEOUT_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindMutex = Mutex()
    private val lock = Any()
    private var service: IReasoningService? = null
    private var serviceConnection: ServiceConnection? = null
    private var death: Pair<IBinder, IBinder.DeathRecipient>? = null
    private var uses = 0
    private var lingerJob: Job? = null

    /** Devolve o serviço vivo (ligando se preciso) e conta um uso; quem chamou deve chamar [release] ao terminar. */
    suspend fun acquire(): IReasoningService {
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

    /** Desliga já, mesmo com uso em curso (testes e fim do processo). */
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

    private suspend fun bind(): IReasoningService = suspendCancellableCoroutine { cont ->
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val connected = IReasoningService.Stub.asInterface(binder)
                val recipient = IBinder.DeathRecipient { synchronized(lock) { if (service === connected) unbindLocked() } }
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
                if (cont.isActive) cont.resumeWithException(IllegalStateException("o :reasoning morreu ao ligar"))
            }

            override fun onNullBinding(name: ComponentName) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("o :reasoning não devolveu binder"))
            }
        }
        val bound = runCatching {
            context.bindService(Intent(context, ReasoningService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            cont.resumeWithException(IllegalStateException("bindService recusado para o :reasoning"))
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { runCatching { context.unbindService(connection) } }
    }

    companion object {
        /** Folga depois da ociosidade do serviço: o modelo já foi descarregado pelo próprio serviço quando o bind cai. */
        const val LINGER_MS = LlmModels.IDLE_UNLOAD_MS + 5_000L
        const val BIND_TIMEOUT_MS = 10_000L

        @Volatile private var instance: ReasoningConnection? = null

        /** Uma por processo: geração da tela, do teclado e futuros consumidores dividem o mesmo bind. */
        fun get(context: Context): ReasoningConnection = instance ?: synchronized(this) {
            instance ?: ReasoningConnection(context.applicationContext).also { instance = it }
        }
    }
}
