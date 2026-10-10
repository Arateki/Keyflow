package com.keyflow.reasoning.google

import com.keyflow.auth.providers.google.GoogleAuthConfig
import com.keyflow.auth.providers.openai.await
import com.keyflow.data.models.Ipv4FirstDns
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Resolução e catálogo de modelos Google Gemini.
 * Consulta o endpoint `GET /models` se autenticado e filtra modelos com suporte a geração de conteúdo.
 */
class GoogleModels(
    http: OkHttpClient,
    private val apiBaseUrl: String = GoogleAuthConfig.apiBaseUrl,
) {
    internal val http: OkHttpClient = http.newBuilder()
        .dns(Ipv4FirstDns())
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Model(val id: String, val displayName: String)

    companion object {
        val DEFAULT_MODELS = listOf(
            Model("gemini-2.0-flash", "Gemini 2.0 Flash"),
            Model("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite"),
            Model("gemini-1.5-flash", "Gemini 1.5 Flash"),
            Model("gemini-1.5-pro", "Gemini 1.5 Pro"),
        )
    }

    suspend fun list(headers: Map<String, String> = emptyMap()): List<Model> {
        if (headers.isEmpty()) return DEFAULT_MODELS
        return try {
            val request = Request.Builder()
                .url("$apiBaseUrl/models")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            http.newCall(request).await().use { response ->
                if (response.isSuccessful) parse(JSONObject(response.body?.string().orEmpty())) else DEFAULT_MODELS
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DEFAULT_MODELS
        }
    }

    private fun parse(json: JSONObject): List<Model> {
        val models = json.optJSONArray("models") ?: return DEFAULT_MODELS
        val result = mutableListOf<Model>()
        for (i in 0 until models.length()) {
            val obj = models.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            val id = name.removePrefix("models/")
            if (!id.startsWith("gemini")) continue
            val methods = obj.optJSONArray("supportedGenerationMethods")
            val supportsGenerate = methods != null && (0 until methods.length()).any {
                methods.optString(it) == "generateContent"
            }
            if (!supportsGenerate && methods != null) continue
            val disp = obj.optString("displayName").ifEmpty { id }
            result.add(Model(id, disp))
        }
        return if (result.isEmpty()) DEFAULT_MODELS else result
    }
}
