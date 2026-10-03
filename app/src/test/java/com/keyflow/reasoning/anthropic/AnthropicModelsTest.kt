package com.keyflow.reasoning.anthropic

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test

class AnthropicModelsTest {

    private val server = MockWebServer().also { it.start() }
    private val headers = mapOf("Authorization" to "Bearer test-tok", "anthropic-version" to "2023-06-01")

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: JSONObject) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body.toString())

    @Test
    fun `parses catalog and api models while filtering out obsolete Claude 1 and 2 models`() {
        val catalogJson = JSONObject().put(
            "surfaces",
            JSONObject().put(
                "cc",
                JSONObject().put(
                    "model_selector_state",
                    JSONArray().put(
                        JSONObject().put(
                            "thinking_by_model",
                            JSONArray()
                                .put(JSONObject().put("id", "claude-sonnet-5").put("name", "Sonnet 5"))
                                .put(JSONObject().put("id", "claude-sonnet-4-6").put("name", "Sonnet 4.6")),
                        ),
                    ),
                ),
            ),
        )

        val apiJson = JSONObject().put(
            "data",
            JSONArray()
                .put(JSONObject().put("id", "claude-2.1").put("display_name", "Claude 2.1"))
                .put(JSONObject().put("id", "claude-instant-1.2").put("display_name", "Claude Instant"))
                .put(JSONObject().put("id", "claude-3-7-sonnet-20250219").put("display_name", "Claude 3.7 Sonnet")),
        )

        server.enqueue(json(catalogJson))
        server.enqueue(json(apiJson))

        val models = AnthropicModels(
            OkHttpClient(),
            apiBaseUrl = server.url("/v1").toString().trimEnd('/'),
            catalogUrl = server.url("/catalog.json").toString(),
        )

        val result = runBlocking { models.list(headers) }

        val ids = result.map { it.id }
        // Não deve conter modelos legados
        assertThat(ids).doesNotContain("claude-2.1")
        assertThat(ids).doesNotContain("claude-instant-1.2")

        // Deve conter os modelos modernos
        assertThat(ids).contains("claude-sonnet-5")
        assertThat(ids).contains("claude-sonnet-4-6")
        assertThat(ids).contains("claude-3-7-sonnet-20250219")

        // Sonnet 5 e Sonnet 4.6 devem vir antes de 3.7 Sonnet
        val idxSonnet5 = ids.indexOf("claude-sonnet-5")
        val idxSonnet46 = ids.indexOf("claude-sonnet-4-6")
        val idx37 = ids.indexOf("claude-3-7-sonnet-20250219")
        assertThat(idxSonnet5).isLessThan(idx37)
        assertThat(idxSonnet46).isLessThan(idx37)
    }

    @Test
    fun `network failure gracefully returns modern default models`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        val models = AnthropicModels(
            OkHttpClient(),
            apiBaseUrl = server.url("/v1").toString().trimEnd('/'),
            catalogUrl = server.url("/catalog.json").toString(),
        )

        val result = runBlocking { models.list(headers) }
        assertThat(result).isNotEmpty()
        assertThat(result.map { it.id }).contains("claude-sonnet-4-6")
        assertThat(result.map { it.id }).contains("claude-3-7-sonnet-20250219")
    }
}
