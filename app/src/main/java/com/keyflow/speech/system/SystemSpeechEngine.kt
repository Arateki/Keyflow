package com.keyflow.speech.system

import android.speech.tts.TextToSpeech
import com.keyflow.i18n.tr
import com.keyflow.speech.SpeechClock
import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** O `TextToSpeech` do aparelho visto pelo motor: o que os testes substituem (o motor do emulador não é gate de CI). */
interface SystemTts {
    /** Espera o `OnInitListener`. Falso = o aparelho não tem motor de voz. */
    suspend fun ready(): Boolean

    /** Um dos `TextToSpeech.LANG_*`. */
    fun setLanguage(locale: Locale): Int
    fun isLanguageAvailable(locale: Locale): Int
    fun setSpeechRate(rate: Float)
    fun speak(text: String, utteranceId: String, callbacks: Callbacks)
    fun stop()

    interface Callbacks {
        fun onStart()
        fun onDone()
        fun onError()
    }
}

/**
 * Voz do sistema (FSYS-02/03): fala pelo `TextToSpeech` instalado. Sem amostras: `onStart` marca o 1º áudio e a duração é o relógio entre
 * `onStart` e `onDone`; o [SpeechEvent.Audio] sai vazio (o som já foi para o alto-falante do sistema). O idioma é o da interface se o motor
 * o tem, senão o do aparelho. [tts] é o que [open] cria; cada fala pede um novo, e ele é liberado quando a coleta termina.
 */
class SystemSpeechEngine(
    private val open: () -> SystemTts,
    private val interfaceLocale: () -> Locale,
    private val deviceLocale: () -> Locale = { Locale.getDefault() },
    private val release: (SystemTts) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : SpeechEngine {

    override fun speak(request: SpeechRequest): Flow<SpeechEvent> = channelFlow {
        val clock = SpeechClock(nowMs)
        val tts = open()
        try {
            if (!tts.ready()) {
                send(SpeechEvent.Error(SpeechError.NOT_READY, tr("O aparelho não tem um motor de voz instalado.")))
                return@channelFlow
            }
            val wanted = interfaceLocale()
            val locale = if (tts.isLanguageAvailable(wanted) >= TextToSpeech.LANG_AVAILABLE) wanted else deviceLocale()
            when (tts.setLanguage(locale)) {
                TextToSpeech.LANG_MISSING_DATA -> {
                    send(SpeechEvent.Error(SpeechError.TTS_DATA_MISSING, tr("Faltam os dados de voz deste idioma no aparelho.")))
                    return@channelFlow
                }
                TextToSpeech.LANG_NOT_SUPPORTED -> {
                    send(SpeechEvent.Error(SpeechError.ENGINE, tr("Este aparelho não tem voz para este idioma.")))
                    return@channelFlow
                }
            }
            tts.setSpeechRate(request.speed)
            var startedAt = -1L
            tts.speak(
                request.text, "keyflow-${System.identityHashCode(this)}",
                object : SystemTts.Callbacks {
                    override fun onStart() {
                        startedAt = clock.elapsedMs()
                        val first = clock.markFirstAudio()
                        trySend(SpeechEvent.Audio(ByteArray(0), 0))
                        if (first != null) trySend(first)
                    }

                    override fun onDone() {
                        val duration = if (startedAt >= 0) clock.elapsedMs() - startedAt else 0L
                        trySend(clock.done(duration))
                        close()
                    }

                    override fun onError() {
                        trySend(SpeechEvent.Error(SpeechError.ENGINE, tr("O motor de voz do aparelho falhou.")))
                        close()
                    }
                },
            )
            awaitClose { tts.stop() }
        } finally {
            release(tts)
        }
    }
}
