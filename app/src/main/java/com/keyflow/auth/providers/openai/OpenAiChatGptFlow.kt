package com.keyflow.auth.providers.openai

import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.AuthStart
import com.keyflow.auth.Availability
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.OAuthFlow
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.RedirectReceiver
import com.keyflow.auth.UnavailableAuthReason
import com.keyflow.auth.oauth.LoopbackRedirectReceiver
import com.keyflow.auth.oauth.PendingAuthHolder
import com.keyflow.auth.oauth.Pkce
import java.io.IOException
import java.net.URI
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * "Entrar com o ChatGPT" (fluxo open-source da OpenAI): registro dinâmico na 1ª vez, reautorização sobre o registro guardado
 * nas seguintes, PKCE, redirect por loopback, ID token verificado. Todos os valores vêm do texto literal da doc
 * (`openai-siwc-notes.md`). Nunca loga nem põe token em mensagem de erro.
 */
internal class OpenAiChatGptFlow(
    private val store: CredentialStore,
    private val http: OkHttpClient,
    private val endpoints: OpenAiEndpoints,
    private val discovery: OpenAiDiscovery,
    private val verifier: IdTokenVerifier,
    private val hostId: OpenAiHostId,
    private val receiverFactory: () -> RedirectReceiver = { LoopbackRedirectReceiver(OpenAiAuthConfig.redirectPath) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val enabled: Boolean = OpenAiAuthConfig.enabled,
    private val pauseMs: suspend (Long) -> Unit = { delay(it) },
) : OAuthFlow {

    override val method = AuthMethod.OAUTH
    override val availability: Availability =
        if (enabled) Availability.Available else Availability.Unavailable(UnavailableAuthReason.PROVIDER_DISABLED)

    private val pending = PendingAuthHolder(clock)

    /** O que só esta tentativa sabe e a URL do callback precisa confirmar. */
    private class Attempt(val redirectUri: String, val clientId: String, val newRegistration: Boolean, val expectedSubject: String?)

    @Volatile private var attempt: Attempt? = null

    override suspend fun begin(forceConsent: Boolean): AuthStart {
        val existing = store.getOAuth(ProviderIds.OPENAI)
        val issued = existing?.clientId
        val reauth = issued != null
        val receiver = receiverFactory()
        val state = Pkce.randomToken()
        val nonce = Pkce.randomToken()
        val verifierValue = Pkce.newVerifier()
        pending.begin(state, nonce, verifierValue)
        attempt = Attempt(receiver.redirectUri, issued ?: OpenAiAuthConfig.provisionalClientId, newRegistration = !reauth, expectedSubject = if (reauth) existing?.subject else null)

        val url = endpoints.authorizationUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", issued ?: OpenAiAuthConfig.provisionalClientId)
            // O nome do app só no registro novo; na reautorização a doc manda omitir.
            if (!reauth) addQueryParameter("agent_name_hint", OpenAiAuthConfig.agentNameHint)
            addQueryParameter("ext_agent_host_id", hostId.get())
            if (reauth) {
                existing?.idToken?.let { addQueryParameter("id_token_hint", it) }
                existing?.account?.let { addQueryParameter("login_hint", it) }
            }
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", receiver.redirectUri)
            addQueryParameter("scope", OpenAiAuthConfig.scopes.joinToString(" "))
            addQueryParameter("resource", OpenAiAuthConfig.resource)
            addQueryParameter("state", state)
            addQueryParameter("nonce", nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", Pkce.challengeS256(verifierValue))
            // Reabilitar uma permissão recusada: a doc manda repetir o OAuth com o conjunto completo de escopos e `prompt=consent`.
            if (forceConsent) addQueryParameter("prompt", "consent")
        }.build()
        return AuthStart(url.toString(), receiver)
    }

    override suspend fun complete(redirect: URI): Credential.OAuth {
        val params = queryOf(redirect)
        // O `state` primeiro, sempre: um callback forjado (mesmo dizendo "access_denied") não é tratado como recusa do usuário nem toca
        // nas credenciais. (Ele ainda encerra a espera no receptor de loopback — ver PendingAuth.)
        val state = params["state"] ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "callback sem state")
        val current = pending.consume(state) ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "callback sem tentativa pendente")
        val started = attempt ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "tentativa desconhecida")

        params["error"]?.let { throw AuthException(AuthErrorKind.DENIED, "o login foi recusado ($it)") }
        val code = params["code"] ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "callback sem code")
        val issued = params["client_id"]
        val clientId = when {
            // Registro novo: sem o client_id emitido, o registro está incompleto; o provisório nunca vale como emitido.
            started.newRegistration -> issued?.takeIf { it != OpenAiAuthConfig.provisionalClientId }
                ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "registro incompleto: o callback não trouxe o client_id emitido")
            // Reautorização: o callback pode omitir; se trouxer outro, é rejeitado em vez de trocar o registro da conta.
            issued != null && issued != started.clientId -> throw AuthException(AuthErrorKind.INVALID_RESPONSE, "o callback trouxe outro client_id")
            else -> started.clientId
        }

        val json = postToken(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to current.verifier,
                "redirect_uri" to started.redirectUri,
                "resource" to OpenAiAuthConfig.resource,
            ),
        )
        val idToken = json.optString("id_token").ifEmpty { null }
            ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "a resposta não trouxe o ID token")
        val identity = verifier.verify(idToken, audience = clientId, nonce = current.nonce)
        // Conta que voltou é a mesma? Só então as credenciais podem ser trocadas.
        started.expectedSubject?.let { if (it != identity.subject) throw AuthException(AuthErrorKind.DENIED, "o login voltou com outra conta") }

        attempt = null
        return credentialFrom(json, clientId, idToken, identity, fallbackScope = params["scope"])
    }

    override suspend fun refresh(current: Credential.OAuth): Credential.OAuth {
        val clientId = current.clientId ?: throw AuthException(AuthErrorKind.INVALID_GRANT, "sem client_id guardado")
        val refreshToken = current.refreshToken ?: throw AuthException(AuthErrorKind.INVALID_GRANT, "sem refresh token")
        // `scope` de propósito ausente: a doc manda omitir para manter a concessão.
        val json = postToken(
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "resource" to OpenAiAuthConfig.resource,
            ),
        )
        val renewed = credentialFrom(json, clientId, idToken = current.idToken, identity = null, fallbackScope = null)
        // O que a renovação não devolve, o registro mantém.
        return renewed.copy(
            refreshToken = renewed.refreshToken ?: current.refreshToken,
            scopes = renewed.scopes.ifEmpty { current.scopes },
            idToken = json.optString("id_token").ifEmpty { null } ?: current.idToken,
            subject = current.subject,
            account = current.account,
        )
    }

    override suspend fun revoke(current: Credential) {
        val oauth = current as? Credential.OAuth ?: return
        val token = oauth.refreshToken ?: return
        val clientId = oauth.clientId ?: return
        val endpoint = discovery.get().revocationEndpoint
        var last = AuthException(AuthErrorKind.NETWORK, "revogação não confirmada")
        repeat(REVOKE_ATTEMPTS) { attempt ->
            try {
                http.postForm(endpoint, mapOf("token" to token, "token_type_hint" to "refresh_token", "client_id" to clientId)).use { r ->
                    if (r.isSuccessful) return // HTTP 200 vazio = sucesso, inclusive para token já inválido
                    if (r.code < 500 && r.code != 429) throw AuthException(AuthErrorKind.INVALID_RESPONSE, "a revogação foi recusada (HTTP ${r.code})")
                    last = AuthException(AuthErrorKind.NETWORK, "a revogação respondeu HTTP ${r.code}")
                }
            } catch (e: IOException) {
                last = AuthException(AuthErrorKind.NETWORK, "sem conexão para revogar", e)
            }
            if (attempt < REVOKE_ATTEMPTS - 1) pauseMs(REVOKE_BACKOFF_MS shl attempt)
        }
        throw last
    }

    private suspend fun postToken(form: Map<String, String>): JSONObject {
        val response = try {
            http.postForm(endpoints.tokenUrl, form)
        } catch (e: IOException) {
            throw AuthException(AuthErrorKind.NETWORK, "sem conexão com o provedor", e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) return parseJson(text)
            throw errorFor(r.code, text)
        }
    }

    /** O erro do token endpoint: só os CÓDIGOS documentados importam; o corpo não vai para a mensagem. */
    private fun errorFor(status: Int, body: String): AuthException {
        val code = errorCodeOf(body)
        return when {
            code in UNUSABLE_GRANT_CODES -> AuthException(AuthErrorKind.INVALID_GRANT, "o token endpoint recusou ($code)")
            code == "invalid_client" -> AuthException(AuthErrorKind.DENIED, "o cliente foi recusado (invalid_client)")
            // As credenciais da renovação foram rejeitadas, com um código que a doc não lista: pedir login em vez de repetir para sempre.
            status == 401 -> AuthException(AuthErrorKind.INVALID_GRANT, "o token endpoint rejeitou as credenciais (HTTP 401)")
            status >= 500 || status == 429 -> AuthException(AuthErrorKind.NETWORK, "o token endpoint respondeu HTTP $status")
            else -> AuthException(AuthErrorKind.INVALID_RESPONSE, "o token endpoint recusou (HTTP $status $code)")
        }
    }

    /**
     * A doc não define a forma do corpo de erro do token endpoint (openai-siwc-notes.md): aceita `{"error":"código"}`,
     * `{"error":{"code":"código"}}` e `{"code":"código"}`. O texto do corpo nunca vai para a mensagem.
     */
    private fun errorCodeOf(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return ""
        val error = json.opt("error")
        return when {
            error is String -> error
            error is JSONObject -> error.optString("code").ifEmpty { error.optString("error") }
            else -> json.optString("code")
        }
    }

    private fun credentialFrom(
        json: JSONObject,
        clientId: String,
        idToken: String?,
        identity: IdTokenVerifier.Identity?,
        fallbackScope: String?,
    ): Credential.OAuth {
        val access = json.optString("access_token").ifEmpty { null } ?: throw AuthException(AuthErrorKind.INVALID_RESPONSE, "a resposta não trouxe o access token")
        val expiresIn = if (json.has("expires_in")) json.optLong("expires_in") else 3_600L
        val scopes = (json.optString("scope").ifEmpty { fallbackScope.orEmpty() }).split(' ').filter { it.isNotEmpty() }.toSet()
        return Credential.OAuth(
            accessToken = access,
            refreshToken = json.optString("refresh_token").ifEmpty { null },
            expiresAtMs = clock() + expiresIn * 1000,
            earliestRefreshAtMs = parseEarliest(json.opt("earliest_refresh_at")),
            scopes = scopes,
            idToken = idToken,
            clientId = clientId,
            subject = identity?.subject,
            account = identity?.email,
        )
    }

    private fun queryOf(uri: URI): Map<String, String> =
        (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }

    companion object {
        private const val REVOKE_ATTEMPTS = 3
        private const val REVOKE_BACKOFF_MS = 200L

        /** Os códigos que a doc lista como "refresh token inutilizável": limpar os tokens e refazer o OAuth com o mesmo client_id. */
        private val UNUSABLE_GRANT_CODES = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused",
        )

        /**
         * `earliest_refresh_at`: a doc só o lista como campo da resposta — formato e semântica NÃO estão documentados. Lido de forma
         * defensiva como segundos desde a época (número ou texto numérico) ou ISO-8601; ilegível → `null` (e a regra primária,
         * "renovar perto de expirar", segue valendo). Devolve milissegundos.
         */
        fun parseEarliest(value: Any?): Long? {
            val seconds: Long = when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull() ?: return parseIso(value.trim())
                else -> return null
            }
            return if (seconds > 0) seconds * 1000 else null
        }

        private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""")

        private fun parseIso(text: String): Long? {
            val g = (ISO.matchEntire(text) ?: return null).groupValues
            val offsetMs = g[7].let { zone ->
                if (zone.isEmpty() || zone == "Z") 0L else {
                    val sign = if (zone[0] == '-') -1 else 1
                    val digits = zone.substring(1).replace(":", "")
                    sign * (digits.substring(0, 2).toInt() * 3_600_000L + digits.substring(2).toInt() * 60_000L)
                }
            }
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            }
            return (cal.timeInMillis - offsetMs).takeIf { it > 0 }
        }
    }
}
