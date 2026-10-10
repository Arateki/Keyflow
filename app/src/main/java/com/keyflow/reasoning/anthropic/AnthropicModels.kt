package com.keyflow.reasoning.anthropic

import com.keyflow.auth.providers.anthropic.AnthropicAuthConfig
import com.keyflow.auth.providers.openai.await
import com.keyflow.data.models.Ipv4FirstDns
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Resolução dinâmica e catálogo de modelos Anthropic Claude (espelhando a descoberta do Claude Code CLI).
 * Busca o catálogo oficial assinado da Claude (https://downloads.claude.ai/model-catalog/v1/catalog.json)
 * e o endpoint de modelos da conta (`GET /v1/models`), filtrando modelos obsoletos (Claude 1/2) e
 * ordenando pelas gerações mais recentes e potentes (Sonnet 5.5/5/4.6, Opus 5/4.6, 3.7 Sonnet).
 */
class AnthropicModels(
    http: OkHttpClient,
    private val apiBaseUrl: String = AnthropicAuthConfig.apiBaseUrl,
    private val catalogUrl: String = CATALOG_URL,
) {
    internal val http: OkHttpClient = http.newBuilder()
        .dns(Ipv4FirstDns())
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Model(val id: String, val displayName: String)

    companion object {
        const val CATALOG_URL = "https://downloads.claude.ai/model-catalog/v1/catalog.json"

        val DEFAULT_MODELS = listOf(
            Model("claude-sonnet-5-5", "Claude Sonnet 5.5"),
            Model("claude-sonnet-5", "Claude Sonnet 5"),
            Model("claude-sonnet-4-6", "Claude Sonnet 4.6"),
            Model("claude-opus-5-5", "Claude Opus 5.5"),
            Model("claude-opus-5", "Claude Opus 5"),
            Model("claude-opus-4-8", "Claude Opus 4.8"),
            Model("claude-opus-4-6", "Claude Opus 4.6"),
            Model("claude-fable-5-1", "Claude Fable 5.1"),
            Model("claude-fable-5", "Claude Fable 5"),
            Model("claude-haiku-4-5-20251001", "Claude Haiku 4.5"),
            Model("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet"),
            Model("claude-3-5-sonnet-latest", "Claude 3.5 Sonnet"),
            Model("claude-3-5-haiku-latest", "Claude 3.5 Haiku"),
            Model("claude-3-opus-latest", "Claude 3 Opus"),
        )

        private val KNOWN_NAMES = mapOf(
            "claude-sonnet-5-5" to "Claude Sonnet 5.5",
            "claude-sonnet-5" to "Claude Sonnet 5",
            "claude-sonnet-4-6" to "Claude Sonnet 4.6",
            "claude-sonnet-4-5" to "Claude Sonnet 4.5",
            "claude-opus-5-5" to "Claude Opus 5.5",
            "claude-opus-5" to "Claude Opus 5",
            "claude-opus-4-8" to "Claude Opus 4.8",
            "claude-opus-4-7" to "Claude Opus 4.7",
            "claude-opus-4-6" to "Claude Opus 4.6",
            "claude-fable-5-1" to "Claude Fable 5.1",
            "claude-fable-5" to "Claude Fable 5",
            "claude-haiku-4-5-20251001" to "Claude Haiku 4.5",
            "claude-haiku-4-5" to "Claude Haiku 4.5",
            "claude-3-7-sonnet" to "Claude 3.7 Sonnet",
            "claude-3-7-sonnet-20250219" to "Claude 3.7 Sonnet",
            "claude-3-5-sonnet-latest" to "Claude 3.5 Sonnet",
            "claude-3-5-sonnet-20241022" to "Claude 3.5 Sonnet",
            "claude-3-5-sonnet" to "Claude 3.5 Sonnet",
            "claude-3-5-haiku-latest" to "Claude 3.5 Haiku",
            "claude-3-5-haiku-20241022" to "Claude 3.5 Haiku",
            "claude-3-5-haiku" to "Claude 3.5 Haiku",
            "claude-3-opus-latest" to "Claude 3 Opus",
            "claude-3-opus-20240229" to "Claude 3 Opus",
            "claude-3-opus" to "Claude 3 Opus",
            "claude-3-sonnet-20240229" to "Claude 3 Sonnet",
            "claude-3-sonnet" to "Claude 3 Sonnet",
            "claude-3-haiku-20240307" to "Claude 3 Haiku",
            "claude-3-haiku" to "Claude 3 Haiku",
        )

        private val PRIORITY_PREFIXES = listOf(
            "claude-sonnet-5-5",
            "claude-sonnet-5",
            "claude-sonnet-4-6",
            "claude-sonnet-4-5",
            "claude-opus-5-5",
            "claude-opus-5",
            "claude-opus-4-8",
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-fable-5-1",
            "claude-fable-5",
            "claude-haiku-4-5",
            "claude-3-7-sonnet",
            "claude-3-5-sonnet",
            "claude-3-5-haiku",
            "claude-3-opus",
            "claude-3-sonnet",
            "claude-3-haiku",
        )
    }

    suspend fun list(headers: Map<String, String> = emptyMap()): List<Model> {
        val collected = mutableMapOf<String, String>()

        // 1. Catálogo assinado de modelos Claude Code
        try {
            val catRequest = Request.Builder().url(catalogUrl).get().build()
            http.newCall(catRequest).await().use { response ->
                if (response.isSuccessful) {
                    parseCatalog(JSONObject(response.body?.string().orEmpty()), collected)
                }
            }
        } catch (_: Exception) {
            // Segue para o endpoint da conta ou fallback
        }

        // 2. Endpoint da conta com credenciais se houver
        if (headers.isNotEmpty()) {
            try {
                val apiRequest = Request.Builder()
                    .url("$apiBaseUrl/models?limit=1000")
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .get()
                    .build()
                http.newCall(apiRequest).await().use { response ->
                    if (response.isSuccessful) {
                        parseApi(JSONObject(response.body?.string().orEmpty()), collected)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Mantém o que já coletou
            }
        }

        // Se o catálogo ou endpoint não trouxerem os modelos base padrão, inclui para completude
        for (m in DEFAULT_MODELS) {
            if (!collected.containsKey(m.id)) {
                collected[m.id] = m.displayName
            }
        }

        return collected
            .filter { (id, _) -> isValidModel(id) }
            .map { (id, name) -> Model(id, resolveName(id, name)) }
            .sortedWith(
                compareBy<Model> { model ->
                    val idx = PRIORITY_PREFIXES.indexOfFirst { model.id.startsWith(it, ignoreCase = true) }
                    if (idx >= 0) idx else PRIORITY_PREFIXES.size
                }.thenBy { it.id },
            )
    }

    private fun parseCatalog(json: JSONObject, out: MutableMap<String, String>) {
        val surfaces = json.optJSONObject("surfaces") ?: return
        for (surfaceKey in listOf("cc", "chat", "ccd", "ccr")) {
            val surface = surfaces.optJSONObject(surfaceKey) ?: continue
            val selectorStates = surface.optJSONArray("model_selector_state") ?: continue
            for (i in 0 until selectorStates.length()) {
                val state = selectorStates.optJSONObject(i) ?: continue
                val modelsArray = state.optJSONArray("thinking_by_model") ?: continue
                for (j in 0 until modelsArray.length()) {
                    val m = modelsArray.optJSONObject(j) ?: continue
                    val id = m.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val name = m.optString("name").takeIf { it.isNotEmpty() } ?: ""
                    if (!out.containsKey(id) || name.isNotEmpty()) {
                        out[id] = name
                    }
                }
            }
        }
    }

    private fun parseApi(json: JSONObject, out: MutableMap<String, String>) {
        val data = json.optJSONArray("data") ?: return
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val name = item.optString("display_name").takeIf { it.isNotEmpty() } ?: ""
            if (!out.containsKey(id) || name.isNotEmpty()) {
                out[id] = name
            }
        }
    }

    private fun isValidModel(id: String): Boolean {
        val lower = id.lowercase()
        if (!lower.contains("claude") && !lower.contains("anthropic")) return false
        // Filtra modelos legados / obsoletos (Claude 1.x, Claude 2.x, Instant)
        if (lower.startsWith("claude-1") || lower.startsWith("claude-2") || lower.contains("instant")) return false
        return true
    }

    private fun resolveName(id: String, rawName: String): String {
        if (rawName.isNotEmpty()) {
            return if (rawName.startsWith("Claude", ignoreCase = true)) rawName else "Claude $rawName"
        }
        KNOWN_NAMES[id]?.let { return it }
        return formatId(id)
    }

    private fun formatId(id: String): String {
        return id.split("-").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
