package com.keyflow.speech.cloud

import com.google.common.truth.Truth.assertThat
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechFormat
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/** FCLD-05: o Custom fala o esquema OpenAI na URL colada; a pista da Groq usa o mesmo corpo. */
class CustomSpeechEngineTest {
    private val server = MockWebServer().also { it.start() }

    @After fun tearDown() = server.shutdown()

    private fun engine(
        format: SpeechFormat,
        decoder: CompressedAudioDecoder = CompressedAudioDecoder.Unsupported,
        headerName: String = "Authorization",
        headerValue: String = "Bearer falso-123",
        endpoint: String = server.url("/openai/v1/audio/speech").toString(),
    ) = CustomSpeechEngine(OkHttpClient(), endpoint, headerName, headerValue, "playai-tts", "Fritz-PlayAI", format, decoder)

    @Test
    fun `posts the same json as openai on the pasted url with the saved header`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wavBytes(2400))))
        val events = engine(SpeechFormat.WAV, headerName = "X-Chave", headerValue = "abc").collect("Hello", 1f)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/openai/v1/audio/speech")
        assertThat(req.getHeader("X-Chave")).isEqualTo("abc")
        val json = JSONObject(req.body.readUtf8())
        assertThat(json.getString("model")).isEqualTo("playai-tts")
        assertThat(json.getString("voice")).isEqualTo("Fritz-PlayAI")
        assertThat(json.getString("input")).isEqualTo("Hello")
        assertThat(json.getString("response_format")).isEqualTo("wav")
        assertThat(events.pcmBytes()).isEqualTo(4800)
        assertThat((events.last() as SpeechEvent.Done).durationMs).isEqualTo(100)
    }

    @Test
    fun `pcm assumes 24 kHz mono 16 bit`() {
        server.enqueue(pcmResponse(48_000))
        val events = engine(SpeechFormat.PCM).collect()
        assertThat(events.filterIsInstance<SpeechEvent.Audio>().first().sampleRate).isEqualTo(24_000)
        assertThat((events.last() as SpeechEvent.Done).durationMs).isEqualTo(1000)
    }

    @Test
    fun `mp3 goes through the injected decoder`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("ID3fake"))
        var seen: SpeechFormat? = null
        val decoder = CompressedAudioDecoder { _, f -> seen = f; DecodedPcm(ByteArray(44_100), 22_050) }
        val events = engine(SpeechFormat.MP3, decoder).collect()
        assertThat(seen).isEqualTo(SpeechFormat.MP3)
        assertThat((events.last() as SpeechEvent.Done).durationMs).isEqualTo(1000)
    }

    @Test
    fun `an endpoint that answers json is ENGINE with the spec phrase`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[]}"""))
        val e = engine(SpeechFormat.MP3).collect().single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.ENGINE)
        assertThat(e.message).isEqualTo("A resposta não é áudio nesse formato.")
    }

    @Test
    fun `a wav request answered with garbage is ENGINE`() {
        server.enqueue(MockResponse().setBody("not a wav"))
        assertThat((engine(SpeechFormat.WAV).collect().single() as SpeechEvent.Error).kind).isEqualTo(SpeechError.ENGINE)
    }

    @Test
    fun `an invalid endpoint url fails cleanly`() {
        val e = engine(SpeechFormat.WAV, endpoint = "isso nao e url").collect().single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.ENGINE)
    }

    @Test
    fun `401 is UNAUTHORIZED`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key falso-123"}"""))
        val e = engine(SpeechFormat.WAV).collect().single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.UNAUTHORIZED)
        assertThat(e.message).doesNotContain("falso-123")
    }
}
