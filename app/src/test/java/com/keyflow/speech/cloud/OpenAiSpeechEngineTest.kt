package com.keyflow.speech.cloud

import com.google.common.truth.Truth.assertThat
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechFormat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/** FCLD-02/07: OpenAI `audio/speech` com `pcm`, sem ler cota, e o mapa de status. */
class OpenAiSpeechEngineTest {
    private val server = MockWebServer().also { it.start() }
    private val fakeKey = "sk-teste-falso"

    @After fun tearDown() = server.shutdown()

    private fun engine(key: String? = fakeKey, voice: String = "marin") = OpenAiSpeechEngine(
        OkHttpClient(), { key }, model = "gpt-4o-mini-tts", voice = voice, baseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `sends the openai json with pcm and a bearer and streams 24 kHz audio`() {
        server.enqueue(pcmResponse(48_000))
        val events = engine().collect("Bom dia", speed = 1.25f)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/audio/speech")
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer $fakeKey")
        val json = JSONObject(req.body.readUtf8())
        assertThat(json.getString("model")).isEqualTo("gpt-4o-mini-tts")
        assertThat(json.getString("input")).isEqualTo("Bom dia")
        assertThat(json.getString("voice")).isEqualTo("marin")
        assertThat(json.getString("response_format")).isEqualTo("pcm")
        assertThat(json.getDouble("speed")).isWithin(1e-6).of(1.25)
        assertThat(server.requestCount).isEqualTo(1) // nada de leitura de cota

        assertThat(events.pcmBytes()).isEqualTo(48_000)
        assertThat(events.filterIsInstance<SpeechEvent.Audio>().all { it.sampleRate == 24_000 }).isTrue()
        assertThat(events.filterIsInstance<SpeechEvent.FirstAudio>()).hasSize(1)
        val done = events.last() as SpeechEvent.Done
        assertThat(done.durationMs).isEqualTo(1000)
        // FirstAudio vem logo depois do primeiro Audio
        assertThat(events.indexOfFirst { it is SpeechEvent.FirstAudio }).isEqualTo(events.indexOfFirst { it is SpeechEvent.Audio } + 1)
    }

    @Test
    fun `an odd trailing byte is never emitted as a half sample`() {
        server.enqueue(pcmResponse(9_999))
        assertThat(engine().collect().pcmBytes() % 2).isEqualTo(0)
    }

    @Test
    fun `speed outside the api range is clamped and not an error`() {
        server.enqueue(pcmResponse(10))
        engine().collect(speed = 9f)
        assertThat(JSONObject(server.takeRequest().body.readUtf8()).getDouble("speed")).isWithin(1e-6).of(4.0)
    }

    @Test
    fun `401 becomes UNAUTHORIZED without the raw body`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key sk-vazou-123"}}"""))
        val error = engine().collect().single() as SpeechEvent.Error
        assertThat(error.kind).isEqualTo(SpeechError.UNAUTHORIZED)
        assertThat(error.message).doesNotContain("sk-vazou")
        assertThat(error.message).doesNotContain("{")
    }

    @Test
    fun `429 becomes RATE_LIMIT and 500 becomes ENGINE`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>boom</html>"))
        assertThat((engine().collect().single() as SpeechEvent.Error).kind).isEqualTo(SpeechError.RATE_LIMIT)
        val e = engine().collect().single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.ENGINE)
        assertThat(e.message).doesNotContain("<html>")
    }

    @Test
    fun `no key fails before any request`() {
        val error = engine(key = "").collect().single() as SpeechEvent.Error
        assertThat(error.kind).isEqualTo(SpeechError.UNAUTHORIZED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a json body with 200 is not audio`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ok":true}"""))
        val error = engine().collect().single() as SpeechEvent.Error
        assertThat(error.kind).isEqualTo(SpeechError.ENGINE)
    }

    @Test
    fun `a dropped connection is NETWORK`() {
        server.shutdown()
        val e = engine().collect().single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.NETWORK)
    }

    @Test
    fun `the model is the one the openai doc lists`() {
        assertThat(SpeechFormat.PCM.wire).isEqualTo("pcm")
        assertThat(com.keyflow.speech.SpeechEngines.OPENAI_MODEL).isEqualTo("gpt-4o-mini-tts")
    }

    @Test
    fun `text above the openai limit is refused before any request`() {
        val e = engine().collect("a".repeat(4097)).single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.ENGINE)
        assertThat(e.message).contains("4096")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a redirect is not followed and the key never reaches another host`() {
        val other = MockWebServer().also { it.start() }
        try {
            other.enqueue(pcmResponse(100))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal").toString()))
            val e = engine().collect().single() as SpeechEvent.Error
            assertThat(e.kind).isEqualTo(SpeechError.ENGINE)
            assertThat(other.requestCount).isEqualTo(0)
        } finally {
            other.shutdown()
        }
    }
}
