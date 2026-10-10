package com.keyflow.reasoning.openai

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/** RCL-06: a lista de modelos da conta — dois formatos, nenhum id inventado, e falha vira lista vazia. */
class OpenAiModelsTest {

    private val server = MockWebServer().also { it.start() }
    private val models = OpenAiModels(OkHttpClient(), server.url("/v1").toString().trimEnd('/'))
    private val headers = mapOf("Authorization" to "Bearer tok-123")

    @After fun tearDown() = server.shutdown()

    private fun json(body: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    @Test
    fun `the client tries IPv4 first and bounds the whole call`() {
        assertThat(models.http.dns).isInstanceOf(com.keyflow.data.models.Ipv4FirstDns::class.java)
        assertThat(models.http.callTimeoutMillis).isGreaterThan(0)
    }

    @Test
    fun `the plan format keeps only visibility list, in the server's order, showing the display name`() {
        server.enqueue(
            json(
                JSONObject().put(
                    "models",
                    JSONArray()
                        .put(JSONObject().put("slug", "b-modelo").put("display_name", "Modelo B").put("visibility", "list"))
                        .put(JSONObject().put("slug", "escondido").put("display_name", "Escondido").put("visibility", "hide"))
                        .put(JSONObject().put("slug", "a-modelo").put("display_name", "Modelo A").put("visibility", "list")),
                ),
            ),
        )

        val result = runBlocking { models.list(headers) }

        assertThat(result.map { it.id }).containsExactly("b-modelo", "a-modelo").inOrder() // a ordem do servidor, não alfabética
        assertThat(result.map { it.displayName }).containsExactly("Modelo B", "Modelo A").inOrder()
    }

    @Test
    fun `the api key format lists every model id`() {
        server.enqueue(
            json(JSONObject().put("object", "list").put("data", JSONArray().put(JSONObject().put("id", "um")).put(JSONObject().put("id", "dois")))),
        )
        val result = runBlocking { models.list(headers) }
        assertThat(result.map { it.id }).containsExactly("um", "dois").inOrder()
        assertThat(result.map { it.displayName }).containsExactly("um", "dois").inOrder()
    }

    @Test
    fun `it asks the models endpoint with the same credential`() {
        server.enqueue(json(JSONObject().put("data", JSONArray())))
        runBlocking { models.list(headers) }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertThat(request.path).isEqualTo("/v1/models")
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer tok-123")
    }

    /** Falha nunca inventa modelo: a lista vem vazia e a tela mantém o que o usuário já tinha escolhido. */
    @Test
    fun `every kind of failure is an empty list, never an invented model`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(runBlocking { models.list(headers) }).isEmpty()

        server.enqueue(MockResponse().setResponseCode(200).setBody("não é json"))
        assertThat(runBlocking { models.list(headers) }).isEmpty()

        server.enqueue(json(JSONObject().put("outra", "coisa")))
        assertThat(runBlocking { models.list(headers) }).isEmpty()

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThat(runBlocking { models.list(headers) }).isEmpty()
    }

    @Test
    fun `an entry with no id is skipped, not a crash`() {
        server.enqueue(json(JSONObject().put("data", JSONArray().put(JSONObject().put("object", "model")).put(JSONObject().put("id", "ok")))))
        assertThat(runBlocking { models.list(headers) }.map { it.id }).containsExactly("ok")
    }

    @Test
    fun `non-chat and legacy snapshots are filtered out while modern models are prioritized`() {
        server.enqueue(
            json(
                JSONObject().put(
                    "data",
                    JSONArray()
                        .put(JSONObject().put("id", "whisper-1"))
                        .put(JSONObject().put("id", "text-embedding-3-small"))
                        .put(JSONObject().put("id", "babbage-002"))
                        .put(JSONObject().put("id", "gpt-3.5-turbo-0301"))
                        .put(JSONObject().put("id", "gpt-3.5-turbo"))
                        .put(JSONObject().put("id", "gpt-4o"))
                        .put(JSONObject().put("id", "o3-mini")),
                ),
            ),
        )

        val result = runBlocking { models.list(headers) }
        assertThat(result.map { it.id }).containsExactly("o3-mini", "gpt-4o", "gpt-3.5-turbo").inOrder()
        assertThat(result.map { it.displayName }).containsExactly("o3-mini", "GPT-4o", "GPT-3.5 Turbo").inOrder()
    }
}
