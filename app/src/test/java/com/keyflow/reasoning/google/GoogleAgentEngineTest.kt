package com.keyflow.reasoning.google

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
 * AGT-02/T7: o `GoogleAgentEngine` contra um servidor falso, com SSE no formato do documento de descoberta e da documentação (ver
 * [GoogleAgentSseFixtures]). Cobre texto, uma e várias `functionCall`, a assinatura de pensamento indo e voltando, os `finishReason`
 * (parada, limite, recusa, chamada malformada), o prompt bloqueado, HTTP 401/403/429/5xx, o corte e o erro no meio do fluxo. Credenciais
 * FALSAS; nenhuma chamada ao Google de verdade.
 */
class GoogleAgentEngineTest {

    private val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    private class RecordingAuth(private val headers: Map<String, String> = mapOf("x-goog-api-key" to "test-google-key")) : AuthorizationProvider {
        val invalidated = CopyOnWriteArrayList<ProviderId>()
        override suspend fun headers(provider: ProviderId) = headers
        override fun invalidate(provider: ProviderId) {
            invalidated += provider
        }
    }

    private val pauses = CopyOnWriteArrayList<Long>()

    private fun engine(auth: AuthorizationProvider = RecordingAuth(), model: String = "gemini-3.8-flash") =
        GoogleAgentEngine(auth, client, model, apiBaseUrl = server.url("/v1beta").toString().trimEnd('/'), pauseMs = { pauses += it })

    @After
    fun tearDown() = server.shutdown()

