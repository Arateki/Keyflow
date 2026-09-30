package com.keyflow.auth.providers.openai

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import okhttp3.OkHttpClient
import org.json.JSONException

/**
 * O documento de descoberta OIDC da OpenAI: dele saem o `jwks_uri` (as chaves que assinam o ID token) e o `revocation_endpoint`
 * — a doc manda ler daqui em vez de fixar as URLs. Guardado em memória depois da 1ª leitura; o `issuer` é conferido.
 */
internal class OpenAiDiscovery(
    private val http: OkHttpClient,
    private val discoveryUrl: String,
    private val issuer: String,
) {
    data class Doc(val revocationEndpoint: String, val jwksUri: String)

    @Volatile private var cached: Doc? = null

    suspend fun get(): Doc = cached ?: fetch().also { cached = it }

    private suspend fun fetch(): Doc {
        val json = http.getJson(discoveryUrl)
        if (json.optString("issuer") != issuer) throw AuthException(AuthErrorKind.INVALID_RESPONSE, "o documento de descoberta é de outro emissor")
        return try {
            Doc(revocationEndpoint = json.getString("revocation_endpoint"), jwksUri = json.getString("jwks_uri"))
        } catch (e: JSONException) {
            throw AuthException(AuthErrorKind.INVALID_RESPONSE, "o documento de descoberta está incompleto")
        }
    }
}
