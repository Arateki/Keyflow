package com.keyflow.reasoning.openai

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentToolCall
import com.keyflow.agent.ProviderState
import com.keyflow.agent.TokenUsage
import com.keyflow.agent.ToolDefinition
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.ProviderId
import com.keyflow.auth.ProviderIds
import com.keyflow.reasoning.ReasoningError
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/**
 * AGT-02/T7: o `OpenAiAgentEngine` contra um servidor falso, com SSE no formato da documentação oficial (ver [OpenAiAgentSseFixtures]).
 * Cobre texto, uma e várias chamadas, argumentos em vários `delta`, o raciocínio cifrado indo e voltando, o corte e a falha no meio do
 * fluxo, HTTP 401/429/5xx e o cancelamento. Credenciais FALSAS; nenhuma chamada à OpenAI de verdade.
 */
class OpenAiAgentEngineTest {

    private val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    private class RecordingAuth(private val headers: Map<String, String> = mapOf("Authorization" to "Bearer test-openai-key")) : AuthorizationProvider {
        val invalidated = CopyOnWriteArrayList<ProviderId>()
        override suspend fun headers(provider: ProviderId) = headers
        override fun invalidate(provider: ProviderId) {
            invalidated += provider
        }
    }

    private val pauses = CopyOnWriteArrayList<Long>()

    private fun engine(auth: AuthorizationProvider = RecordingAuth(), model: String = "gpt-6-astra") =
        OpenAiAgentEngine(auth, client, model, apiBaseUrl = server.url("/v1").toString().trimEnd('/'), pauseMs = { pauses += it })

    @After
    fun tearDown() = server.shutdown()

    private fun sse(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun status(code: Int, body: String = "") = MockResponse().setResponseCode(code).setBody(body)

    private val tool = ToolDefinition("skin_list", "Lista as skins.", JSONObject("""{"type":"object","properties":{},"additionalProperties":false}"""))

    private fun events(
        messages: List<AgentMessage> = listOf(AgentMessage.User("olá")),
        tools: List<ToolDefinition> = listOf(tool),
        e: AgentEngine = engine(),
        maxTokens: Int = 2048,
    ): List<AgentEvent> = runBlocking { withTimeout(10_000) { e.chat(messages, tools, "SISTEMA", maxTokens).toList() } }

    private fun lastBody(): JSONObject = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())

    // --- sucesso ---

