package com.keyflow.vision

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthorizationProvider
import com.keyflow.auth.ProviderId
import com.keyflow.reasoning.*
import com.keyflow.reasoning.openai.OpenAiReasoningEngine
import com.keyflow.reasoning.anthropic.AnthropicReasoningEngine
import com.keyflow.reasoning.google.GoogleReasoningEngine
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Test

class VisionModelEngineTest {
    private val image = ReasoningImage(byteArrayOf(1, 2, 3))

    @Test fun `falha descarta os deltas e preserva a causa`() = runBlocking {
        for (kind in ReasoningError.entries) {
            val fake = object : ReasoningEngine {
                override fun generate(request: ReasoningRequest) = flow {
                    assertThat(request.image).isSameInstanceAs(image)
                    assertThat(request.user).isEqualTo(VisionModelEngine.READ_INSTRUCTION)
                    emit(ReasoningEvent.Delta("parcial")); emit(ReasoningEvent.Error(kind, "privado"))
                }
            }
            val expected = if (kind == ReasoningError.MODEL_MISSING) VisionResult.Unavailable else VisionResult.Failed(kind)
            assertThat(CloudVisionEngine(fake).read(image)).isEqualTo(expected)
        }
    }

    @Test fun `vazio e fim sem sucesso não viram texto`() = runBlocking {
        for ((events, expected) in listOf(
            listOf(ReasoningEvent.Done) to VisionResult.Empty,
            listOf(ReasoningEvent.Delta("incompleto")) to VisionResult.Failed(ReasoningError.ENGINE),
        )) {
            val fake = object : ReasoningEngine { override fun generate(request: ReasoningRequest) = flow { events.forEach { emit(it) } } }
            assertThat(LocalVisionEngine(fake).read(image)).isEqualTo(expected)
        }
    }

    @Test fun `cancelar o pedido cancela o coletor e não vira falha amigável`() = runBlocking {
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        val fake = object : ReasoningEngine {
            override fun generate(request: ReasoningRequest) = flow<ReasoningEvent> {
                try { started.complete(Unit); awaitCancellation() } finally { stopped.complete(Unit) }
            }
        }
        val job = launch { CloudVisionEngine(fake).read(image); error("cancelamento foi engolido") }
        started.await(); job.cancelAndJoin(); assertThat(stopped.isCompleted).isTrue()
    }

    @Test fun `cada provedor recebe a imagem e a instrução no formato documentado`() = runBlocking {
        for (provider in listOf("openai", "anthropic", "google", "custom")) {
            val server = MockWebServer(); server.start()
            try {
                val ids = mutableListOf<ProviderId>()
                val auth = object : AuthorizationProvider {
                    override suspend fun headers(provider: ProviderId): Map<String, String> {
                        ids += provider; return mapOf("x-chave-falsa" to "teste")
                    }
                }
                val base = server.url("/v1").toString().trimEnd('/')
                val http = OkHttpClient()
                val transport: ReasoningEngine = when (provider) {
                    "openai" -> OpenAiReasoningEngine(auth, http, "modelo", base)
                    "anthropic" -> AnthropicReasoningEngine(auth, http, "modelo", base)
                    "google" -> GoogleReasoningEngine(auth, http, "gemini-teste", base)
                    else -> CustomVisionTransport(http, base, "modelo", "x-custom", "valor-falso")
                }
                val sse = when (provider) {
                    "openai" -> "data: {\"type\":\"response.output_text.delta\",\"delta\":\"resposta\"}\n\ndata: {\"type\":\"response.completed\"}\n\n"
                    "anthropic" -> "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"resposta\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
                    "google" -> "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"resposta\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                    else -> "data: {\"choices\":[{\"delta\":{\"content\":\"resposta\"}}]}\n\ndata: [DONE]\n\n"
                }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
                assertThat(withTimeout(5_000) { CloudVisionEngine(transport).ask(image, "Leia") })
                    .isEqualTo(VisionResult.Text("resposta"))
                val received = server.takeRequest(5, TimeUnit.SECONDS)!!
                val json = JSONObject(received.body.readUtf8())
                when (provider) {
                    "openai" -> {
                        val parts = json.getJSONArray("input").getJSONObject(0).getJSONArray("content")
                        assertThat(parts.getJSONObject(0).getString("text")).isEqualTo("Leia")
                        assertThat(parts.getJSONObject(1).getString("image_url")).isEqualTo(image.dataUrl())
                    }
                    "anthropic" -> {
                        val parts = json.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                        assertThat(parts.getJSONObject(0).getJSONObject("source").getString("data")).isEqualTo(image.base64())
                        assertThat(parts.getJSONObject(0).getJSONObject("source").getString("media_type")).isEqualTo("image/png")
                    }
                    "google" -> {
                        val part = json.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
                        assertThat(part.getJSONObject("inlineData").getString("data")).isEqualTo(image.base64())
                        assertThat(part.getJSONObject("inlineData").getString("mimeType")).isEqualTo("image/png")
                    }
                    else -> {
                        val part = json.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1)
                        assertThat(part.getJSONObject("image_url").getString("url")).isEqualTo(image.dataUrl())
                        assertThat(received.getHeader("x-custom")).isEqualTo("valor-falso")
                        assertThat(received.getHeader("x-chave-falsa")).isNull()
                    }
                }
                assertThat(ids.size).isEqualTo(if (provider == "custom") 0 else 1)
            } finally { server.shutdown() }
        }
    }

    @Test fun `custom cancela o socket ao parar e mapeia erro sem expor corpo`() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val engine = CustomVisionEngine(CustomVisionTransport(OkHttpClient(), server.url("/v1").toString(), "modelo", "", ""))
            for ((status, kind) in listOf(401 to ReasoningError.UNAUTHORIZED, 429 to ReasoningError.RATE_LIMIT, 500 to ReasoningError.NETWORK)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("segredo-do-servidor"))
                assertThat(engine.read(image)).isEqualTo(VisionResult.Failed(kind)); server.takeRequest()
            }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) { engine.read(image) }
            assertThat(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }).isNotNull()
            withTimeout(5_000) { job.cancelAndJoin() }
        } finally { server.shutdown() }
    }
}
