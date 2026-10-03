package com.keyflow.reasoning.anthropic

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentToolCall
import com.keyflow.agent.TokenUsage
import com.keyflow.agent.ToolDefinition
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.ProviderId
import com.keyflow.auth.ProviderIds
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningRequest
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
 * AGT-02: o `AnthropicAgentEngine` contra um servidor falso, com SSE no formato da documentação oficial (ver
 * [AnthropicSseFixtures]). Cobre texto, uma e várias `tool_use`, argumentos em vários `input_json_delta`, o raciocínio assinado
 * indo e voltando, HTTP 401/429/5xx/529 e o corte no meio do fluxo.
 */
class AnthropicAgentEngineTest {

    private val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    private class RecordingAuth(private val headers: Map<String, String> = mapOf("x-api-key" to "test-anthropic-key", "anthropic-version" to "2023-06-01")) : AuthorizationProvider {
        val invalidated = CopyOnWriteArrayList<ProviderId>()
        override suspend fun headers(provider: ProviderId) = headers
        override fun invalidate(provider: ProviderId) {
            invalidated += provider
        }
    }

    private val pauses = CopyOnWriteArrayList<Long>()

    private fun engine(
        auth: AuthorizationProvider = RecordingAuth(),
        model: String = "claude-opus-5-5",
        eager: Boolean = false,
    ) = AnthropicAgentEngine(auth, client, model, apiBaseUrl = server.url("/v1").toString().trimEnd('/'), pauseMs = { pauses += it }, eagerInputStreaming = eager)

    @After
    fun tearDown() = server.shutdown()

