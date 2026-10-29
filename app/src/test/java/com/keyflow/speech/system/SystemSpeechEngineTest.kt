package com.keyflow.speech.system

import android.speech.tts.TextToSpeech
import com.google.common.truth.Truth.assertThat
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.util.Locale
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** FSYS-02/03: o motor de sistema sobre um `TextToSpeech` falso. */
class SystemSpeechEngineTest {

    private class FakeTts(
        val ready: Boolean = true,
        val available: Int = TextToSpeech.LANG_AVAILABLE,
        val setResult: Int = TextToSpeech.LANG_AVAILABLE,
        val script: (SystemTts.Callbacks) -> Unit = { it.onStart(); it.onDone() },
    ) : SystemTts {
        var rate = 0f
        var language: Locale? = null
        var spoken: String? = null
        override suspend fun ready() = ready
        override fun setLanguage(locale: Locale): Int { language = locale; return setResult }
        override fun isLanguageAvailable(locale: Locale) = available
        override fun setSpeechRate(rate: Float) { this.rate = rate }
        override fun speak(text: String, utteranceId: String, callbacks: SystemTts.Callbacks) { spoken = text; script(callbacks) }
        override fun stop() = Unit
    }

    private fun run(tts: FakeTts, now: () -> Long = { 0 }): List<SpeechEvent> = runBlocking {
        SystemSpeechEngine({ tts }, { Locale("pt", "BR") }, { Locale.US }, nowMs = now).speak(SpeechRequest("oi", 0.75f)).toList()
    }

    @Test
    fun `onStart makes the first audio and onDone the duration by the wall clock`() {
        var now = 100L
        val tts = FakeTts(script = { cb ->
            now = 130; cb.onStart()
            now = 930; cb.onDone()
        })
        val events = run(tts) { now }
        assertThat(events.map { it::class.simpleName }).containsExactly("Audio", "FirstAudio", "Done").inOrder()
        assertThat((events[0] as SpeechEvent.Audio).pcm).isEmpty()
        assertThat((events[1] as SpeechEvent.FirstAudio).latencyMs).isEqualTo(30)
        assertThat((events[2] as SpeechEvent.Done).durationMs).isEqualTo(800)
        assertThat(tts.rate).isEqualTo(0.75f)
        assertThat(tts.spoken).isEqualTo("oi")
        assertThat(tts.language).isEqualTo(Locale("pt", "BR"))
    }

    @Test
    fun `falls back to the device language when the interface one is not available`() {
        val tts = FakeTts(available = TextToSpeech.LANG_NOT_SUPPORTED)
        run(tts)
        assertThat(tts.language).isEqualTo(Locale.US)
    }

    @Test
    fun `missing data becomes TTS_DATA_MISSING and nothing is spoken`() {
        val tts = FakeTts(setResult = TextToSpeech.LANG_MISSING_DATA)
        val e = run(tts).single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.TTS_DATA_MISSING)
        assertThat(tts.spoken).isNull()
    }

    @Test
    fun `unsupported language becomes ENGINE with the spec phrase`() {
        val e = run(FakeTts(setResult = TextToSpeech.LANG_NOT_SUPPORTED)).single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.ENGINE)
        assertThat(e.message).isEqualTo("Este aparelho não tem voz para este idioma.")
    }

    @Test
    fun `no engine installed is NOT_READY and an utterance error is ENGINE`() {
        assertThat((run(FakeTts(ready = false)).single() as SpeechEvent.Error).kind).isEqualTo(SpeechError.NOT_READY)
        assertThat((run(FakeTts(script = { it.onError() })).single() as SpeechEvent.Error).kind).isEqualTo(SpeechError.ENGINE)
    }
}
