package com.keyflow.net

import android.util.Log
import com.keyflow.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Service token do Cloudflare Access, num único ponto do cliente HTTP.
 * Os dois cabeçalhos saem juntos, só com os dois valores preenchidos e só para o host da base da API.
 * Sem token, um 401 desse host vira aviso no log de debug; a resposta segue para quem chamou.
 */
internal class CloudflareAccessInterceptor(
    clientId: String,
    clientSecret: String,
    apiBaseUrl: String,
    private val debugLog: ((String) -> Unit)? = if (BuildConfig.DEBUG) ::logDebug else null,
) : Interceptor {
    private val clientId = clientId.trim()
    private val clientSecret = clientSecret.trim()
    private val apiHost: String? = apiBaseUrl.toHttpUrlOrNull()?.host
    private val credentialsUsable = usableHeader(this.clientId) && usableHeader(this.clientSecret)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val forApiHost = apiHost != null && request.url.host.equals(apiHost, ignoreCase = true)
        val builder = request.newBuilder()
        if (forApiHost && credentialsUsable) {
            builder.header(HEADER_ID, clientId)
            builder.header(HEADER_SECRET, clientSecret)
        } else {
            // Redirect para outro host não pode levar o token que o hop anterior anexou.
            builder.removeHeader(HEADER_ID)
            builder.removeHeader(HEADER_SECRET)
        }
        val response = chain.proceed(builder.build())
        if (forApiHost && !credentialsUsable && response.code == 401) {
            debugLog?.invoke(MISSING_TOKEN_LOG)
        }
        return response
    }

    internal companion object {
        const val HEADER_ID = "CF-Access-Client-Id"
        const val HEADER_SECRET = "CF-Access-Client-Secret"
        const val MISSING_TOKEN_LOG =
            "token do Cloudflare Access ausente — veja api/deploy/LEIA-ME.md"

        private const val TAG = "KeyFlowApi"

        private fun logDebug(message: String) {
            Log.d(TAG, message)
        }
    }
}

/** Valor aceito pelo OkHttp num cabeçalho. Vazio ou com controle não é enviado — a chamada não quebra. */
private fun usableHeader(value: String): Boolean {
    if (value.isEmpty()) return false
    return value.all { char -> char == '\t' || char in ' '..'~' }
}
