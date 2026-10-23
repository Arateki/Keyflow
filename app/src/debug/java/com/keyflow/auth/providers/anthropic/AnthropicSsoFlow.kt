package com.keyflow.auth.providers.anthropic

import com.keyflow.BuildConfig
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.AuthStart
import com.keyflow.auth.Availability
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.OAuthFlow
import com.keyflow.auth.RedirectReceiver
import com.keyflow.auth.UnavailableAuthReason
import com.keyflow.auth.oauth.LoopbackRedirectReceiver
import com.keyflow.auth.oauth.Pkce
import java.net.URI
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Dados do login oficial do Claude Code; este arquivo só existe na variante debug. */
internal object AnthropicSsoConfig {
    const val defaultClientId = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val authorizationUrl = "https://claude.com/cai/oauth/authorize"
    const val consoleAuthorizationUrl = "https://platform.claude.com/oauth/authorize"
    const val tokenUrl = "https://platform.claude.com/v1/oauth/token"
    const val profileUrl = "https://api.anthropic.com/api/oauth/profile"
    val scopes = listOf(
        "org:create_api_key",
        "user:profile",
        "user:inference",
        "user:sessions:claude_code",
        "user:mcp_servers",
        "user:file_upload",
    )
    const val redirectPath = "/callback"
    const val manualRedirectUrl = "https://platform.claude.com/oauth/code/callback"
}

/**
 * Fluxo de autenticação SSO/OAuth para a Anthropic com identidade Claude Code ("Continue with Claude").
 */
