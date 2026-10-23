package com.keyflow.reasoning.anthropic

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.ProviderId
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test

class AnthropicReasoningEngineTest {

    private val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    private val auth = object : AuthorizationProvider {
        override suspend fun headers(provider: ProviderId) = mapOf("x-api-key" to "test-anthropic-key")
    }

    private fun engine(model: String = "claude-3-5-sonnet") =
        AnthropicReasoningEngine(auth, client, model, apiBaseUrl = server.url("/v1").toString().trimEnd('/'))

    @After
    fun tearDown() = server.shutdown()

    private fun events(request: ReasoningRequest = ReasoningRequest(user = "olá"), e: AnthropicReasoningEngine = engine()): List<ReasoningEvent> =
        runBlocking { withTimeout(5_000) { e.generate(request).toList() } }

    @Test
    fun `parses anthropic sse content block deltas and message stop`() {
        val sseBody = buildString {
            append("event: content_block_delta\ndata: {\"type\": \"content_block_delta\", \"delta\": {\"text\": \"Olá, \"}}\n\n")
            append("event: content_block_delta\ndata: {\"type\": \"content_block_delta\", \"delta\": {\"text\": \"mundo!\"}}\n\n")
            append("event: message_stop\ndata: {\"type\": \"message_stop\"}\n\n")
        }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseBody))

        val result = events()
        val recorded = server.takeRequest()

        assertThat(recorded.path).contains("/messages")
        assertThat(recorded.getHeader("x-api-key")).isEqualTo("test-anthropic-key")

        val deltas = result.filterIsInstance<ReasoningEvent.Delta>().map { it.text }
        assertThat(deltas).containsExactly("Olá, ", "mundo!").inOrder()
        assertThat(result.last()).isEqualTo(ReasoningEvent.Done)
    }

    @Test
    fun `translates 401 to unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\": {\"message\": \"Invalid API Key\"}, \"request_id\": \"private-request-123\"}"))

        val result = events()
        val error = result.filterIsInstance<ReasoningEvent.Error>().single()
        assertThat(error.kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(error.message.orEmpty()).doesNotContain("private-request-123")
    }
}
