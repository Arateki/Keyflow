package com.keyflow.reasoning

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.AuthorizedHeaders
import com.keyflow.auth.ProviderId
import com.keyflow.reasoning.anthropic.AnthropicReasoningEngine
import com.keyflow.reasoning.custom.CustomReasoningEngine
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
import org.junit.After
import org.junit.Test

class OnlineGenerationOptionsTest {
    private val server = MockWebServer().also { it.start() }
    private val http = OkHttpClient()
    private val base get() = server.url("/v1").toString().trimEnd('/')
    private fun auth(key: Boolean) = object : AuthorizationProvider {
        override suspend fun headers(provider: ProviderId) = mapOf("Authorization" to "Bearer credencial-falsa")
        override suspend fun authorizedHeaders(provider: ProviderId) = AuthorizedHeaders(headers(provider), key)
    }
    private fun run(engine: ReasoningEngine, options: GenerationOptions, sse: String): Pair<JSONObject, ReasoningEvent.Metrics> {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        val events = runBlocking { withTimeout(10_000) { engine.generate(ReasoningRequest(user = "oi", options = options)).toList() } }
        assertThat(events.last()).isEqualTo(ReasoningEvent.Done)
        val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
        return JSONObject(sent.body.readUtf8()) to events.filterIsInstance<ReasoningEvent.Metrics>().single()
    }
    @After fun fechar() = server.shutdown()

    @Test fun `openai chave envia campos documentados e contabiliza usage`() {
        val o = GenerationOptions(temperature = 0.0, seed = 42, maxOutputTokens = 90)
        val stream = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}}\n\n"
        val (body, m) = run(OpenAiReasoningEngine(auth(true), http, "modelo", base), o, stream)
        assertThat(body.getDouble("temperature")).isEqualTo(0.0)
        assertThat(body.getInt("max_output_tokens")).isEqualTo(90)
        assertThat(body.has("seed")).isFalse()
        assertThat(m.applied).containsExactly("temperature", "0.0", "maxOutputTokens", "90")
        assertThat(m.ignored).containsExactly("seed")
        assertThat(m.inputTokens).isEqualTo(11)
        assertThat(m.outputTokens).isEqualTo(7)
    }

    @Test fun `openai plano ignora opcoes e usage ausente fica nulo`() {
        val o = GenerationOptions(temperature = 0.0, maxOutputTokens = 90)
        val (body, m) = run(OpenAiReasoningEngine(auth(false), http, "modelo", base), o,
            "event: response.completed\ndata: {\"type\":\"response.completed\"}\n\n")
        assertThat(body.has("temperature")).isFalse()
        assertThat(body.has("max_output_tokens")).isFalse()
        assertThat(m.ignored).containsExactly("temperature", "maxOutputTokens")
        assertThat(m.inputTokens).isNull()
        assertThat(m.outputTokens).isNull()
    }

    @Test fun `anthropic aplica amostragem antiga e reune usage do inicio e delta`() {
        val o = GenerationOptions(temperature = 0.0, seed = 42, maxOutputTokens = 90)
        val stream = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":12}}}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":8}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        val (body, m) = run(AnthropicReasoningEngine(auth(true), http, "claude-3-5-sonnet", base), o, stream)
        assertThat(body.getDouble("temperature")).isEqualTo(0.0)
        assertThat(body.getInt("max_tokens")).isEqualTo(90)
        assertThat(body.has("seed")).isFalse()
        assertThat(m.ignored).containsExactly("seed")
        assertThat(m.inputTokens).isEqualTo(12)
        assertThat(m.outputTokens).isEqualTo(8)
    }

    @Test fun `anthropic aceita pensamento apenas com orcamento e teto suficientes`() {
        val o = GenerationOptions(thinking = true, maxOutputTokens = 2048, temperature = 0.0)
        val (body, m) = run(AnthropicReasoningEngine(auth(true), http, "claude-3-7-sonnet", base), o,
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        assertThat(body.getJSONObject("thinking").getString("type")).isEqualTo("enabled")
        assertThat(body.getJSONObject("thinking").getInt("budget_tokens")).isEqualTo(1024)
        assertThat(body.has("temperature")).isFalse()
        assertThat(m.ignored).containsExactly("temperature")
        val (body45, m45) = run(AnthropicReasoningEngine(auth(true), http, "claude-sonnet-4-5", base), o,
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        assertThat(body45.getJSONObject("thinking").getInt("budget_tokens")).isEqualTo(1024)
        assertThat(m45.applied).containsEntry("thinking", "true")
    }

    @Test fun `usage ausente fica nulo em cada provedor`() {
        val cases = listOf(
            AnthropicReasoningEngine(auth(true), http, "claude-3-5-sonnet", base) to "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            GoogleReasoningEngine(auth(true), http, "gemini-2.5-flash", base) to "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n",
            CustomReasoningEngine(server.url("/custom").toString(), "modelo", http) to "data: [DONE]\n\n",
        )
        cases.forEach { (engine, stream) ->
            val (_, m) = run(engine, GenerationOptions(), stream)
            assertThat(m.inputTokens).isNull()
            assertThat(m.outputTokens).isNull()
        }
    }

    @Test fun `gemini captura usage depois da parada e envia parametros`() {
        val o = GenerationOptions(temperature = 0.0, seed = 42, maxOutputTokens = 90, thinking = false)
        val stream = "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n" +
            "data: {\"usageMetadata\":{\"promptTokenCount\":13,\"candidatesTokenCount\":9}}\n\n"
        val (body, m) = run(GoogleReasoningEngine(auth(true), http, "gemini-2.5-flash", base), o, stream)
        val config = body.getJSONObject("generationConfig")
        assertThat(config.getDouble("temperature")).isEqualTo(0.0)
        assertThat(config.getInt("seed")).isEqualTo(42)
        assertThat(config.getInt("maxOutputTokens")).isEqualTo(90)
        assertThat(config.getJSONObject("thinkingConfig").getInt("thinkingBudget")).isEqualTo(0)
        assertThat(m.ignored).isEmpty()
        assertThat(m.inputTokens).isEqualTo(13)
        assertThat(m.outputTokens).isEqualTo(9)
    }

    @Test fun `custom usa parametros de Chat Completions e usage final`() {
        val o = GenerationOptions(temperature = 0.0, seed = 42, topK = 10, maxOutputTokens = 90)
        val stream = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":14,\"completion_tokens\":10}}\n\ndata: [DONE]\n\n"
        val (body, m) = run(CustomReasoningEngine(server.url("/chat").toString(), "modelo", http), o, stream)
        assertThat(body.getInt("seed")).isEqualTo(42)
        assertThat(body.getInt("max_completion_tokens")).isEqualTo(90)
        assertThat(body.getJSONObject("stream_options").getBoolean("include_usage")).isTrue()
        assertThat(body.has("top_k")).isFalse()
        assertThat(m.ignored).containsExactly("topK")
        assertThat(m.inputTokens).isEqualTo(14)
        assertThat(m.outputTokens).isEqualTo(10)
    }
}