    private fun sse(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun status(code: Int, body: String = "") = MockResponse().setResponseCode(code).setBody(body)

    private val tool = ToolDefinition("skin_list", "Lista as skins.", JSONObject("""{"type":"object","properties":{}}"""))

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
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))

        val result = events()
        val recorded = server.takeRequest()

        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1/messages")
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("test-anthropic-key")
        assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01")
        assertThat(recorded.getHeader("Accept")).isEqualTo("text/event-stream")
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json")
        val body = JSONObject(recorded.body.readUtf8())
        assertThat(body.getString("model")).isEqualTo("claude-opus-5-5")
        assertThat(body.getInt("max_tokens")).isEqualTo(2048)
        assertThat(body.getBoolean("stream")).isTrue()
        assertThat(body.getString("system")).isEqualTo("SISTEMA")
        assertThat(body.getJSONArray("tools").getJSONObject(0).getString("name")).isEqualTo("skin_list")
        assertThat(body.getJSONArray("messages").getJSONObject(0).getString("role")).isEqualTo("user")

        assertThat(result.filterIsInstance<AgentEvent.TextDelta>().map { it.text }).containsExactly("Hello", "!").inOrder()
        assertThat(result.last()).isEqualTo(AgentEvent.Done(AgentStopReason.END_TURN, TokenUsage(25, 15), null))
    }

    @Test
    fun `one tool use - the text, then the whole call, then Done with tool_use`() {
        server.enqueue(sse(AnthropicSseFixtures.ONE_TOOL_USE))
        val result = events()
        assertThat(result.map { it::class.simpleName }.distinct()).containsExactly("TextDelta", "ToolCall", "Done").inOrder()
        val call = result.filterIsInstance<AgentEvent.ToolCall>().single()
        assertThat(call.name).isEqualTo("get_weather")
        assertThat(JSONObject(call.argsJson).getString("location")).isEqualTo("San Francisco, CA")
        assertThat((result.last() as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.TOOL_USE)
    }

    @Test
    fun `several tool uses in one turn`() {
        server.enqueue(sse(AnthropicSseFixtures.TWO_TOOL_USES))
        val calls = events().filterIsInstance<AgentEvent.ToolCall>()
        assertThat(calls.map { it.id to it.name }).containsExactly("toolu_01AAA" to "skin_list", "toolu_01BBB" to "app_status").inOrder()
    }

    @Test
    fun `a tool without arguments`() {
        server.enqueue(sse(AnthropicSseFixtures.TOOL_USE_NO_ARGS))
        assertThat(events().filterIsInstance<AgentEvent.ToolCall>().single().argsJson).isEqualTo("{}")
    }

    @Test
    fun `an event without the blank line after it still counts`() {
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY.trimEnd('\n')))
        assertThat(events().last()).isInstanceOf(AgentEvent.Done::class.java)
    }

    @Test
    fun `the tool turn round trip - the signed thinking goes back untouched and the results go in one user message`() {
        server.enqueue(sse(AnthropicSseFixtures.THINKING_THEN_TOOL_USE))
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
        val first = events()
        val done = first.last() as AgentEvent.Done
        val call = first.filterIsInstance<AgentEvent.ToolCall>().single()
        lastBody()

        val history = listOf(
            AgentMessage.User("quais skins?"),
            AgentMessage.Assistant("Vou consultar.", listOf(AgentToolCall(call.id, call.name, call.argsJson)), done.providerState),
            AgentMessage.ToolResult(call.id, "2 skins: neon, aero"),
        )
        events(history)
        val second = lastBody().getJSONArray("messages")
        assertThat(second.length()).isEqualTo(3)
        val assistant = second.getJSONObject(1).getJSONArray("content")
        assertThat((0 until assistant.length()).map { assistant.getJSONObject(it).getString("type") }).containsExactly("thinking", "text", "tool_use").inOrder()
        assertThat(assistant.getJSONObject(0).getString("signature")).isEqualTo(AnthropicSseFixtures.SIGNATURE)
        assertThat(assistant.getJSONObject(2).getString("id")).isEqualTo("toolu_01Think")
        val result = second.getJSONObject(2)
        assertThat(result.getString("role")).isEqualTo("user")
        val block = result.getJSONArray("content").getJSONObject(0)
        assertThat(block.getString("type")).isEqualTo("tool_result")
        assertThat(block.getString("tool_use_id")).isEqualTo("toolu_01Think")
        assertThat(block.getString("content")).isEqualTo("2 skins: neon, aero")
    }

    @Test
    fun `eager input streaming is on the tools only when the engine was built with it`() {
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
        events(e = engine(eager = true))
        assertThat(lastBody().getJSONArray("tools").getJSONObject(0).getBoolean("eager_input_streaming")).isTrue()
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
        events()
        assertThat(lastBody().getJSONArray("tools").getJSONObject(0).has("eager_input_streaming")).isFalse()
    }

    @Test
    fun `the login credential sends its headers and the Claude Code identity in the system prompt`() {
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
        val auth = RecordingAuth(mapOf("Authorization" to "Bearer test-token", "anthropic-version" to "2023-06-01", "anthropic-beta" to "oauth-2025-04-20,claude-code-20250219"))
        events(e = engine(auth))
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-token")
        assertThat(recorded.getHeader("anthropic-beta")).isEqualTo("oauth-2025-04-20,claude-code-20250219")
        assertThat(JSONObject(recorded.body.readUtf8()).getString("system")).startsWith("You are Claude Code, Anthropic's official CLI for Claude.")
    }

    @Test
    fun `the model aliases resolve like in the text engine`() {
        val aliases = listOf("claude-3-7-sonnet", "claude-3-5-sonnet", "claude-3-5-haiku", "claude-3-opus", "claude-opus-5-5")
        for (alias in aliases) {
            server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
            events(e = engine(model = alias))
            val agentModel = lastBody().getString("model")

            server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
            val textEngine = AnthropicReasoningEngine(RecordingAuth(), client, alias, apiBaseUrl = server.url("/v1").toString().trimEnd('/'))
            runBlocking { withTimeout(10_000) { textEngine.generate(ReasoningRequest(user = "oi")).toList() } }
            assertThat(agentModel).isEqualTo(lastBody().getString("model"))
        }
    }

    // --- erros HTTP ---

    @Test
    fun `401 is unauthorized, is not retried and asks the auth side to invalidate`() {
        server.enqueue(status(401, AnthropicSseFixtures.AUTH_HTTP_BODY))
        val auth = RecordingAuth()
        val error = events(e = engine(auth)).single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(error.message).contains("401")
        assertThat(error.message).contains("invalid x-api-key")
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(auth.invalidated).containsExactly(ProviderIds.ANTHROPIC)
    }

    @Test
    fun `403 is unauthorized too`() {
        server.enqueue(status(403, """{"type":"error","error":{"type":"permission_error","message":"nope"}}"""))
        assertThat((events().single() as AgentEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)
    }

    @Test
    fun `429 is a rate limit and is not retried`() {
        server.enqueue(status(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}"""))
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(error.message).contains("slow down")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `400 is an engine error and is not retried`() {
        server.enqueue(status(400, """{"type":"error","error":{"type":"invalid_request_error","message":"messages.1: bad"}}"""))
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `529 overloaded is retried with a growing pause, then reported as a network error`() {
        repeat(3) { server.enqueue(status(529, AnthropicSseFixtures.OVERLOADED_HTTP_BODY)) }
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(error.message).contains("529")
        assertThat(error.message).contains("Overloaded")
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(pauses).containsExactly(200L, 400L).inOrder()
    }

    @Test
    fun `a 5xx followed by success recovers`() {
        server.enqueue(status(503))
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
        val result = events()
        assertThat(result.last()).isInstanceOf(AgentEvent.Done::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a body that is not json still gives a typed error`() {
        server.enqueue(status(404, "<html>nope</html>"))
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(error.message).isEqualTo("Anthropic HTTP 404")
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
        assertThat(kindFor(AuthErrorKind.NEEDS_RECONNECT)).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(kindFor(AuthErrorKind.NETWORK)).isEqualTo(ReasoningError.NETWORK)
        assertThat(kindFor(AuthErrorKind.TIMEOUT)).isEqualTo(ReasoningError.NETWORK)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a credential the http layer cannot send is an unauthorized error, not an exception`() {
        val auth = RecordingAuth(mapOf("x-api-key" to "chave\ncom quebra de linha"))
        assertThat((events(e = engine(auth)).single() as AgentEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)
    }

    // --- erro e corte no fluxo ---

    @Test
    fun `an overloaded error event after some text keeps the text and ends with a network error`() {
        val partial = AnthropicSseFixtures.CUT_MID_STREAM + AnthropicSseFixtures.OVERLOADED_EVENT
        server.enqueue(sse(partial))
        val result = events()
        assertThat(result.first()).isEqualTo(AgentEvent.TextDelta("Vou criar uma skin"))
        val error = result.last() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(error.message).contains("overloaded_error")
        assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
        assertThat(server.requestCount).isEqualTo(1) // erro no fluxo não repete: já chegou texto
    }

    @Test
    fun `a stream that ends without message_stop is an error, never a finished turn`() {
        server.enqueue(sse(AnthropicSseFixtures.CUT_MID_STREAM))
        val result = events()
        assertThat(result.first()).isEqualTo(AgentEvent.TextDelta("Vou criar uma skin"))
        assertThat((result.last() as AgentEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
    }

    @Test
    fun `a tool use cut in the middle never reaches the loop as a call`() {
        val cut = AnthropicSseFixtures.sse(
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"skin_list","input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
        )
        server.enqueue(sse(cut))
        val result = events()
        assertThat(result.filterIsInstance<AgentEvent.ToolCall>()).isEmpty()
        assertThat((result.last() as AgentEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
    }

    @Test
    fun `a connection dropped during the body is a network error`() {
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val result = events()
        assertThat((result.last() as AgentEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
    }

    @Test
    fun `no connection is a network error`() {
        val unreachable = AnthropicAgentEngine(RecordingAuth(), client, "claude-opus-5-5", apiBaseUrl = "http://127.0.0.1:1/v1", pauseMs = { })
        val error = events(e = unreachable).single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(error.message).isEqualTo("sem conexão com a Anthropic")
    }

    @Test
    fun `cancelling the collection cancels the http call`() = runBlocking {
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY).setBodyDelay(3, TimeUnit.SECONDS))
        val e = engine()
        val job = launch { e.chat(listOf(AgentMessage.User("x")), emptyList(), "S", 100).toList() }
        withTimeout(5_000) { while (server.requestCount == 0) kotlinx.coroutines.delay(10) }
        job.cancel()
        withTimeout(1_500) { job.join() } // sem o call.cancel() isto esperaria os 3 s do atraso do corpo
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `the first event arrives before the stream ends`() = runBlocking {
        // O motor emite conforme lê: o primeiro delta chega sem esperar o resto (o Flow é de verdade em fluxo).
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
        val first = withTimeout(5_000) { engine().chat(listOf(AgentMessage.User("x")), emptyList(), "S", 100).first() }
        assertThat(first).isEqualTo(AgentEvent.TextDelta("Hello"))
    }

    @Test
    fun `without tools and with a tool history, the request carries no tool blocks`() {
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
        val history = listOf(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("toolu_1", "skin_list", "{}"))),
            AgentMessage.ToolResult("toolu_1", "ok"),
            AgentMessage.User("e agora?"),
        )
        events(history, tools = emptyList())
        val body = lastBody()
        assertThat(body.has("tools")).isFalse()
        assertThat(body.getJSONArray("messages").toString()).doesNotContain("tool_use")
        assertThat(body.getJSONArray("messages").toString()).doesNotContain("tool_result")
    }

    @Test
    fun `the request never carries the credential in the body`() {
        server.enqueue(sse(AnthropicSseFixtures.TEXT_ONLY))
        events()
        assertThat(lastBody().toString()).doesNotContain("test-anthropic-key")
    }

    @Test
    fun `done carries nothing to replay when the model did not think`() {
        server.enqueue(sse(AnthropicSseFixtures.ONE_TOOL_USE))
        assertThat((events().last() as AgentEvent.Done).providerState).isNull()
    }

    @Test
    fun `an invalid data line in the stream is skipped, not a crash`() {
        server.enqueue(sse("event: content_block_delta\ndata: {isto não é json\n\n" + AnthropicSseFixtures.TEXT_ONLY))
        val result = events()
        assertThat(result.last()).isInstanceOf(AgentEvent.Done::class.java)
    }
}