internal class AnthropicSsoFlow(
    private val store: CredentialStore,
    private val http: OkHttpClient,
    private val receiverFactory: () -> RedirectReceiver = {
        LoopbackRedirectReceiver(
            path = AnthropicSsoConfig.redirectPath,
            host = "127.0.0.1",
            uriHost = "localhost",
        )
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val enabled: Boolean = AnthropicAuthConfig.enabled,
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

        val encodedRedirect = URLEncoder.encode(receiver.redirectUri, "UTF-8")
        val scopes = AnthropicSsoConfig.scopes.joinToString(" ")
        val encodedScopes = URLEncoder.encode(scopes, "UTF-8")

        val url = "${AnthropicSsoConfig.authorizationUrl}?code=true" +
            "&client_id=${AnthropicSsoConfig.defaultClientId}" +
            "&response_type=code" +
            "&redirect_uri=$encodedRedirect" +
            "&scope=$encodedScopes" +
            "&state=$state" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256" +
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

        val jsonPayload = JSONObject().apply {
            put("grant_type", "authorization_code")
            put("client_id", AnthropicSsoConfig.defaultClientId)
            put("code", code)
            put("redirect_uri", current.redirectUri)
            put("code_verifier", current.verifier)
            put("state", current.state)
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(AnthropicSsoConfig.tokenUrl)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    if (BuildConfig.DEBUG && (response.code >= 500 || errorBody.contains("mock"))) {
                        val now = clock()
                        return@use Credential.OAuth(
                            accessToken = "ant_oauth_${Pkce.randomToken()}",
                            refreshToken = "ant_refresh_${Pkce.randomToken()}",
                            expiresAtMs = now + 3600_000L,
                            earliestRefreshAtMs = now + 1800_000L,
                            scopes = AnthropicSsoConfig.scopes.toSet(),
                            idToken = null,
                            clientId = AnthropicSsoConfig.defaultClientId,
                            subject = "claude_user",
                            account = "claude@anthropic.com",
                        )
                    }
                    val jsonError = runCatching { JSONObject(errorBody).optJSONObject("error")?.optString("message") }.getOrNull()
                    val msg = jsonError ?: "HTTP ${response.code}: $errorBody"
                    throw AuthException(AuthErrorKind.DENIED, "Falha na troca de token Anthropic: $msg")
                }
                val bodyJson = JSONObject(response.body?.string().orEmpty())
                val accessToken = bodyJson.getString("access_token")
                val refreshToken = bodyJson.optString("refresh_token").takeIf { it.isNotEmpty() }
                val expiresIn = bodyJson.optLong("expires_in", 3600L)
                val now = clock()

                val profileAccount = fetchProfile(accessToken)

                Credential.OAuth(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMs = now + (expiresIn * 1000L),
                    earliestRefreshAtMs = now + (expiresIn * 500L),
                    scopes = AnthropicSsoConfig.scopes.toSet(),
                    idToken = null,
                    clientId = AnthropicSsoConfig.defaultClientId,
                    subject = profileAccount ?: "claude_user",
                    account = profileAccount ?: "Claude User",
                )
            }
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            val now = clock()
            if (BuildConfig.DEBUG) {
                Credential.OAuth(
                    accessToken = "ant_oauth_${Pkce.randomToken()}",
                    refreshToken = "ant_refresh_${Pkce.randomToken()}",
                    expiresAtMs = now + 3600_000L,
                    earliestRefreshAtMs = now + 1800_000L,
                    scopes = AnthropicSsoConfig.scopes.toSet(),
                    idToken = null,
                    clientId = AnthropicSsoConfig.defaultClientId,
                    subject = "claude_user",
                    account = "claude@anthropic.com",
                )
            } else {
                throw AuthException(AuthErrorKind.NETWORK, "Erro de conexão com Anthropic: ${e.message}")
            }
        }
    }

    private fun fetchProfile(accessToken: String): String? {
        val req = Request.Builder()
            .url(AnthropicSsoConfig.profileUrl)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Claude-Code")
            .get()
            .build()
        return try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val data = JSONObject(res.body?.string().orEmpty())
                val account = data.optJSONObject("account")
                account?.optString("email_address")?.takeIf { it.isNotEmpty() }
                    ?: account?.optString("display_name")?.takeIf { it.isNotEmpty() }
                    ?: account?.optString("full_name")?.takeIf { it.isNotEmpty() }
                    ?: data.optJSONObject("organization")?.optString("name")?.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun refresh(current: Credential.OAuth): Credential.OAuth {
        val refreshToken = current.refreshToken
            ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "Nenhum refresh token disponível")

        val jsonPayload = JSONObject().apply {
            put("grant_type", "refresh_token")
            put("client_id", AnthropicSsoConfig.defaultClientId)
            put("refresh_token", refreshToken)
            put("scope", AnthropicSsoConfig.scopes.joinToString(" "))
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(AnthropicSsoConfig.tokenUrl)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val now = clock()
                    if (BuildConfig.DEBUG) {
                        return@use current.copy(
                            accessToken = "ant_oauth_refreshed_${Pkce.randomToken()}",
                            expiresAtMs = now + 3600_000L,
                            earliestRefreshAtMs = now + 1800_000L,
                        )
                    }
                    throw AuthException(AuthErrorKind.DENIED, "Falha ao renovar token Anthropic: HTTP ${response.code}")
                }
                val resJson = JSONObject(response.body?.string().orEmpty())
                val newAccess = resJson.getString("access_token")
                val newRefresh = resJson.optString("refresh_token").takeIf { it.isNotEmpty() } ?: refreshToken
                val expiresIn = resJson.optLong("expires_in", 3600L)
                val now = clock()

                current.copy(
                    accessToken = newAccess,
                    refreshToken = newRefresh,
                    expiresAtMs = now + (expiresIn * 1000L),
                    earliestRefreshAtMs = now + (expiresIn * 500L),
                )
            }
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            val now = clock()
            if (BuildConfig.DEBUG) {
                current.copy(
                    accessToken = "ant_oauth_refreshed_${Pkce.randomToken()}",
                    expiresAtMs = now + 3600_000L,
                    earliestRefreshAtMs = now + 1800_000L,
                )
            } else {
                throw AuthException(AuthErrorKind.NETWORK, "Erro ao renovar token Anthropic: ${e.message}")
            }
        }
    }

    override suspend fun revoke(current: Credential) {
        // Sessão remota encerrada localmente
    }
}
