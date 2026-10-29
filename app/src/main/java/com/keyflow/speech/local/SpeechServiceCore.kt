package com.keyflow.speech.local

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Agenda a descarga da sessão. Falso em teste; relógio de verdade no serviço. */
internal interface SpeechIdleScheduler {
    fun schedule(delayMs: Long, block: () -> Unit): SpeechCancellable
}

internal fun interface SpeechCancellable {
    fun cancel()
}

/**
 * Uma síntese no processo `:speech`. PCM 16-bit mono. Não abre rede: quem implementa só lê arquivo e a ORT.
 * [cancelled] passa a valer no meio da frase; a implementação para na próxima checagem.
 */
internal interface LocalVoice : AutoCloseable {
    fun available(model: File, tokens: File): Boolean = model.isFile && tokens.isFile
    fun synthesize(
        model: File,
        tokens: File,
        text: String,
        speed: Float,
        cancelled: () -> Boolean,
        emit: (pcm: ByteArray, sampleRate: Int) -> Unit,
    )
}

internal data class SpeechJob(
    val modelPath: String,
    val tokensPath: String,
    val text: String,
    val speed: Float,
    val ramEstimateMb: Int,
)

/**
 * Núcleo do `:speech`, sem Android (FLOC-04/05). Uma fala por vez. Sem arquivo, [LocalVoice] não é chamado
 * (não abre sessão nem socket). 120 s sem fala fecha a sessão. `OutOfMemoryError` vira [SpeechProtocol.OUT_OF_MEMORY].
 * Estado tocado só na thread do [executor]; [cancel] só mexe no contador.
 */
internal class SpeechServiceCore(
    private val voice: LocalVoice,
    private val availableMb: () -> Long?,
    private val scheduler: SpeechIdleScheduler,
    private val executor: Executor,
    private val idleUnloadMs: Long = SpeechProtocol.IDLE_UNLOAD_MS,
    private val peakRssKb: () -> Long = { 0L },
    private val resetPeak: () -> Unit = {},
) {
    interface Listener {
        fun onPcm(pcm: ByteArray, sampleRate: Int)
        fun onDone(peakRssKb: Long)
        fun onError(kind: Int, detail: String)
    }

    private var busy = false
    private var idleTimer: SpeechCancellable? = null
    private var useToken = 0L
    private val tickets = AtomicLong()
    private val cancelledUpTo = AtomicLong()

    private fun isCancelled(ticket: Long) = ticket <= cancelledUpTo.get()

    fun speak(job: SpeechJob, listener: Listener) {
        val ticket = tickets.incrementAndGet()
        executor.execute { process(ticket, job, listener) }
    }

    /** Cancela as falas já entregues. A que chegar depois não é afetada. */
    fun cancel() {
        cancelledUpTo.accumulateAndGet(tickets.get()) { a, b -> maxOf(a, b) }
    }

    fun shutdown() {
        executor.execute {
            idleTimer?.cancel()
            idleTimer = null
            runCatching { voice.close() }
        }
    }

    private fun process(ticket: Long, job: SpeechJob, listener: Listener) {
        if (isCancelled(ticket)) return
        busy = true
        idleTimer?.cancel()
        idleTimer = null
        useToken++
        try {
            val model = File(job.modelPath)
            val tokens = File(job.tokensPath)
            if (!voice.available(model, tokens)) {
                if (!isCancelled(ticket)) listener.onError(SpeechProtocol.MODEL_MISSING, "arquivo ausente")
                return
            }
            val available = availableMb()
            if (job.ramEstimateMb > 0 && available != null && available < job.ramEstimateMb) {
                if (!isCancelled(ticket)) listener.onError(SpeechProtocol.NOT_ENOUGH_RAM, "ram")
                return
            }
            if (isCancelled(ticket)) return
            resetPeak()
            voice.synthesize(model, tokens, job.text, job.speed, { isCancelled(ticket) }) { pcm, rate ->
                if (!isCancelled(ticket) && pcm.isNotEmpty()) listener.onPcm(pcm, rate)
            }
            if (!isCancelled(ticket)) listener.onDone(peakRssKb())
        } catch (e: OutOfMemoryError) {
            runCatching { voice.close() }
            if (!isCancelled(ticket)) listener.onError(SpeechProtocol.OUT_OF_MEMORY, "oom")
        } catch (e: Exception) {
            if (!isCancelled(ticket)) listener.onError(SpeechProtocol.ENGINE, e.message ?: e.javaClass.simpleName)
        } finally {
            busy = false
            scheduleIdle()
        }
    }

    private fun scheduleIdle() {
        if (idleUnloadMs <= 0) return
        val token = useToken
        idleTimer = scheduler.schedule(idleUnloadMs) { executor.execute { onIdle(token) } }
    }

    private fun onIdle(token: Long) {
        if (token != useToken || busy) return
        runCatching { voice.close() }
    }
}