    private fun sse(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun status(code: Int, body: String = "") = MockResponse().setResponseCode(code).setBody(body)

    private val tool = ToolDefinition(
        "skin_install",
        "Instala a skin.",
        JSONObject("""{"type":"object","properties":{"files":{"type":"object","additionalProperties":{"type":"string","maxLength":100}},"reset":{"type":"boolean","default":false}},"additionalProperties":false}"""),
    )

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
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))

        val result = events()
        val recorded = server.takeRequest()

        assertThat(recorded.method).isEqualTo("POST")
        assertThat(recorded.path).isEqualTo("/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse")
        assertThat(recorded.getHeader("x-goog-api-key")).isEqualTo("test-google-key")
        assertThat(recorded.getHeader("Accept")).isEqualTo("text/event-stream")
        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json")
        val body = JSONObject(recorded.body.readUtf8())
        assertThat(body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")).isEqualTo("SISTEMA")
        // Sem teto de saída: cada modelo tem o seu limite e pedir mais é HTTP 400.
        assertThat(body.has("generationConfig")).isFalse()
        val contents = body.getJSONArray("contents")
        assertThat(contents.length()).isEqualTo(1)
        assertThat(contents.getJSONObject(0).getString("role")).isEqualTo("user")
        assertThat(contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")).isEqualTo("olá")
        val declaration = body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertThat(declaration.getString("name")).isEqualTo("skin_install")
        assertThat(declaration.getString("description")).isEqualTo("Instala a skin.")
        val schema = declaration.getJSONObject("parametersJsonSchema")
        assertThat(schema.getString("type")).isEqualTo("object")
        assertThat(schema.getJSONObject("properties").getJSONObject("files").getJSONObject("additionalProperties").getString("type")).isEqualTo("string")
        // `default` é só documentação e não está na lista da Google: sai.
        assertThat(schema.getJSONObject("properties").getJSONObject("reset").has("default")).isFalse()

        assertThat(result.filterIsInstance<AgentEvent.TextDelta>().map { it.text }).containsExactly("Olá", "!").inOrder()
        assertThat(result.last()).isEqualTo(AgentEvent.Done(AgentStopReason.END_TURN, TokenUsage(25, 15), null))
    }

    @Test
    fun `a function call arrives whole - the text, then the call, then Done with tool use even though the api says STOP`() {
        server.enqueue(sse(GoogleAgentSseFixtures.ONE_FUNCTION_CALL))
        val result = events()
        assertThat(result.map { it::class.simpleName }.distinct()).containsExactly("TextDelta", "ToolCall", "Done").inOrder()
        val call = result.filterIsInstance<AgentEvent.ToolCall>().single()
        assertThat(call.name).isEqualTo("get_weather")
        assertThat(JSONObject(call.argsJson).getString("location")).isEqualTo("Boston, MA")
        assertThat(GoogleAgentWire.isSynthetic(call.id)).isTrue() // a API não numerou: o app inventou um id
        assertThat(result.last()).isEqualTo(AgentEvent.Done(AgentStopReason.TOOL_USE, TokenUsage(75, 20), null))
    }

    @Test
    fun `the documented streaming example - a call chunk with no finish reason, then the finish chunk`() {
        server.enqueue(sse(GoogleAgentSseFixtures.DOC_FUNCTION_CALL))
        val result = events()
        assertThat(result.filterIsInstance<AgentEvent.ToolCall>().single().name).isEqualTo("get_weather")
        assertThat((result.last() as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.TOOL_USE)
    }

    @Test
    fun `several calls in one chunk - the id of the api is kept, the missing one is invented, each unique`() {
        server.enqueue(sse(GoogleAgentSseFixtures.TWO_FUNCTION_CALLS))
        val calls = events().filterIsInstance<AgentEvent.ToolCall>()
        assertThat(calls.map { it.name }).containsExactly("skin_list", "app_status").inOrder()
        assertThat(GoogleAgentWire.isSynthetic(calls[0].id)).isTrue()
        assertThat(calls[1].id).isEqualTo("api-id-2")
        assertThat(JSONObject(calls[1].argsJson).getBoolean("detail")).isTrue()
    }

    @Test
    fun `invented ids never repeat across turns`() {
        server.enqueue(sse(GoogleAgentSseFixtures.NO_ARGS_CALL))
        server.enqueue(sse(GoogleAgentSseFixtures.NO_ARGS_CALL))
        val a = events().filterIsInstance<AgentEvent.ToolCall>().single()
        val b = events().filterIsInstance<AgentEvent.ToolCall>().single()
        assertThat(a.id).isNotEqualTo(b.id)
        assertThat(a.argsJson).isEqualTo("{}")
    }

    @Test
    fun `the tool turn round trip - the signed parts go back untouched, the result is a functionResponse in a user content`() {
        server.enqueue(sse(GoogleAgentSseFixtures.SIGNED_CALL))
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        val first = events()
        val done = first.last() as AgentEvent.Done
        val call = first.filterIsInstance<AgentEvent.ToolCall>().single()
        assertThat(call.id).isEqualTo("call-sig-1")
        assertThat(done.usage).isEqualTo(TokenUsage(80, 266)) // a saída soma os tokens de pensamento
        assertThat(done.providerState).isNotNull()
        lastBody()

        val history = listOf(
            AgentMessage.User("quais skins?"),
            AgentMessage.Assistant("Vou olhar.", listOf(AgentToolCall(call.id, call.name, call.argsJson)), done.providerState),
            AgentMessage.ToolResult(call.id, "2 skins: neon, aero"),
        )
        events(history)
        val contents = lastBody().getJSONArray("contents")
        assertThat(contents.length()).isEqualTo(3)
        val model = contents.getJSONObject(1)
        assertThat(model.getString("role")).isEqualTo("model")
        val parts = model.getJSONArray("parts")
        assertThat(parts.length()).isEqualTo(2)
        assertThat(parts.getJSONObject(0).getString("text")).isEqualTo("Vou olhar.")
        assertThat(parts.getJSONObject(0).getString("thoughtSignature")).isEqualTo(GoogleAgentSseFixtures.SIGNATURE)
        assertThat(parts.getJSONObject(1).getString("thoughtSignature")).isEqualTo(GoogleAgentSseFixtures.SIGNATURE + "-fc")
        assertThat(parts.getJSONObject(1).getJSONObject("functionCall").getString("id")).isEqualTo("call-sig-1")
        val result = contents.getJSONObject(2)
        assertThat(result.getString("role")).isEqualTo("user")
        val response = result.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertThat(response.getString("name")).isEqualTo("skin_list")
        assertThat(response.getString("id")).isEqualTo("call-sig-1")
        assertThat(response.getJSONObject("response").getString("result")).isEqualTo("2 skins: neon, aero")
    }

    @Test
    fun `an old turn preserves thoughtSignature across subsequent conversation turns`() {
        server.enqueue(sse(GoogleAgentSseFixtures.SIGNED_CALL))
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        val first = events()
        val done = first.last() as AgentEvent.Done
        val call = first.filterIsInstance<AgentEvent.ToolCall>().single()
        lastBody()

        // Histórico com uma volta subsequente: User -> Assistant (com thoughtSignature) -> ToolResult -> Assistant -> User (nova pergunta)
        val history = listOf(
            AgentMessage.User("quais skins?"),
            AgentMessage.Assistant("Vou olhar.", listOf(AgentToolCall(call.id, call.name, call.argsJson)), done.providerState),
            AgentMessage.ToolResult(call.id, "2 skins: neon, aero"),
            AgentMessage.Assistant("Aqui estão as skins neon e aero."),
            AgentMessage.User("instale a neon"),
        )
        events(history)
        val contents = lastBody().getJSONArray("contents")
        // O assistant da volta 1 (índice 1) deve preservar as partes assinadas intactas
        val oldModel = contents.getJSONObject(1)
        assertThat(oldModel.getString("role")).isEqualTo("model")
        val parts = oldModel.getJSONArray("parts")
        assertThat(parts.getJSONObject(0).getString("thoughtSignature")).isEqualTo(GoogleAgentSseFixtures.SIGNATURE)
        assertThat(parts.getJSONObject(1).getString("thoughtSignature")).isEqualTo(GoogleAgentSseFixtures.SIGNATURE + "-fc")
    }

    @Test
    fun `a text only turn with thoughtSignature preserves provider state and replays with signature`() {
        server.enqueue(sse(GoogleAgentSseFixtures.SIGNED_TEXT_ONLY))
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        val first = events()
        val done = first.last() as AgentEvent.Done
        val texts = first.filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        assertThat(texts).containsExactly("Aqui está a resposta.")
        assertThat(done.providerState).isNotNull()
        lastBody()

        val history = listOf(
            AgentMessage.User("como vai?"),
            AgentMessage.Assistant("Aqui está a resposta.", providerState = done.providerState),
            AgentMessage.User("e agora?"),
        )
        events(history)
        val contents = lastBody().getJSONArray("contents")
        val model = contents.getJSONObject(1)
        val parts = model.getJSONArray("parts")
        assertThat(parts.length()).isEqualTo(2)
        assertThat(parts.getJSONObject(0).getString("thoughtSignature")).isEqualTo("CiQBjz1rX-signature-blob-thought-only")
    }

    @Test
    fun `a tool error goes back under the error key, and an invented id is not sent to the api`() {
        server.enqueue(sse(GoogleAgentSseFixtures.NO_ARGS_CALL))
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        val call = events().filterIsInstance<AgentEvent.ToolCall>().single()
        lastBody()
        events(listOf(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall(call.id, call.name, call.argsJson))), AgentMessage.ToolResult(call.id, "deu ruim", isError = true)))
        val contents = lastBody().getJSONArray("contents")
        val functionCall = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
        assertThat(functionCall.has("id")).isFalse()
        assertThat(functionCall.getString("name")).isEqualTo("skin_list")
        assertThat(functionCall.getJSONObject("args").length()).isEqualTo(0)
        val response = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertThat(response.has("id")).isFalse()
        assertThat(response.getJSONObject("response").getString("error")).isEqualTo("deu ruim")
        assertThat(response.getJSONObject("response").has("result")).isFalse()
    }

    @Test
    fun `a thought summary is not shown as the answer`() {
        server.enqueue(sse(GoogleAgentSseFixtures.THOUGHT_SUMMARY))
        val texts = events().filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        assertThat(texts).containsExactly("Resposta")
    }

    @Test
    fun `the model name goes through as the user chose it`() {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        events(e = engine(model = "gemini-2.5-flash"))
        assertThat(server.takeRequest().path).isEqualTo("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse")
    }

    @Test
    fun `the model name strips models prefix and escapes path`() {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        events(e = engine(model = "models/gemini-2.5-flash"))
        assertThat(server.takeRequest().path).isEqualTo("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse")
    }

    @Test
    fun `an invalid model name is rejected before calling the api`() {
        for (bad in listOf("models/", "models/models/gemini", "gemini/pro", "gemini:flash", "models/gemini:pro", "a/b", "a:b", "")) {
            val result = events(e = engine(model = bad))
            assertThat(result).hasSize(1)
            val error = result.single() as AgentEvent.Error
            assertThat(error.kind).isEqualTo(ReasoningError.ENGINE)
            assertThat(error.message).contains("Google Gemini: nome de modelo inválido")
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun `a login credential sends the bearer header and nothing else changes`() {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        events(e = engine(RecordingAuth(mapOf("Authorization" to "Bearer test-token"))))
        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-token")
        assertThat(recorded.getHeader("x-goog-api-key")).isNull()
    }

    // --- fim da resposta ---

    @Test
    fun `max tokens keeps the text and finishes as truncated`() {
        server.enqueue(sse(GoogleAgentSseFixtures.MAX_TOKENS))
        val result = events()
        assertThat(result.first()).isEqualTo(AgentEvent.TextDelta("Vou criar uma skin"))
        assertThat(result.last()).isEqualTo(AgentEvent.Done(AgentStopReason.TRUNCATED, TokenUsage(50, 16384), null))
    }

    @Test
    fun `safety is a refusal`() {
        server.enqueue(sse(GoogleAgentSseFixtures.SAFETY))
        assertThat((events().last() as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.REFUSAL)
    }

    @Test
    fun `a blocked prompt is a refusal`() {
        server.enqueue(sse(GoogleAgentSseFixtures.PROMPT_BLOCKED))
        val done = events().single() as AgentEvent.Done
        assertThat(done.stopReason).isEqualTo(AgentStopReason.REFUSAL)
        assertThat(done.usage).isEqualTo(TokenUsage(9, 0))
    }

    @Test
    fun `an invalid function call or a missing signature ends the turn as an engine error, never as a call to run`() {
        for (fixture in listOf(GoogleAgentSseFixtures.MALFORMED, GoogleAgentSseFixtures.MISSING_SIGNATURE)) {
            server.enqueue(sse(fixture))
            val result = events()
            val error = result.last() as AgentEvent.Error
            assertThat(error.kind).isEqualTo(ReasoningError.ENGINE)
            assertThat(error.message).contains("resposta inválida")
            assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
        }
    }

    @Test
    fun `an error object after some text keeps the text and ends with a typed error that does not repeat the server message`() {
        server.enqueue(sse(GoogleAgentSseFixtures.ERROR_CHUNK))
        val result = events()
        assertThat(result.first()).isEqualTo(AgentEvent.TextDelta("Vou"))
        val error = result.last() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(error.message).contains("RESOURCE_EXHAUSTED")
        assertThat(error.message).doesNotContain("privada")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a stream that ends without a finish reason is an error, never a finished turn`() {
        server.enqueue(sse(GoogleAgentSseFixtures.CUT_MID_STREAM))
        val result = events()
        assertThat(result.first()).isEqualTo(AgentEvent.TextDelta("Vou criar uma skin"))
        assertThat((result.last() as AgentEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
    }

    @Test
    fun `a connection dropped during the body is a network error`() {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val result = events()
        assertThat((result.last() as AgentEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(result.filterIsInstance<AgentEvent.Done>()).isEmpty()
    }

    @Test
    fun `an event without the blank line after it still counts`() {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY.trimEnd('\n')))
        assertThat(events().last()).isInstanceOf(AgentEvent.Done::class.java)
    }

    @Test
    fun `an invalid data line in the stream is skipped, not a crash`() {
        server.enqueue(sse("data: {isto não é json\n\n" + GoogleAgentSseFixtures.TEXT_ONLY))
        assertThat(events().last()).isInstanceOf(AgentEvent.Done::class.java)
    }

    // --- erros HTTP ---

    @Test
    fun `401 is unauthorized, is not retried and asks the auth side to invalidate`() {
        val marker = "google-private-body-marker"
        server.enqueue(status(401, """{"error":{"code":401,"message":"$marker","status":"UNAUTHENTICATED"}}"""))
        val auth = RecordingAuth()
        val result = events(e = engine(auth))
        val error = result.single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(error.message).contains("401")
        assertThat(result.toString()).doesNotContain(marker)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(auth.invalidated).containsExactly(ProviderIds.GOOGLE)
    }

    @Test
    fun `403 is unauthorized too`() {
        server.enqueue(status(403, """{"error":{"code":403,"message":"nope","status":"PERMISSION_DENIED"}}"""))
        assertThat((events().single() as AgentEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)
    }

    @Test
    fun `429 is a rate limit and is not retried`() {
        server.enqueue(status(429, """{"error":{"code":429,"message":"slow down","status":"RESOURCE_EXHAUSTED"}}"""))
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(error.message).doesNotContain("slow down")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `400 is an engine error and is not retried`() {
        server.enqueue(status(400, """{"error":{"code":400,"message":"bad schema","status":"INVALID_ARGUMENT"}}"""))
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(error.message).contains("400")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `503 is retried with a growing pause, then reported as a network error`() {
        repeat(3) { server.enqueue(status(503, """{"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}""")) }
        val error = events().single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(pauses).containsExactly(200L, 400L).inOrder()
    }

    @Test
    fun `a 5xx followed by success recovers`() {
        server.enqueue(status(500))
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
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
        assertThat(kindFor(AuthErrorKind.TIMEOUT)).isEqualTo(ReasoningError.NETWORK)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a credential the http layer cannot send is an unauthorized error, not an exception`() {
        val auth = RecordingAuth(mapOf("x-goog-api-key" to "chave\ncom quebra de linha"))
        assertThat((events(e = engine(auth)).single() as AgentEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)
    }

    @Test
    fun `no connection is a network error`() {
        val unreachable = GoogleAgentEngine(RecordingAuth(), client, "gemini-3.8-flash", apiBaseUrl = "http://127.0.0.1:1/v1beta", pauseMs = { })
        val error = events(e = unreachable).single() as AgentEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(error.message).isEqualTo("sem conexão com o Google Gemini")
    }

    // --- cancelamento e fluxo ---

    @Test
    fun `cancelling the collection cancels the http call`() = runBlocking {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY).setBodyDelay(3, TimeUnit.SECONDS))
        val e = engine()
        val job = launch { e.chat(listOf(AgentMessage.User("x")), emptyList(), "S", 100).toList() }
        withTimeout(5_000) { while (server.requestCount == 0) kotlinx.coroutines.delay(10) }
        job.cancel()
        withTimeout(1_500) { job.join() } // sem o call.cancel() isto esperaria os 3 s do atraso do corpo
        assertThat(job.isCancelled).isTrue()
    }

    @Test
    fun `the first event arrives before the stream ends`() = runBlocking {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        val first = withTimeout(5_000) { engine().chat(listOf(AgentMessage.User("x")), emptyList(), "S", 100).first() }
        assertThat(first).isEqualTo(AgentEvent.TextDelta("Olá"))
    }

    @Test
    fun `without tools and with a tool history, the request carries no function parts`() {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        val history = listOf(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("c1", "skin_list", "{}"))),
            AgentMessage.ToolResult("c1", "ok"),
            AgentMessage.User("e agora?"),
        )
        events(history, tools = emptyList())
        val body = lastBody()
        assertThat(body.has("tools")).isFalse()
        assertThat(body.getJSONArray("contents").toString()).doesNotContain("functionCall")
        assertThat(body.getJSONArray("contents").toString()).doesNotContain("functionResponse")
    }

    @Test
    fun `the request never carries the credential in the body or the url`() {
        server.enqueue(sse(GoogleAgentSseFixtures.TEXT_ONLY))
        events()
        val recorded = server.takeRequest()
        assertThat(recorded.body.readUtf8()).doesNotContain("test-google-key")
        assertThat(recorded.path).doesNotContain("test-google-key")
    }
}