    @Test
    fun `text only - sends the documented request and streams the answer`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))

        val result = events()
        val recorded = server.takeRequest()

        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/responses")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-openai-key")
        assertThat(recorded.getHeader("Accept")).isEqualTo("text/event-stream")
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json")
        val body = JSONObject(recorded.body.readUtf8())
        assertThat(body.getString("model")).isEqualTo("gpt-6-astra")
        assertThat(body.getString("instructions")).isEqualTo("SISTEMA")
        assertThat(body.getBoolean("store")).isFalse()
        assertThat(body.getBoolean("stream")).isTrue()
        // Sem teto de saída: cada modelo tem o seu limite e pedir mais é HTTP 400.
        assertThat(body.has("max_output_tokens")).isFalse()
        val input = body.getJSONArray("input")
        assertThat(input.length()).isEqualTo(1)
        assertThat(input.getJSONObject(0).getString("role")).isEqualTo("user")
        assertThat(input.getJSONObject(0).getString("content")).isEqualTo("olá")
        val function = body.getJSONArray("tools").getJSONObject(0)
        assertThat(function.getString("type")).isEqualTo("function")
        assertThat(function.getString("name")).isEqualTo("skin_list")
        assertThat(function.getBoolean("strict")).isFalse()
        assertThat(function.getJSONObject("parameters").getString("type")).isEqualTo("object")

        assertThat(result.filterIsInstance<AgentEvent.TextDelta>().map { it.text }).containsExactly("Olá", "!").inOrder()
        assertThat(result.last()).isEqualTo(AgentEvent.Done(AgentStopReason.END_TURN, TokenUsage(25, 15), null))
    }

    @Test
    fun `one function call - the text, then the whole call with the arguments joined, then Done with tool use`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.ONE_FUNCTION_CALL))
        val result = events()
        assertThat(result.map { it::class.simpleName }.distinct()).containsExactly("TextDelta", "ToolCall", "Done").inOrder()
        val call = result.filterIsInstance<AgentEvent.ToolCall>().single()
        assertThat(call.id).isEqualTo("call_1234xyz") // o call_id, não o id do item
        assertThat(call.name).isEqualTo("get_weather")
        assertThat(JSONObject(call.argsJson).getString("location")).isEqualTo("Paris, France")
        assertThat(result.last()).isEqualTo(AgentEvent.Done(AgentStopReason.TOOL_USE, TokenUsage(75, 40), null))
        assertThat(result.filterIsInstance<AgentEvent.ToolCall>()).hasSize(1) // o completed não repete a que já saiu
    }

    @Test
    fun `several function calls in one turn, one of them with the arguments only on the item done`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TWO_FUNCTION_CALLS))
        val calls = events().filterIsInstance<AgentEvent.ToolCall>()
        assertThat(calls.map { it.id to it.name }).containsExactly("call_AAA" to "skin_list", "call_BBB" to "app_status").inOrder()
        assertThat(calls[0].argsJson).isEqualTo("{}")
        assertThat(JSONObject(calls[1].argsJson).getBoolean("detail")).isTrue()
    }

    @Test
    fun `a call that only shows up in the completed list is still delivered`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.CALL_ONLY_IN_COMPLETED))
        val result = events()
        assertThat(result.filterIsInstance<AgentEvent.ToolCall>().single().id).isEqualTo("call_late")
        assertThat((result.last() as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.TOOL_USE)
    }

    @Test
    fun `the tool turn round trip - reasoning goes back untouched, then the result as function_call_output`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.REASONING_THEN_CALL))
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))
        val first = events()
        val done = first.last() as AgentEvent.Done
        val call = first.filterIsInstance<AgentEvent.ToolCall>().single()
        assertThat(done.providerState).isNotNull()
        lastBody()

        val history = listOf(
            AgentMessage.User("quais skins?"),
            AgentMessage.Assistant("", listOf(AgentToolCall(call.id, call.name, call.argsJson)), done.providerState),
            AgentMessage.ToolResult(call.id, "2 skins: neon, aero"),
        )
        events(history)
        val input = lastBody().getJSONArray("input")
        assertThat((0 until input.length()).map { input.getJSONObject(it).optString("type").ifEmpty { input.getJSONObject(it).optString("role") } })
            .containsExactly("user", "reasoning", "function_call", "function_call_output").inOrder()
        assertThat(input.getJSONObject(1).getString("encrypted_content")).isEqualTo(OpenAiAgentSseFixtures.ENCRYPTED)
        assertThat(input.getJSONObject(2).getString("call_id")).isEqualTo("call_think")
        assertThat(input.getJSONObject(3).getString("call_id")).isEqualTo("call_think")
        assertThat(input.getJSONObject(3).getString("output")).isEqualTo("2 skins: neon, aero")
    }

    @Test
    fun `an old turn preserves the provider state including reasoning and function call items`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))
        val history = listOf(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("call_old", "skin_list", "{}")), ProviderState("openai", """[{"type":"reasoning","id":"rs_old","encrypted_content":"x"},{"type":"function_call","id":"fc_old","call_id":"call_old","name":"skin_list","arguments":"{}"}]""")),
            AgentMessage.ToolResult("call_old", "ok"),
            AgentMessage.Assistant("feito"),
            AgentMessage.User("e agora?"),
        )
        events(history)
        val body = lastBody().getJSONArray("input").toString()
        assertThat(body).contains("rs_old")
        assertThat(body).contains("fc_old")
        assertThat(body).contains("call_old")
    }

    @Test
    fun `an old turn without provider state is rebuilt from text and calls`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))
        val history = listOf(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("call_old", "skin_list", "{}")), providerState = null),
            AgentMessage.ToolResult("call_old", "ok"),
            AgentMessage.Assistant("feito"),
            AgentMessage.User("e agora?"),
        )
        events(history)
        val body = lastBody().getJSONArray("input").toString()
        assertThat(body).doesNotContain("reasoning")
        assertThat(body).contains("call_old")
    }

    @Test
    fun `the model name goes through as the user chose it`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))
        events(e = engine(model = "gpt-5.6-terra"))
        assertThat(lastBody().getString("model")).isEqualTo("gpt-5.6-terra")
    }

    // --- fim da resposta ---

    @Test
    fun `incomplete by max_output_tokens keeps the text and finishes as truncated`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.INCOMPLETE_MAX_TOKENS))
        val result = events()
        assertThat(result.first()).isEqualTo(AgentEvent.TextDelta("Vou criar uma skin"))
        assertThat(result.last()).isEqualTo(AgentEvent.Done(AgentStopReason.TRUNCATED, TokenUsage(50, 16384), null))
    }

    @Test
    fun `incomplete by content_filter is a refusal`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.INCOMPLETE_CONTENT_FILTER))
        assertThat((events().last() as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.REFUSAL)
    }

    @Test
    fun `a failed event after some text keeps the text and ends with a typed error that does not repeat the server message`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.FAILED_RATE_LIMIT))
        val result = events()
        assertThat(result.first()).isEqualTo(AgentEvent.TextDelta("Vou"))
        val error = result.last() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(error.message).contains("rate_limit_exceeded")
        assertThat(error.message).doesNotContain("privada")
        assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an error event in the stream is a network error for server_error`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.ERROR_EVENT))
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(error.message).doesNotContain("privada")
    }

    @Test
    fun `a stream that ends without response completed is an error, never a finished turn`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.CUT_MID_CALL))
        val result = events()
        assertThat(result.filterIsInstance<AgentEvent.ToolCall>()).isEmpty()
        assertThat((result.last() as AgentEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
    }

    @Test
    fun `a connection dropped during the body is a network error`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val result = events()
        assertThat((result.last() as AgentEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
    }

    @Test
    fun `an event without the blank line after it still counts`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY.trimEnd('\n')))
        assertThat(events().last()).isInstanceOf(AgentEvent.Done::class.java)
    }

    @Test
    fun `an invalid data line in the stream is skipped, not a crash`() {
        server.enqueue(sse("event: response.output_text.delta\ndata: {isto não é json\n\n" + OpenAiAgentSseFixtures.TEXT_ONLY))
        assertThat(events().last()).isInstanceOf(AgentEvent.Done::class.java)
    }

    // --- erros HTTP ---

    @Test
    fun `401 is unauthorized, is not retried and asks the auth side to invalidate`() {
        val marker = "openai-private-body-marker"
        server.enqueue(status(401, """{"error":{"message":"$marker","type":"invalid_request_error","code":"invalid_api_key"}}"""))
        val auth = RecordingAuth()
        val result = events(e = engine(auth))
        val error = result.single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(error.message).contains("invalid_api_key")
        assertThat(result.toString()).doesNotContain(marker)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(auth.invalidated).containsExactly(ProviderIds.OPENAI)
    }

    @Test
    fun `429 is a rate limit and is not retried`() {
        server.enqueue(status(429, """{"error":{"message":"slow down","type":"insufficient_quota","code":"insufficient_quota"}}"""))
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(error.message).doesNotContain("slow down")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `400 is an engine error and is not retried`() {
        server.enqueue(status(400, """{"error":{"message":"bad","type":"invalid_request_error","code":"unsupported_parameter"}}"""))
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `503 is retried with a growing pause, then reported as a network error`() {
        repeat(3) { server.enqueue(status(503)) }
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(pauses).containsExactly(200L, 400L).inOrder()
    }

    @Test
    fun `a 5xx followed by success recovers`() {
        server.enqueue(status(500))
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))
        assertThat(events().last()).isInstanceOf(AgentEvent.Done::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `an authentication failure before the request maps to the same error kinds as the text engine`() {
        fun kindFor(kind: AuthErrorKind): ReasoningError {
            val auth = object : AuthorizationProvider {
                override suspend fun headers(provider: ProviderId): Map<String, String> = throw AuthException(kind, "x")
            }
            return (events(e = engine(auth)).single() as AgentEvent.Error).kind
        }
        assertThat(kindFor(AuthErrorKind.NOT_CONNECTED)).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(kindFor(AuthErrorKind.NETWORK)).isEqualTo(ReasoningError.NETWORK)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a credential the http layer cannot send is an unauthorized error, not an exception`() {
        val auth = RecordingAuth(mapOf("Authorization" to "Bearer chave\ncom quebra de linha"))
        assertThat((events(e = engine(auth)).single() as AgentEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)
    }

    @Test
    fun `no connection is a network error`() {
        val unreachable = OpenAiAgentEngine(RecordingAuth(), client, "gpt-6-astra", apiBaseUrl = "http://127.0.0.1:1/v1", pauseMs = { })
        val error = events(e = unreachable).single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(error.message).isEqualTo("sem conexão com a OpenAI")
    }

    // --- cancelamento e fluxo ---

    @Test
    fun `cancelling the collection cancels the http call`() = runBlocking {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY).setBodyDelay(3, TimeUnit.SECONDS))
        val e = engine()
        val job = launch { e.chat(listOf(AgentMessage.User("x")), emptyList(), "S", 100).toList() }
        withTimeout(5_000) { while (server.requestCount == 0) kotlinx.coroutines.delay(10) }
        job.cancel()
        withTimeout(1_500) { job.join() } // sem o call.cancel() isto esperaria os 3 s do atraso do corpo
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `the first event arrives before the stream ends`() = runBlocking {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))
        val first = withTimeout(5_000) { engine().chat(listOf(AgentMessage.User("x")), emptyList(), "S", 100).first() }
        assertThat(first).isEqualTo(AgentEvent.TextDelta("Olá"))
    }

    @Test
    fun `without tools and with a tool history, the request carries no tool items`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))
        val history = listOf(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("call_1", "skin_list", "{}"))),
            AgentMessage.ToolResult("call_1", "ok"),
            AgentMessage.User("e agora?"),
        )
        events(history, tools = emptyList())
        val body = lastBody()
        assertThat(body.has("tools")).isFalse()
        assertThat(body.getJSONArray("input").toString()).doesNotContain("function_call")
    }

    @Test
    fun `the request never carries the credential in the body`() {
        server.enqueue(sse(OpenAiAgentSseFixtures.TEXT_ONLY))
        events()
        assertThat(lastBody().toString()).doesNotContain("test-openai-key")
    }
}
