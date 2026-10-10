package com.keyflow.reasoning.google

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Test

class GoogleModelsTest {

    private val server = MockWebServer().also { it.start() }
    private val headers = mapOf("Authorization" to "Bearer test-tok")

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: JSONObject) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body.toString())

    @Test
    fun `parses models supporting generateContent`() {
        val body = JSONObject().put(
            "models",
            JSONArray()
                .put(
                    JSONObject()
                        .put("name", "models/gemini-2.5-pro")
                        .put("displayName", "Gemini 2.5 Pro")
                        .put("supportedGenerationMethods", JSONArray().put("generateContent")),
                )
                .put(
                    JSONObject()
                        .put("name", "models/text-embedding-004")
                        .put("displayName", "Embedding")
                        .put("supportedGenerationMethods", JSONArray().put("embedContent")),
                )
                .put(
                    JSONObject()
                        .put("name", "models/gemini-2.5-flash")
                        .put("displayName", "Gemini 2.5 Flash")
                        .put("supportedGenerationMethods", JSONArray().put("generateContent")),
                ),
        )

        server.enqueue(json(body))

        val models = GoogleModels(OkHttpClient(), server.url("/v1beta").toString().trimEnd('/'))
        val result = runBlocking { models.list(headers) }

        assertThat(result.map { it.id }).containsExactly("gemini-2.5-pro", "gemini-2.5-flash").inOrder()
        assertThat(result.map { it.displayName }).containsExactly("Gemini 2.5 Pro", "Gemini 2.5 Flash").inOrder()
    }

    @Test
    fun `failure returns default models`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val models = GoogleModels(OkHttpClient(), server.url("/v1beta").toString().trimEnd('/'))
        val result = runBlocking { models.list(headers) }
        assertThat(result.map { it.id }).contains("gemini-2.0-flash")
    }
}
