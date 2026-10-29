package com.keyflow.speech

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.FalaSource
import com.keyflow.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test

/** FENG-01/02/05: o contrato, o relógio de latência e a escolha do motor pela origem gravada. */
class SpeechEngineTest {

    private fun pcm(samples: Int) = ByteArray(samples * 2)

    /** Motor falso: dois buffers de 12 000 amostras a 24 kHz (0,5 s no total) e o relógio andando 40 ms até o primeiro. */
    private class FakeEngine(val ticks: MutableList<Long> = mutableListOf(0L, 40L, 90L)) : SpeechEngine {
        override fun speak(request: SpeechRequest): Flow<SpeechEvent> = flow {
            val clock = SpeechClock { ticks.removeAt(0) }
            val a = ByteArray(24_000)
            emit(SpeechEvent.Audio(a, 24_000))
            clock.onAudio(a, 24_000)?.let { emit(it) }
            val b = ByteArray(24_000)
            emit(SpeechEvent.Audio(b, 24_000))
            clock.onAudio(b, 24_000)
            emit(clock.done())
        }
    }

    @Test
    fun `fake engine emits audio then first audio then done with the sample duration`() = runBlocking {
        val events = FakeEngine().speak(SpeechRequest("oi", 1f)).toList()
        assertThat(events.map { it::class.simpleName }).containsExactly("Audio", "FirstAudio", "Audio", "Done").inOrder()
        assertThat((events[1] as SpeechEvent.FirstAudio).latencyMs).isEqualTo(40)
        val done = events.last() as SpeechEvent.Done
        assertThat(done.durationMs).isEqualTo(1000) // 24 000 amostras a 24 kHz
        assertThat(done.firstAudioMs).isEqualTo(40)
    }

    @Test
    fun `cancelling in the middle never emits done`() = runBlocking {
        val events = FakeEngine().speak(SpeechRequest("oi", 1f)).take(1).toList()
        assertThat(events.filterIsInstance<SpeechEvent.Done>()).isEmpty()
    }

    @Test
    fun `clock marks the first audio once and ignores empty buffers`() {
        var now = 0L
        val clock = SpeechClock { now }
        assertThat(clock.onAudio(ByteArray(0), 24_000)).isNull()
        now = 25
        assertThat(clock.onAudio(pcm(100), 24_000)).isEqualTo(SpeechEvent.FirstAudio(25))
        now = 80
        assertThat(clock.onAudio(pcm(100), 24_000)).isNull()
        assertThat(clock.done().firstAudioMs).isEqualTo(25)
    }

    @Test
    fun `done falls back to the wall clock when there are no samples`() {
        val clock = SpeechClock { 0 }
        clock.markFirstAudio()
        assertThat(clock.done(fallbackDurationMs = 700).durationMs).isEqualTo(700)
    }

    private val deps = SpeechDeps(http = OkHttpClient(), apiKey = { null })

    @Test
    fun `local without a model id emits MODEL_MISSING and does not switch the source`() = runBlocking {
        val settings = Settings(falaSource = FalaSource.LOCAL, falaLocalModelId = null)
        val first = SpeechEngines.forSource(settings, deps).speak(SpeechRequest("oi", 1f)).first()
        assertThat((first as SpeechEvent.Error).kind).isEqualTo(SpeechError.MODEL_MISSING)
        assertThat(settings.falaSource).isEqualTo(FalaSource.LOCAL)
    }

    @Test
    fun `local with an id that is not installed emits MODEL_MISSING`() = runBlocking {
        val settings = Settings(falaSource = FalaSource.LOCAL, falaLocalModelId = "x")
        val first = SpeechEngines.forSource(settings, deps).speak(SpeechRequest("oi", 1f)).first()
        assertThat((first as SpeechEvent.Error).kind).isEqualTo(SpeechError.MODEL_MISSING)
    }

    @Test
    fun `local installed but without an engine in this build is NOT_READY`() = runBlocking {
        val withInstalled = SpeechDeps(OkHttpClient(), { null }, isModelInstalled = { true })
        val first = SpeechEngines.forSource(Settings(falaLocalModelId = "x"), withInstalled).speak(SpeechRequest("oi", 1f)).first()
        assertThat((first as SpeechEvent.Error).kind).isEqualTo(SpeechError.NOT_READY)
    }

    @Test
    fun `availability follows the source and what is missing`() {
        fun a(s: Settings, d: SpeechDeps = deps) = SpeechEngines.availability(s, d)
        assertThat(a(Settings())).isEqualTo(SpeechAvailability.NO_LOCAL_MODEL)
        assertThat(a(Settings(falaLocalModelId = "x"))).isEqualTo(SpeechAvailability.MODEL_NOT_INSTALLED)
        assertThat(a(Settings(falaLocalModelId = "x"), SpeechDeps(OkHttpClient(), { null }, isModelInstalled = { true })))
            .isEqualTo(SpeechAvailability.READY)
        assertThat(a(Settings(falaSource = FalaSource.OPENAI))).isEqualTo(SpeechAvailability.NOT_CONNECTED)
        assertThat(a(Settings(falaSource = FalaSource.OPENAI), SpeechDeps(OkHttpClient(), { "k" }))).isEqualTo(SpeechAvailability.READY)
        assertThat(a(Settings(falaSource = FalaSource.CUSTOM))).isEqualTo(SpeechAvailability.NO_ENDPOINT)
        assertThat(a(Settings(falaSource = FalaSource.CUSTOM, falaCustomEndpoint = "https://x/v1/audio/speech"))).isEqualTo(SpeechAvailability.READY)
        assertThat(a(Settings(falaSource = FalaSource.SYSTEM))).isEqualTo(SpeechAvailability.READY)
        assertThat(a(Settings(falaSource = FalaSource.ELEVENLABS, falaElevenLabsVoice = "Rachel"), SpeechDeps(OkHttpClient(), { "k" })))
            .isEqualTo(SpeechAvailability.NO_VOICE)
    }

    @Test
    fun `default source of a new Settings stays LOCAL`() {
        assertThat(Settings().falaSource).isEqualTo(FalaSource.LOCAL)
    }
}
