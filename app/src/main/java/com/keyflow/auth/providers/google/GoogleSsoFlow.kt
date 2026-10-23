package com.keyflow.auth.providers.google

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.AuthStart
import com.keyflow.auth.Availability
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.HttpErrorMessage
import com.keyflow.auth.OAuthFlow
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.RedirectReceiver
import com.keyflow.auth.UnavailableAuthReason
import com.keyflow.auth.oauth.LoopbackRedirectReceiver
import com.keyflow.auth.oauth.Pkce
import java.net.URI
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fluxo de autenticação SSO/OAuth 2.0 com PKCE para o Google ("Continue with Google" / Gemini).
 */
internal class GoogleSsoFlow(
    private val store: CredentialStore,
    private val http: OkHttpClient,
    private val receiverFactory: () -> RedirectReceiver = { LoopbackRedirectReceiver(GoogleAuthConfig.redirectPath) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val enabled: Boolean = GoogleAuthConfig.enabled,
) : OAuthFlow {

    override val method: AuthMethod = AuthMethod.OAUTH
    override val availability: Availability =
        if (enabled) Availability.Available else Availability.Unavailable(UnavailableAuthReason.PROVIDER_DISABLED)

    private class Pending(val state: String, val verifier: String, val redirectUri: String)
    @Volatile private var pending: Pending? = null

    override suspend fun begin(forceConsent: Boolean): AuthStart {
        val receiver = receiverFactory()
        val verifier = Pkce.newVerifier()
        val challenge = Pkce.challengeS256(verifier)
        val state = Pkce.randomToken()
        pending = Pending(state = state, verifier = verifier, redirectUri = receiver.redirectUri)

        val scopes = GoogleAuthConfig.scopes.joinToString(" ")
        val url = "${GoogleAuthConfig.authorizationUrl}?response_type=code" +
            "&client_id=${GoogleAuthConfig.defaultClientId}" +
            "&redirect_uri=${receiver.redirectUri}" +
            "&scope=$scopes" +
            "&state=$state" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256" +
            "&access_type=offline" +
            if (forceConsent) "&prompt=consent" else ""

        return AuthStart(url = url, redirect = receiver)
    }

    override suspend fun complete(redirect: URI): Credential.OAuth {
        val current = pending ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "Nenhum login em andamento")
        val query = redirect.query.orEmpty()
        val params = query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }

        val returnedState = params["state"]
        if (returnedState != current.state) {
            throw AuthException(AuthErrorKind.DENIED, "State inválido no redirect")
        }

        val code = params["code"] ?: throw AuthException(AuthErrorKind.DENIED, "Código de autorização não recebido")

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", GoogleAuthConfig.defaultClientId)
            .add("code", code)
            .add("redirect_uri", current.redirectUri)
            .add("code_verifier", current.verifier)
            .build()

        val request = Request.Builder()
            .url(GoogleAuthConfig.tokenUrl)
            .post(form)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw AuthException(AuthErrorKind.NETWORK, "Falha ao conectar com o Google", e)
        }

        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            HttpErrorMessage.log("Google token", response.code, body)
            throw AuthException(AuthErrorKind.INVALID_RESPONSE, HttpErrorMessage.of("Google token", response.code, body))
        }

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AuthException(AuthErrorKind.INVALID_RESPONSE, "Resposta do Google não é JSON", e)
        }

        val accessToken = json.optString("access_token")
        if (accessToken.isNullOrEmpty()) {
            throw AuthException(AuthErrorKind.INVALID_RESPONSE, "access_token ausente")
        }

        val refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() }
        val expiresInSec = json.optLong("expires_in", 3600L)
        val now = clock()
        val expiresAtMs = now + (expiresInSec * 1000L)
        val idToken = json.optString("id_token").takeIf { it.isNotEmpty() }
        val scopeStr = json.optString("scope", GoogleAuthConfig.scopes.joinToString(" "))
        val grantedScopes = scopeStr.split(" ").toSet()

        // Tenta obter o nome/email da conta
        var accountName: String? = null
        if (!idToken.isNullOrEmpty()) {
            accountName = parseEmailFromIdToken(idToken)
        }
        if (accountName == null) {
            accountName = fetchUserInfo(accessToken)
        }

        val credential = Credential.OAuth(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMs = expiresAtMs,
            earliestRefreshAtMs = expiresAtMs - 300_000L,
            scopes = grantedScopes,
            idToken = idToken,
            clientId = GoogleAuthConfig.defaultClientId,
            subject = accountName,
            account = accountName,
        )

        store.put(ProviderIds.GOOGLE, credential)
        return credential
    }

    override suspend fun refresh(current: Credential.OAuth): Credential.OAuth {
        val refreshToken = current.refreshToken
            ?: throw AuthException(AuthErrorKind.NEEDS_RECONNECT, "Sem refresh token")

        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", GoogleAuthConfig.defaultClientId)
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(GoogleAuthConfig.tokenUrl)
            .post(form)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw AuthException(AuthErrorKind.NETWORK, "Falha de rede ao renovar token", e)
        }

        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            HttpErrorMessage.log("Google token", response.code, body)
            if (response.code in 400..499) {
                throw AuthException(AuthErrorKind.NEEDS_RECONNECT, HttpErrorMessage.of("Google token", response.code, body))
            }
            throw AuthException(AuthErrorKind.INVALID_RESPONSE, HttpErrorMessage.of("Google token", response.code, body))
        }

        val json = JSONObject(body)
        val newAccessToken = json.optString("access_token")
        if (newAccessToken.isNullOrEmpty()) {
            throw AuthException(AuthErrorKind.INVALID_RESPONSE, "Token renovado inválido")
        }

        val expiresInSec = json.optLong("expires_in", 3600L)
        val now = clock()
        val expiresAtMs = now + (expiresInSec * 1000L)

        val updated = current.copy(
            accessToken = newAccessToken,
            expiresAtMs = expiresAtMs,
            earliestRefreshAtMs = expiresAtMs - 300_000L,
        )
        store.put(ProviderIds.GOOGLE, updated)
        return updated
    }

    override suspend fun revoke(current: Credential) {
        if (current is Credential.OAuth) {
            val token = current.refreshToken ?: current.accessToken
            if (token.isNotEmpty()) {
                val form = FormBody.Builder().add("token", token).build()
                val request = Request.Builder().url(GoogleAuthConfig.revokeUrl).post(form).build()
                runCatching { http.newCall(request).execute().close() }
            }
        }
    }

    private fun parseEmailFromIdToken(idToken: String): String? = runCatching {
        val parts = idToken.split(".")
        if (parts.size >= 2) {
            val decodedBytes = com.keyflow.auth.oauth.Base64Url.decode(parts[1])
            val json = JSONObject(String(decodedBytes, Charsets.UTF_8))
            json.optString("email").takeIf { it.isNotEmpty() } ?: json.optString("name").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun fetchUserInfo(accessToken: String): String? = runCatching {
        val request = Request.Builder()
            .url(GoogleAuthConfig.userinfoUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optString("email").takeIf { it.isNotEmpty() } ?: json.optString("name").takeIf { it.isNotEmpty() }
            } else null
        }
    }.getOrNull()
}
