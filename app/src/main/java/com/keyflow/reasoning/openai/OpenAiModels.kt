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
                .map { Model(it.getString("slug"), it.optString("display_name").ifEmpty { it.getString("slug") }) }
        }
        json.optJSONArray("data")?.let { array ->
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .filter { it.optString("id").isNotEmpty() }
                .map { Model(it.getString("id"), it.getString("id")) }
        }
        return emptyList()
    }
}
