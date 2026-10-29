package com.keyflow.speech.cloud

import com.google.common.truth.Truth.assertThat
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Test

class ElevenLabsAndGoogleSpeechEngineTest {
    private val server = MockWebServer().also { it.start() }
    private val base get() = server.url("/").toString().trimEnd('/')

    @After fun tearDown() = server.shutdown()

    private val voiceId = "JBFqnCBsd6RMkjVDRZzb"

    @Test
    fun `elevenlabs posts to the stream path with xi-api-key and pcm_24000`() {
        server.enqueue(pcmResponse(24_000))
        val events = ElevenLabsSpeechEngine(OkHttpClient(), { "xi-falso" }, voiceId, "eleven_multilingual_v2", base).collect("Oi", 2f)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/text-to-speech/$voiceId/stream?output_format=pcm_24000")
        assertThat(req.getHeader("xi-api-key")).isEqualTo("xi-falso")
        assertThat(req.getHeader("Authorization")).isNull()
        val json = JSONObject(req.body.readUtf8())
        assertThat(json.getString("text")).isEqualTo("Oi")
        assertThat(json.getString("model_id")).isEqualTo("eleven_multilingual_v2")
        assertThat(json.getJSONObject("voice_settings").getDouble("speed")).isWithin(1e-6).of(1.2) // limitada
        assertThat((events.last() as SpeechEvent.Done).durationMs).isEqualTo(500)
    }

    @Test
    fun `elevenlabs voice ids are 20 alphanumerics and names are not ids`() {
        assertThat(ElevenLabsSpeechEngine.isVoiceId(voiceId)).isTrue()
        assertThat(ElevenLabsSpeechEngine.isVoiceId("Rachel")).isFalse()
        assertThat(ElevenLabsSpeechEngine.isVoiceId("")).isFalse()
    }

    @Test
    fun `elevenlabs 429 is RATE_LIMIT`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val e = ElevenLabsSpeechEngine(OkHttpClient(), { "k" }, voiceId, "m", base).collect().single() as SpeechEvent.Error
        assertThat(e.kind).isEqualTo(SpeechError.RATE_LIMIT)
    }

    private fun googleBody(frames: Int) = JSONObject()
        .put("audioContent", wavBytes(frames).toByteString().base64()).toString()

    @Test
    fun `google posts text synthesize with linear16 and reads the wav in audioContent`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(Buffer().writeUtf8(googleBody(12_000))))
        val events = GoogleSpeechEngine(OkHttpClient(), { "AIza-falso" }, "pt-BR-Neural2-A", base).collect("Olá", 1.5f)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/text:synthesize")
        assertThat(req.getHeader("X-Goog-Api-Key")).isEqualTo("AIza-falso")
        val json = JSONObject(req.body.readUtf8())
        assertThat(json.getJSONObject("input").getString("text")).isEqualTo("Olá")
        assertThat(json.getJSONObject("voice").getString("languageCode")).isEqualTo("pt-BR")
        assertThat(json.getJSONObject("voice").getString("name")).isEqualTo("pt-BR-Neural2-A")
        val cfg = json.getJSONObject("audioConfig")
        assertThat(cfg.getString("audioEncoding")).isEqualTo("LINEAR16")
        assertThat(cfg.getDouble("speakingRate")).isWithin(1e-6).of(1.5)
        assertThat(events.pcmBytes()).isEqualTo(24_000)
        assertThat((events.last() as SpeechEvent.Done).durationMs).isEqualTo(500)
    }

    @Test
    fun `google 403 is UNAUTHORIZED and a body without audio is ENGINE`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"API key not valid AIza-falso"}}"""))
        server.enqueue(MockResponse().setBody("""{"audioContent":""}"""))
        val engine = GoogleSpeechEngine(OkHttpClient(), { "AIza-falso" }, "pt-BR-Neural2-A", base)
        val denied = engine.collect().single() as SpeechEvent.Error
        assertThat(denied.kind).isEqualTo(SpeechError.UNAUTHORIZED)
        assertThat(denied.message).doesNotContain("AIza-falso")
        assertThat((engine.collect().single() as SpeechEvent.Error).kind).isEqualTo(SpeechError.ENGINE)
    }

    @Test
    fun `language comes from the voice name`() {
        assertThat(GoogleSpeechEngine.languageOf("en-US-Journey-F")).isEqualTo("en-US")
        assertThat(GoogleSpeechEngine.languageOf("Journey-F")).isEqualTo("en-US")
    }

    @Test
    fun `elevenlabs text above 5000 characters is refused before any request`() {
        val e = ElevenLabsSpeechEngine(OkHttpClient(), { "k" }, voiceId, "m", base).collect("a".repeat(5001)).single() as SpeechEvent.Error
        assertThat(e.message).contains("5000")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `google invalid key and disabled service say to enable the text-to-speech api`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}"""))
        val engine = GoogleSpeechEngine(OkHttpClient(), { "AIza-falso" }, "pt-BR-Neural2-A", base)
        repeat(2) {
            val e = engine.collect().single() as SpeechEvent.Error
            assertThat(e.kind).isEqualTo(SpeechError.UNAUTHORIZED)
            assertThat(e.message).contains("Cloud Text-to-Speech")
            assertThat(e.message).doesNotContain("HTTP")
        }
    }

    @Test
    fun `custom headers are not sent to a redirect target`() {
        val other = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/x").toString()))
            val engine = CustomSpeechEngine(
                OkHttpClient(), server.url("/v1/audio/speech").toString(), "Authorization", "Bearer falso", "m", "v",
                com.keyflow.speech.SpeechFormat.WAV, CompressedAudioDecoder.Unsupported,
            )
            assertThat((engine.collect().single() as SpeechEvent.Error).kind).isEqualTo(SpeechError.ENGINE)
            assertThat(other.requestCount).isEqualTo(0)
        } finally {
            other.shutdown()
        }
    }
}
