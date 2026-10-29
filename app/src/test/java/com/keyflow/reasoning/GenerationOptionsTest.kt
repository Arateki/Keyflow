package com.keyflow.reasoning

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.ProviderId
import com.keyflow.reasoning.anthropic.AnthropicReasoningEngine
import com.keyflow.reasoning.google.GoogleReasoningEngine
import com.keyflow.reasoning.openai.OpenAiReasoningEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Test

/**
 * BNC-T8 / BEX-02: as opções de geração são um contrato novo, mas **nulo = comportamento de hoje**. Este teste prova a metade que
 * importa enquanto os motores ainda não leem as opções: com a lista vazia (e com `GenerationOptions()` explícito) o corpo que cada
 * motor online manda é, campo a campo, o de sempre.
 */
class GenerationOptionsTest {

    private val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val auth = object : AuthorizationProvider {
        override suspend fun headers(provider: ProviderId) = mapOf("Authorization" to "Bearer tok-123")
    }
    private val base get() = server.url("/v1").toString().trimEnd('/')

    @After fun tearDown() = server.shutdown()

    private val sse = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"x\"}}\n\n" +
        "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

    private fun bodyRaw(engine: ReasoningEngine, request: ReasoningRequest): String {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sse))
        runBlocking { withTimeout(10_000) { engine.generate(request).toList() } }
        return server.takeRequest().body.readUtf8()
    }

    private fun keysOf(json: String): Set<String> = JSONObject(json).keys().asSequence().toSet()

    @Test
    fun `default options are nothing requested`() {
        assertThat(GenerationOptions().isDefault).isTrue()
        assertThat(GenerationOptions().requestedNames()).isEmpty()
        assertThat(GenerationOptions().requestedValues()).isEmpty()
        assertThat(ReasoningRequest(user = "oi")).isEqualTo(ReasoningRequest(user = "oi", options = GenerationOptions()))
    }

    @Test
    fun `requested options are listed in a fixed order with their values`() {
        val options = GenerationOptions(temperature = 0.0, seed = 42, maxOutputTokens = 96, thinking = false)
        assertThat(options.isDefault).isFalse()
        assertThat(options.requestedNames()).containsExactly("temperature", "seed", "maxOutputTokens", "thinking").inOrder()
        assertThat(options.requestedValues())
            .containsExactly("temperature", "0.0", "seed", "42", "maxOutputTokens", "96", "thinking", "false").inOrder()
    }

    @Test
    fun `anthropic body with default options is the body of today`() {
        val withSystem = ReasoningRequest(system = "sistema", user = "olá")
        val keyAuth = object : AuthorizationProvider {
            override suspend fun headers(provider: ProviderId) = mapOf("x-api-key" to "test-anthropic-key")
        }
        val engine = AnthropicReasoningEngine(keyAuth, client, "claude-x", apiBaseUrl = base)
        val rawToday = bodyRaw(engine, withSystem)
        val today = JSONObject(rawToday)
        // org.json guarda as chaves num HashMap: a ordem não é do contrato, o conjunto de campos e os valores são.
        assertThat(today.keys().asSequence().toSet()).containsExactly("model", "max_tokens", "stream", "messages", "system")
        val original = JSONObject().put("model", "claude-x").put("max_tokens", 1024).put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "olá"))).put("system", "sistema")
        assertThat(rawToday).isEqualTo(original.toString())
        assertThat(today.getString("model")).isEqualTo("claude-x")
        assertThat(today.getInt("max_tokens")).isEqualTo(1024)
        assertThat(today.getBoolean("stream")).isTrue()
        assertThat(today.getString("system")).isEqualTo("sistema")
        assertThat(today.getJSONArray("messages").toString()).isEqualTo("""[{"role":"user","content":"olá"}]""")
        assertThat(bodyRaw(engine, withSystem.copy(options = GenerationOptions()))).isEqualTo(rawToday)
    }

    @Test
    fun `openai body with default options is the body of today`() {
        val completed = "event: response.completed\ndata: {\"type\":\"response.completed\"}\n\n"
        val engine = OpenAiReasoningEngine(auth, client, "modelo-x", apiBaseUrl = base, pauseMs = {})
        val request = ReasoningRequest(system = "sistema", user = "olá")
        fun body(r: ReasoningRequest): String {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(completed))
            runBlocking { withTimeout(10_000) { engine.generate(r).toList() } }
            return server.takeRequest().body.readUtf8()
        }
        val today = body(request)
        val original = JSONObject().put("model", "modelo-x")
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", "olá")))
            .put("store", false).put("stream", true).put("instructions", "sistema").toString()
        assertThat(today).isEqualTo(original)
        assertThat(keysOf(today)).containsExactly("model", "input", "store", "stream", "instructions")
        assertThat(body(request.copy(options = GenerationOptions()))).isEqualTo(today)
    }

    @Test
    fun `google body with default options is the body of today`() {
        val sseGoogle = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]},\"finishReason\":\"STOP\"}]}\n\n"
        val engine = GoogleReasoningEngine(auth, client, "gemini-x", apiBaseUrl = base)
        val request = ReasoningRequest(system = "sistema", user = "olá")
        fun body(r: ReasoningRequest): String {
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseGoogle))
            runBlocking { withTimeout(10_000) { engine.generate(r).toList() } }
            return server.takeRequest().body.readUtf8()
        }
        val today = body(request)
        val original = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", "olá")))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "sistema")))).toString()
        assertThat(today).isEqualTo(original)
        assertThat(keysOf(today)).containsExactly("contents", "systemInstruction")
        assertThat(body(request.copy(options = GenerationOptions()))).isEqualTo(today)
    }
}
