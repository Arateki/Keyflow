package com.keyflow.reasoning.custom

import com.google.common.truth.Truth.assertThat
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
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

class CustomReasoningEngineTest {
    private val server = MockWebServer().also { it.start() }
    @After fun fechar() = server.shutdown()

    @Test fun `nao segue redirecionamento com cabecalho privado`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/outro")))
        val engine = CustomReasoningEngine(server.url("/origem").toString(), "modelo", OkHttpClient()) {
            mapOf("X-Teste" to "credencial-falsa")
        }
        val events = runBlocking { withTimeout(10_000) { engine.generate(ReasoningRequest(user = "oi")).toList() } }
        assertThat(events.last()).isInstanceOf(ReasoningEvent.Error::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(events.toString()).doesNotContain("credencial-falsa")
    }

    @Test fun `usa endpoint configurado e corpo Chat Completions`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"olá\"}}]}\n\ndata: [DONE]\n\n",
        ))
        val engine = CustomReasoningEngine(server.url("/minha-api/chat/completions").toString(), "modelo-x", OkHttpClient()) {
            mapOf("X-Teste" to "credencial-falsa")
        }
        val events = runBlocking { withTimeout(10_000) { engine.generate(ReasoningRequest("sistema", "pedido")).toList() } }
        val sent = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(sent.path).isEqualTo("/minha-api/chat/completions")
        assertThat(sent.getHeader("X-Teste")).isEqualTo("credencial-falsa")
        val body = JSONObject(sent.body.readUtf8())
        assertThat(body.keys().asSequence().toList()).containsExactly("model", "messages", "stream")
        assertThat(body.getString("model")).isEqualTo("modelo-x")
        assertThat(body.getBoolean("stream")).isTrue()
        val messages = body.getJSONArray("messages")
        assertThat(messages.length()).isEqualTo(2)
        assertThat(messages.getJSONObject(0).getString("role")).isEqualTo("system")
        assertThat(messages.getJSONObject(0).getString("content")).isEqualTo("sistema")
        assertThat(messages.getJSONObject(1).getString("role")).isEqualTo("user")
        assertThat(messages.getJSONObject(1).getString("content")).isEqualTo("pedido")
        assertThat(events).containsExactly(ReasoningEvent.Delta("olá"), ReasoningEvent.Done).inOrder()
    }
}
