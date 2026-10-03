package com.keyflow.reasoning.openai

import com.keyflow.auth.providers.openai.OpenAiAuthConfig
import com.keyflow.auth.providers.openai.await
import com.keyflow.data.models.Ipv4FirstDns
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Os modelos que a conta pode usar (RCL-06): `GET {base}/models` com a MESMA credencial da geração. Entende os dois formatos —
 * o do **plano** (`models[]` com `slug`/`display_name`/`visibility`; só `"list"` aparece, na ordem do servidor) e o da **chave
 * de API** (`data[].id`). Falha de qualquer tipo é lista **vazia**: nunca se inventa um id de modelo; a tela mantém o que o
 * usuário já tinha escolhido e oferece "Atualizar".
 */
class OpenAiModels(
    http: OkHttpClient,
    private val apiBaseUrl: String = OpenAiAuthConfig.apiBaseUrl,
) {
    /** IPv4 antes do IPv6 (rede com IPv6 quebrado) e um prazo para a chamada inteira: o dropdown não pode ficar pendurado. */
    internal val http: OkHttpClient = http.newBuilder().dns(Ipv4FirstDns()).callTimeout(30, TimeUnit.SECONDS).build()

    data class Model(val id: String, val displayName: String)

    companion object {
        val DEFAULT_MODELS = listOf(
            Model("gpt-4o", "GPT-4o"),
            Model("gpt-4o-mini", "GPT-4o Mini"),
            Model("o3-mini", "o3-mini"),
            Model("o1", "o1"),
            Model("chatgpt-4o-latest", "ChatGPT-4o (Latest)"),
            Model("gpt-4.5-preview", "GPT-4.5 Preview"),
            Model("gpt-4-turbo", "GPT-4 Turbo"),
        )

        private val EXCLUDED_KEYWORDS = listOf(
            "embedding",
            "whisper",
            "tts",
            "dall-e",
            "moderation",
            "babbage",
            "davinci",
            "curie",
            "text-ada",
            "audio",
            "realtime",
        )

        private val LEGACY_SNAPSHOT_REGEX = Regex(".*-(0301|0613|0314|1106)$")

        val FRIENDLY_NAMES = mapOf(
            "gpt-4o" to "GPT-4o",
            "gpt-4o-mini" to "GPT-4o Mini",
            "o3-mini" to "o3-mini",
            "o1" to "o1",
            "o1-mini" to "o1-mini",
            "o1-preview" to "o1-preview",
            "chatgpt-4o-latest" to "ChatGPT-4o (Latest)",
            "gpt-4.5-preview" to "GPT-4.5 Preview",
            "gpt-5.5" to "GPT-5.5",
            "gpt-5.6-luna" to "GPT-5.6-Luna",
            "gpt-5.6-sol" to "GPT-5.6-Sol",
            "gpt-5.6-terra" to "GPT-5.6-Terra",
            "gpt-6-astra" to "GPT-6-Astra",
            "gpt-4-turbo" to "GPT-4 Turbo",
            "gpt-4" to "GPT-4",
            "gpt-3.5-turbo" to "GPT-3.5 Turbo",
        )

        val PRIORITY_MODELS = listOf(
            "o3-mini",
            "o1",
            "gpt-4o",
            "gpt-4o-mini",
            "chatgpt-4o-latest",
            "gpt-4.5-preview",
            "gpt-5.5",
            "gpt-5.6-luna",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-6-astra",
            "gpt-4-turbo",
            "o1-mini",
            "o1-preview",
            "gpt-4",
            "gpt-3.5-turbo",
        )
    }

    suspend fun list(headers: Map<String, String>): List<Model> = try {
        val request = Request.Builder().url("$apiBaseUrl/models").apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        http.newCall(request).await().use { response ->
            if (response.isSuccessful) parse(JSONObject(response.body?.string().orEmpty())) else emptyList()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private fun parse(json: JSONObject): List<Model> {
        json.optJSONArray("models")?.let { array ->
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filter { it.optString("visibility") == "list" && it.optString("slug").isNotEmpty() }
                .map {
                    val slug = it.getString("slug")
                    val name = it.optString("display_name").ifEmpty { FRIENDLY_NAMES[slug] ?: slug }
                    Model(slug, name)
                }
        }
        json.optJSONArray("data")?.let { array ->
            val raw = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .mapNotNull { it.optString("id").takeIf { id -> id.isNotEmpty() } }
                .filter { id ->
                    val lower = id.lowercase()
                    EXCLUDED_KEYWORDS.none { lower.contains(it) } && !LEGACY_SNAPSHOT_REGEX.matches(lower)
                }
            return raw.sortedBy { id ->
                val idx = PRIORITY_MODELS.indexOfFirst { id.equals(it, ignoreCase = true) || id.startsWith("$it-", ignoreCase = true) }
                if (idx >= 0) idx else PRIORITY_MODELS.size
            }.map { id ->
                Model(id, FRIENDLY_NAMES[id] ?: id)
            }
        }
        return emptyList()
    }
}
