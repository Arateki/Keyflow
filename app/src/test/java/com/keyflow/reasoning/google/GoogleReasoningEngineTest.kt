package com.keyflow.reasoning.google

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

class GoogleReasoningEngineTest {

    private val server = MockWebServer().also { it.start() }
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    private val auth = object : AuthorizationProvider {
        override suspend fun headers(provider: ProviderId) = mapOf("Authorization" to "Bearer test-google-token")
    }

    private fun engine(model: String = "gemini-2.0-flash") =
        GoogleReasoningEngine(auth, client, model, apiBaseUrl = server.url("/v1beta").toString().trimEnd('/'))

    @After
    fun tearDown() = server.shutdown()

    private fun events(request: ReasoningRequest = ReasoningRequest(user = "olá"), e: GoogleReasoningEngine = engine()): List<ReasoningEvent> =
        runBlocking { withTimeout(5_000) { e.generate(request).toList() } }

    @Test
    fun `parses gemini streaming candidates and completes on STOP`() {
        val sseBody = buildString {
            append("data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Olá, \"}]}, \"finishReason\": \"\"}]}\n\n")
            append("data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"como vai?\"}]}, \"finishReason\": \"STOP\"}]}\n\n")
        }
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(sseBody))

        val result = events()
        val recorded = server.takeRequest()

        assertThat(recorded.path).contains("/models/gemini-2.0-flash:streamGenerateContent?alt=sse")
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-google-token")

        val deltas = result.filterIsInstance<ReasoningEvent.Delta>().map { it.text }
        assertThat(deltas).containsExactly("Olá, ", "como vai?").inOrder()
        assertThat(result.last()).isEqualTo(ReasoningEvent.Done)
    }

    @Test
    fun `translates 429 to rate limit error`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\": {\"message\": \"Resource has been exhausted\"}}"))

        val result = events()
        val error = result.filterIsInstance<ReasoningEvent.Error>().single()
        assertThat(error.kind).isEqualTo(ReasoningError.RATE_LIMIT)
    }

    @Test
    fun `translates 401 or 403 to unauthorized error`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\": {\"message\": \"API key not valid\"}}"))

        val result = events()
        val error = result.filterIsInstance<ReasoningEvent.Error>().single()
        assertThat(error.kind).isEqualTo(ReasoningError.UNAUTHORIZED)
    }
}
