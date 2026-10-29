package com.keyflow.reasoning.openai

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.ProviderId
import com.keyflow.auth.ProviderIds
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/**
 * A Responses API para o plano e para a chave de API (RCL-02/03/05, Review Focus 3): corpo enxuto, fluxo SSE lido linha a
 * linha, sucesso só em `response.completed`, e cada erro documentado mapeado sem nunca cair em outra forma de cobrança.
 * As fixtures reproduzem os eventos e os códigos da documentação (`openai-siwc-notes.md`).
 */
class OpenAiReasoningEngineTest {

    private val server = MockWebServer().also { it.start() }
    private val pauses = mutableListOf<Long>()
    private val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val client = OkHttpClient.Builder().eventListener(object : EventListener() {
        override fun canceled(call: Call) { canceled.set(true) }
    }).readTimeout(10, TimeUnit.SECONDS).build()

    private val allowed = object : AuthorizationProvider {
        override suspend fun headers(provider: ProviderId) = mapOf("Authorization" to "Bearer tok-123")
    }

    private fun engine(auth: AuthorizationProvider = allowed, model: String = "modelo-x") =
        OpenAiReasoningEngine(auth, client, model, apiBaseUrl = server.url("/v1").toString().trimEnd('/'), pauseMs = { pauses += it })

    @After fun tearDown() = server.shutdown()

    /** Anota quem pediu para invalidar o login (RCL-03). */
    private class RecordingAuth : AuthorizationProvider {
        val invalidated = mutableListOf<ProviderId>()
        override suspend fun headers(provider: ProviderId) = mapOf("Authorization" to "Bearer tok-123")
        override fun invalidate(provider: ProviderId) { invalidated += provider }
    }

    private fun events(request: ReasoningRequest = ReasoningRequest(user = "oi"), e: OpenAiReasoningEngine = engine()): List<ReasoningEvent> =
        runBlocking { withTimeout(20_000) { e.generate(request).toList().filterNot { it is ReasoningEvent.Metrics } } }

    // ── fixtures SSE ──────────────────────────────────────────────────────────────────────

    private fun sse(vararg events: Pair<String, JSONObject>): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" })

    private fun delta(text: String) = "response.output_text.delta" to JSONObject().put("type", "response.output_text.delta").put("delta", text)
    private val completed = "response.completed" to JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed"))
    private fun failed(code: String) = "response.failed" to JSONObject().put("type", "response.failed")
        .put("response", JSONObject().put("error", JSONObject().put("code", code).put("message", "detalhe interno")))
    private val incomplete = "response.incomplete" to JSONObject().put("type", "response.incomplete")
        .put("response", JSONObject().put("incomplete_details", JSONObject().put("reason", "max_output_tokens")))
    private val created = "response.created" to JSONObject().put("type", "response.created")

    private fun status(code: Int, body: String = "") = MockResponse().setResponseCode(code).setBody(body)
    private fun errorBody(code: String) = JSONObject().put("error", JSONObject().put("code", code).put("message", "x")).toString()

