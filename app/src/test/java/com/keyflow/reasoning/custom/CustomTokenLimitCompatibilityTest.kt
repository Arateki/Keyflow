package com.keyflow.reasoning.custom

import com.google.common.truth.Truth.assertThat
import com.keyflow.reasoning.GenerationOptions
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

class CustomTokenLimitCompatibilityTest {
    private val server = MockWebServer().also { it.start() }
    @After fun fechar() = server.shutdown()

    private fun success() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"choices\":[{\"delta\":{\"content\":\"olá\"}}]}\n\n" +
            "data: {\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2},\"choices\":[]}\n\ndata: [DONE]\n\n",
    )
    private fun unsupported(param: String = "max_completion_tokens") = MockResponse().setResponseCode(400)
        .setBody("""{"error":{"code":"unsupported_parameter","param":"$param","message":"Unknown parameter: $param"}}""")
    private suspend fun generate(options: GenerationOptions = GenerationOptions(maxOutputTokens = 32)): List<ReasoningEvent> =
        withContext(Dispatchers.Default) {
            val engine = CustomReasoningEngine(server.url("/chat/completions").toString(), "modelo", OkHttpClient()) {
                mapOf("X-Teste" to "credencial-falsa")
            }
            withTimeout(5_000) { engine.generate(ReasoningRequest(user = "oi", options = options)).toList() }
        }

    @Test fun servicoAtualRecebeSomenteMaxCompletionTokensSemRepeticao() = runTest {
        server.enqueue(success())
        assertThat(generate().last()).isEqualTo(ReasoningEvent.Done)
        assertThat(server.requestCount).isEqualTo(1)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertThat(body.getInt("max_completion_tokens")).isEqualTo(32)
        assertThat(body.has("max_tokens")).isFalse()
    }

    @Test fun rejeicaoExplicitaDoCampoAtualRepeteUmaVezComMaxTokens() = runTest {
        server.enqueue(unsupported())
        server.enqueue(success())
        val events = generate()
        assertThat(events.last()).isEqualTo(ReasoningEvent.Done)
        assertThat(events.filterIsInstance<ReasoningEvent.Delta>()).containsExactly(ReasoningEvent.Delta("olá"))
        assertThat(server.requestCount).isEqualTo(2)
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        val retry = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(retry.path).isEqualTo(first.path)
        assertThat(retry.getHeader("X-Teste")).isEqualTo("credencial-falsa")
        val original = JSONObject(first.body.readUtf8())
        val legacy = JSONObject(retry.body.readUtf8())
        assertThat(original.getInt("max_completion_tokens")).isEqualTo(32)
        assertThat(original.has("max_tokens")).isFalse()
        assertThat(legacy.getInt("max_tokens")).isEqualTo(32)
        assertThat(legacy.has("max_completion_tokens")).isFalse()
        original.remove("max_completion_tokens"); legacy.remove("max_tokens")
        fun values(json: JSONObject) = json.keys().asSequence().associateWith { json.get(it).toString() }
        assertThat(values(original)).isEqualTo(values(legacy))
        val metrics = events.filterIsInstance<ReasoningEvent.Metrics>().single()
        assertThat(metrics.applied[GenerationOptions.MAX_OUTPUT_TOKENS]).isEqualTo("32")
        assertThat(metrics.inputTokens).isEqualTo(7)
        assertThat(metrics.outputTokens).isEqualTo(2)
        assertThat(events.toString()).doesNotContain("credencial-falsa")
    }

    @Test fun rejeicaoSemCodeOuParamReconheceApenasONomeExatoDoCampo() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"message":"Unrecognized request argument supplied: max_completion_tokens"}}""",
        ))
        server.enqueue(success())
        assertThat(generate().last()).isEqualTo(ReasoningEvent.Done)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun segundaRejeicaoEncerraSemTerceiroPedido() = runTest {
        server.enqueue(unsupported()); server.enqueue(unsupported("max_tokens"))
        val events = generate()
        assertThat(events.last()).isInstanceOf(ReasoningEvent.Error::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun rejeicaoDeOutroCampoNaoRepeteNemExpoeCorpo() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"code":"unsupported_parameter","param":"temperature","message":"credencial-falsa; max_completion_tokens não é o problema"}}""",
        ))
        val events = generate()
        assertThat(events.last()).isInstanceOf(ReasoningEvent.Error::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(events.toString()).doesNotContain("credencial-falsa")
    }

    @Test fun rejeicaoSemParamDeTemperatureNaoRepeteAoListarLimiteComoPermitido() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"code":"unknown_parameter","message":"Unknown parameter: temperature. Supported fields include max_completion_tokens."}}""",
        ))
        server.enqueue(success())
        val events = generate()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(events).containsExactly(ReasoningEvent.Error(ReasoningError.ENGINE, "Custom: HTTP 400"))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertThat(body.getInt("max_completion_tokens")).isEqualTo(32)
        assertThat(body.has("max_tokens")).isFalse()
    }

    @Test fun valorInvalidoNaoEConfundidoComCampoNaoSuportado() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"code":"invalid_value","param":"max_completion_tokens","message":"must be positive"}}""",
        ))
        assertThat(generate().last()).isInstanceOf(ReasoningEvent.Error::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun semLimitePedidoNaoNegociaCampoNemAlteraCorpoPadrao() = runTest {
        server.enqueue(unsupported())
        assertThat(generate(GenerationOptions()).last()).isInstanceOf(ReasoningEvent.Error::class.java)
        assertThat(server.requestCount).isEqualTo(1)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertThat(body.keys().asSequence().toList()).containsExactly("model", "messages", "stream")
    }

    @Test fun cancelarDuranteARepeticaoCancelaOCallAtual() = runTest {
        val calls = CopyOnWriteArrayList<Call>()
        val cancelled = CountDownLatch(1)
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callStart(call: Call) { calls += call }
            override fun callFailed(call: Call, ioe: IOException) {
                if (calls.size == 2 && call === calls.last() && call.isCanceled()) cancelled.countDown()
            }
        }).build()
        server.enqueue(unsupported())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val engine = CustomReasoningEngine(server.url("/chat/completions").toString(), "modelo", client)
        val job = launch(Dispatchers.Default) {
            engine.generate(ReasoningRequest(user = "oi", options = GenerationOptions(maxOutputTokens = 32))).toList()
        }
        try {
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
            job.cancel()
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue()
        } finally {
            job.cancel()
            calls.forEach { it.cancel() }
            job.join()
        }
    }

    @Test fun errosHttpQueNaoRejeitamOCampoNaoRepetem() = runTest {
        for ((index, status) in listOf(401, 403, 429, 503, 422).withIndex()) {
            server.enqueue(unsupported().setResponseCode(status))
            val events = generate()
            val expected = when (status) {
                401, 403 -> ReasoningError.UNAUTHORIZED
                429 -> ReasoningError.RATE_LIMIT
                503 -> ReasoningError.NETWORK
                else -> ReasoningError.ENGINE
            }
            assertThat((events.last() as ReasoningEvent.Error).kind).isEqualTo(expected)
            assertThat(server.requestCount).isEqualTo(index + 1)
        }
    }
}
