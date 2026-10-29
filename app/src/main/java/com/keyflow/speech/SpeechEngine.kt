package com.keyflow.speech

import kotlinx.coroutines.flow.Flow

/** Um pedido de fala: só o texto e a velocidade. Origem, voz, modelo e formato vêm das `Settings` do momento em que o motor foi montado. */
data class SpeechRequest(val text: String, val speed: Float)

/** Por que a fala falhou (FENG-02). A mensagem do [SpeechEvent.Error] já vem traduzida e curta, sem corpo cru de resposta. */
enum class SpeechError { MODEL_MISSING, TTS_DATA_MISSING, NOT_READY, NETWORK, UNAUTHORIZED, RATE_LIMIT, ENGINE }

/**
 * Os eventos de uma fala, nesta ordem quando dá certo: zero ou mais [Audio], um [FirstAudio] junto do primeiro [Audio] e um [Done].
 * Falha emite um [Error] e nada depois.
 */
sealed interface SpeechEvent {
    /** PCM 16-bit, mono, little-endian. Vazio só no Sistema, quando o motor do aparelho fala sem entregar amostras. */
    class Audio(val pcm: ByteArray, val sampleRate: Int) : SpeechEvent {
        override fun toString() = "Audio(${pcm.size} bytes, $sampleRate Hz)"
    }

    /** Relógio desde `speak` até o primeiro buffer PCM pronto. */
    data class FirstAudio(val latencyMs: Long) : SpeechEvent

    /** [durationMs] é amostras ÷ taxa (ou o relógio entre `onStart` e `onDone` no Sistema sem amostras). */
    data class Done(val durationMs: Long, val firstAudioMs: Long) : SpeechEvent

    data class Error(val kind: SpeechError, val message: String) : SpeechEvent
}

/** Texto entra, eventos de áudio saem. Cancelar a coleta cancela a síntese e a chamada. A tela e um passo futuro de Fluxos usam o mesmo contrato. */
interface SpeechEngine {
    fun speak(request: SpeechRequest): Flow<SpeechEvent>
}

/** Conta amostras e tempo de uma fala para montar [SpeechEvent.FirstAudio] e [SpeechEvent.Done] do mesmo jeito em todo motor. */
class SpeechClock(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val start = nowMs()
    private var firstAudioMs = -1L
    private var samples = 0L
    private var rate = 0

    /** Registra um buffer; devolve o [SpeechEvent.FirstAudio] quando é o primeiro com amostras. */
    fun onAudio(pcm: ByteArray, sampleRate: Int): SpeechEvent.FirstAudio? {
        samples += pcm.size / 2
        if (rate == 0) rate = sampleRate
        if (firstAudioMs >= 0 || pcm.isEmpty()) return null
        firstAudioMs = nowMs() - start
        return SpeechEvent.FirstAudio(firstAudioMs)
    }

    /** Marca o primeiro áudio sem amostras (Sistema: `onStart`). */
    fun markFirstAudio(): SpeechEvent.FirstAudio? {
        if (firstAudioMs >= 0) return null
        firstAudioMs = nowMs() - start
        return SpeechEvent.FirstAudio(firstAudioMs)
    }

    fun elapsedMs(): Long = nowMs() - start

    fun done(fallbackDurationMs: Long? = null): SpeechEvent.Done {
        val duration = if (samples > 0 && rate > 0) samples * 1000 / rate else fallbackDurationMs ?: 0L
        return SpeechEvent.Done(duration, firstAudioMs.coerceAtLeast(0))
    }
}