    private fun requestJson() = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())

    /** A rede do Yan tem IPv6 quebrado: o motor tenta o IPv4 antes; e uma resposta longa não pode ser cortada por prazo total. */
    @Test
    fun `the engine's client tries IPv4 first and has no call deadline but keeps a read timeout`() {
        val c = engine().client
        assertThat(c.dns).isInstanceOf(com.keyflow.data.models.Ipv4FirstDns::class.java)
        assertThat(c.callTimeoutMillis).isEqualTo(0)
        assertThat(c.readTimeoutMillis).isGreaterThan(0)
    }

    // ── o pedido ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the request body has exactly model, input, store and stream — and none of the fields the plan flow rejects`() {
        server.enqueue(sse(delta("a"), completed))
        events()

        val json = requestJson()
        assertThat(json.keys().asSequence().toSet()).containsExactly("model", "input", "store", "stream")
        assertThat(json.getString("model")).isEqualTo("modelo-x")
        assertThat(json.getBoolean("store")).isFalse()
        assertThat(json.getBoolean("stream")).isTrue()
        val input = json.getJSONArray("input")
        assertThat(input.length()).isEqualTo(1)
        assertThat(input.getJSONObject(0).getString("role")).isEqualTo("user")
        assertThat(input.getJSONObject(0).getString("content")).isEqualTo("oi")
        listOf("max_output_tokens", "temperature", "top_p", "user", "metadata", "previous_response_id", "background", "truncation").forEach {
            assertThat(json.has(it)).isFalse()
        }
    }

    /** "explicit {type: message, role: system} items are rejected" — o system vai em `instructions`. */
    @Test
    fun `a system message goes in instructions and never as an input item`() {
        server.enqueue(sse(completed))
        events(ReasoningRequest(system = "Seja breve.", user = "oi"))

        val json = requestJson()
        assertThat(json.keys().asSequence().toSet()).containsExactly("model", "input", "store", "stream", "instructions")
        assertThat(json.getString("instructions")).isEqualTo("Seja breve.")
        val input = json.getJSONArray("input")
        assertThat((0 until input.length()).map { input.getJSONObject(it).getString("role") }).containsExactly("user")
    }

    @Test
    fun `it posts to the responses endpoint with a bearer token and json headers`() {
        server.enqueue(sse(completed))
        events()

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(request.path).isEqualTo("/v1/responses")
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok-123")
        assertThat(request.getHeader("Content-Type")).contains("application/json")
        assertThat(request.getHeader("Accept")).contains("text/event-stream")
    }

    // ── o fluxo ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `it streams the output text deltas in order and finishes only on response completed`() {
        server.enqueue(sse(created, delta("Olá"), delta(", "), delta("mundo"), completed))

        val result = events()

        assertThat(result).containsExactly(
            ReasoningEvent.Delta("Olá"), ReasoningEvent.Delta(", "), ReasoningEvent.Delta("mundo"), ReasoningEvent.Done,
        ).inOrder()
    }

    /** Review Focus 3: sem `response.completed` não é sucesso, e o que já chegou fica. */
    @Test
    fun `a stream that ends without response completed is an error and keeps what arrived`() {
        server.enqueue(sse(delta("parc"), delta("ial")))

        val result = events()

        assertThat(result.dropLast(1)).containsExactly(ReasoningEvent.Delta("parc"), ReasoningEvent.Delta("ial")).inOrder()
        assertThat((result.last() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(result).doesNotContain(ReasoningEvent.Done)
    }

    @Test
    fun `an invalid data line is an error, not a crash`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: x\ndata: {isto não é json\n\n"))

        val result = events()

        assertThat((result.single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.ENGINE)
    }

    @Test
    fun `events it does not care about are ignored`() {
        server.enqueue(sse(created, "response.output_item.added" to JSONObject().put("type", "response.output_item.added"), delta("x"), completed))
        assertThat(events()).containsExactly(ReasoningEvent.Delta("x"), ReasoningEvent.Done).inOrder()
    }

    @Test
    fun `response failed with the usage limit code maps to rate limit and keeps the partial text`() {
        server.enqueue(sse(delta("meio"), failed("subscription_sharing_usage_limit_exceeded")))
        val result = events()
        assertThat(result.first()).isEqualTo(ReasoningEvent.Delta("meio"))
        assertThat((result.last() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.RATE_LIMIT)
    }

    /** "Preserve credentials" — o motor nunca toca em credencial; só classifica o erro como temporário. */
    @Test
    fun `response failed with usage unavailable in the middle of the stream is a temporary network error`() {
        server.enqueue(sse(delta("x"), failed("subscription_sharing_usage_unavailable")))
        assertThat((events().last() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
    }

    @Test
    fun `response failed with an unknown code is an engine error`() {
        server.enqueue(sse(failed("codigo_que_ninguem_conhece")))
        assertThat((events().single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.ENGINE)
    }

    @Test
    fun `response incomplete is an error`() {
        server.enqueue(sse(delta("x"), incomplete))
        assertThat((events().last() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.ENGINE)
    }

    // ── erros antes do fluxo ──────────────────────────────────────────────────────────────

    @Test
    fun `401 maps to unauthorized`() {
        server.enqueue(status(401, errorBody("subscription_sharing_invalid_user")))
        assertThat((events().single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(server.requestCount).isEqualTo(1)
    }

    /** RCL-03: 403 not_eligible → explicar (não "entre de novo": entrar de novo não resolve) e NÃO repetir. */
    @Test
    fun `403 not eligible is its own error, is never retried and never invalidates the login`() {
        server.enqueue(status(403, errorBody("subscription_sharing_user_not_eligible")))
        val recording = RecordingAuth()
        assertThat((events(e = engine(recording)).single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.NOT_ELIGIBLE)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(recording.invalidated).isEmpty()
    }

    @Test
    fun `response failed with not eligible in the middle of the stream is the same error`() {
        server.enqueue(sse(delta("x"), failed("subscription_sharing_user_not_eligible")))
        assertThat((events().last() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.NOT_ELIGIBLE)
    }

    /** RCL-03: 401 invalid_user → a conexão vira NeedsReconnect (o motor pede ao lado da autenticação que invalide o login). */
    @Test
    fun `401 asks the auth side to invalidate the login`() {
        server.enqueue(status(401, errorBody("subscription_sharing_invalid_user")))
        val recording = RecordingAuth()

        assertThat((events(e = engine(recording)).single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)

        assertThat(recording.invalidated).containsExactly(ProviderIds.OPENAI)
    }

    @Test
    fun `invalid user in the middle of the stream also invalidates the login`() {
        server.enqueue(sse(delta("x"), failed("subscription_sharing_invalid_user")))
        val recording = RecordingAuth()

        assertThat((events(e = engine(recording)).last() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)

        assertThat(recording.invalidated).containsExactly(ProviderIds.OPENAI)
    }

    @Test
    fun `other errors never invalidate the login`() {
        val recording = RecordingAuth()
        server.enqueue(status(429, errorBody("subscription_sharing_usage_limit_exceeded")))
        server.enqueue(status(503)); server.enqueue(status(503)); server.enqueue(status(503))
        server.enqueue(status(400, errorBody("subscription_sharing_unsupported_capability")))
        repeat(3) { events(e = engine(recording)) }
        assertThat(recording.invalidated).isEmpty()
    }

    /** Review, Important 1: uma chave colada com um caractere invisível não pode virar exceção — o OkHttp a recusa no cabeçalho. */
    @Test
    fun `a credential the http layer cannot send is an unauthorized error, not an exception`() {
        val zeroWidth = object : AuthorizationProvider {
            override suspend fun headers(provider: ProviderId) = mapOf("Authorization" to "Bearer sk-abc\u200B")
        }
        assertThat((events(e = engine(zeroWidth)).single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `403 route not supported and the chatpass codes are engine errors and never retried`() {
        listOf("subscription_sharing_route_not_supported", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context").forEach { code ->
            server.enqueue(status(403, errorBody(code)))
            assertThat((events().single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.ENGINE)
        }
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `429 maps to rate limit with one single request — never another billing path`() {
        server.enqueue(status(429, errorBody("subscription_sharing_usage_limit_exceeded")))
        assertThat((events().single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `400 unsupported capability is an engine error and the same body is not sent again`() {
        server.enqueue(status(400, errorBody("subscription_sharing_unsupported_capability")))
        assertThat((events().single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `503 is retried a bounded number of times with backoff and then reported as network`() {
        repeat(3) { server.enqueue(status(503)) }

        val result = events()

        assertThat((result.single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(pauses).hasSize(2)
        assertThat(pauses[1]).isGreaterThan(pauses[0])
    }

    @Test
    fun `a 503 that recovers on the retry streams normally`() {
        server.enqueue(status(503))
        server.enqueue(sse(delta("ok"), completed))
        assertThat(events()).containsExactly(ReasoningEvent.Delta("ok"), ReasoningEvent.Done).inOrder()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `a detail body before the stream is kept as diagnostic text, not as a machine readable code`() {
        server.enqueue(status(403, JSONObject().put("detail", "região não permitida").toString()))
        val error = events().single() as ReasoningEvent.Error
        assertThat(error.kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(error.message).contains("região não permitida")
    }

    @Test
    fun `a connection failure maps to network`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = events()
        assertThat((result.single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
    }

    // ── autenticação ──────────────────────────────────────────────────────────────────────

    @Test
    fun `an auth failure maps to unauthorized without any http request`() {
        val notConnected = object : AuthorizationProvider {
            override suspend fun headers(provider: ProviderId): Map<String, String> = throw AuthException(AuthErrorKind.NOT_CONNECTED, "x")
        }
        assertThat((events(e = engine(notConnected)).single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a temporary auth failure maps to network`() {
        val offline = object : AuthorizationProvider {
            override suspend fun headers(provider: ProviderId): Map<String, String> = throw AuthException(AuthErrorKind.NETWORK, "x")
        }
        assertThat((events(e = engine(offline)).single() as ReasoningEvent.Error).kind).isEqualTo(ReasoningError.NETWORK)
    }

    @Test
    fun `an error never leaks the token into its message`() {
        server.enqueue(status(401, errorBody("subscription_sharing_invalid_user")))
        val error = events().single() as ReasoningEvent.Error
        assertThat(error.message.orEmpty()).doesNotContain("tok-123")
    }

    // ── cancelamento ──────────────────────────────────────────────────────────────────────

    /** Review Focus 4: cancelar o coletor cancela a chamada HTTP de verdade — não deixa a conexão aberta lendo. */
    @Test
    fun `cancelling the collector cancels the http call`() {
        server.enqueue(sse(delta("primeiro"), completed).throttleBody(8, 200, TimeUnit.MILLISECONDS)) // pinga devagar

        val first = runBlocking { withTimeout(10_000) { engine().generate(ReasoningRequest(user = "oi")).first() } }

        assertThat(first).isEqualTo(ReasoningEvent.Delta("primeiro"))
        val deadline = System.currentTimeMillis() + 5_000
        while (!canceled.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(canceled.get()).isTrue()
    }
}
