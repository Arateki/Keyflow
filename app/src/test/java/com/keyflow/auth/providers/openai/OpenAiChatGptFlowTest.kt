package com.keyflow.auth.providers.openai

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.Availability
import com.keyflow.auth.Credential
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.UnavailableAuthReason
import com.keyflow.auth.fake.FakeCredentialStore
import com.keyflow.auth.fake.FakeReceiver
import com.keyflow.auth.fake.oauth
import com.keyflow.auth.oauth.Pkce
import java.net.URI
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test

/** O fluxo de login ChatGPT contra um servidor falso: registro dinâmico, reautorização, troca do código, renovação, revogação. */
class OpenAiChatGptFlowTest {

    private val openai = FakeOpenAi()
    private val store = FakeCredentialStore()
    private val http = OkHttpClient()
    private val redirect = "http://127.0.0.1:5555/auth/callback"
    private var receiver = FakeReceiver(redirect)
    private val pauses = mutableListOf<Long>()

    private fun flow(enabled: Boolean = true): OpenAiChatGptFlow {
        val discovery = OpenAiDiscovery(http, openai.endpoints().discoveryUrl, ISSUER)
        return OpenAiChatGptFlow(
            store = store, http = http, endpoints = openai.endpoints(), discovery = discovery,
            verifier = IdTokenVerifier(http, discovery, ISSUER) { NOW_MS },
            hostId = OpenAiHostId(store),
            receiverFactory = { receiver.also { } }, clock = { NOW_MS }, enabled = enabled,
            pauseMs = { pauses += it },
        )
    }

    @After fun tearDown() = openai.shutdown()

    private fun stateOf(url: String) = url.toHttpUrl().queryParameter("state")!!
    private fun nonceOf(url: String) = url.toHttpUrl().queryParameter("nonce")!!
    private fun callback(state: String, extra: String = "code=CODE&client_id=oaiapp_1") = URI("$redirect?$extra&state=$state")

    private fun failure(block: suspend () -> Any?): AuthException =
        runCatching { runBlocking { block() } }.exceptionOrNull() as? AuthException ?: error("deveria ter falhado com AuthException")

    /** Faz um login completo de registro novo e devolve a credencial. */
    private fun loginOk(f: OpenAiChatGptFlow = flow(), idToken: (String) -> String = { nonce -> openai.signer.jwt(idTokenPayload(nonce = nonce)) }): Credential.OAuth {
        val start = runBlocking { f.begin() }
        openai.tokenResponses += openai.tokenOk(idToken = idToken(nonceOf(start.url)), earliest = NOW_SECONDS + 3_000)
        return runBlocking { f.complete(callback(stateOf(start.url))) }
    }

    // ── begin ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `begin builds the authorization url with every required parameter, url encoded`() {
        val start = runBlocking { flow().begin() }
        val url = start.url.toHttpUrl()

        assertThat(start.url).startsWith(openai.url("/authorize"))
        assertThat(url.queryParameter("response_type")).isEqualTo("code")
        assertThat(url.queryParameter("redirect_uri")).isEqualTo(redirect)
        assertThat(url.queryParameter("scope")!!.split(' ')).containsExactly(
            "openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct",
        )
        assertThat(url.queryParameter("resource")).isEqualTo("https://api.openai.com/v1")
        assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256")
        assertThat(url.queryParameter("code_challenge")).isNotEmpty()
        assertThat(url.queryParameter("state")!!.length).isAtLeast(30)
        assertThat(url.queryParameter("nonce")!!.length).isAtLeast(30)
        assertThat(url.queryParameter("ext_agent_host_id")).startsWith("urn:uuid:")
        assertThat(start.redirect).isSameInstanceAs(receiver)
    }

    @Test
    fun `the first login uses the provisional client id and the agent name hint`() {
        val url = runBlocking { flow().begin() }.url.toHttpUrl()
        assertThat(url.queryParameter("client_id")).isEqualTo("dynamic_agent_client")
        assertThat(url.queryParameter("agent_name_hint")).isEqualTo("KeyFlow")
        assertThat(url.queryParameter("id_token_hint")).isNull()
        assertThat(url.queryParameter("login_hint")).isNull()
    }

    @Test
    fun `a returning account uses its issued client id and the hints, and omits the agent name hint`() {
        store.put(ProviderIds.OPENAI, oauth(clientId = "oaiapp_1", idToken = "id-token-retido", account = "pessoa@exemplo.com"))
        val url = runBlocking { flow().begin() }.url.toHttpUrl()

        assertThat(url.queryParameter("client_id")).isEqualTo("oaiapp_1")
        assertThat(url.queryParameter("id_token_hint")).isEqualTo("id-token-retido")
        assertThat(url.queryParameter("login_hint")).isEqualTo("pessoa@exemplo.com")
        assertThat(url.queryParameter("agent_name_hint")).isNull()
    }

    @Test
    fun `an account with no registration yet is a new registration even if a skeleton exists without a client id`() {
        store.put(ProviderIds.OPENAI, oauth(clientId = null, idToken = null, account = null))
        val url = runBlocking { flow().begin() }.url.toHttpUrl()
        assertThat(url.queryParameter("client_id")).isEqualTo("dynamic_agent_client")
    }

    @Test
    fun `the host id is the same on every login and survives a disconnect`() {
        val first = runBlocking { flow().begin() }.url.toHttpUrl().queryParameter("ext_agent_host_id")
        store.clear(ProviderIds.OPENAI)
        receiver = FakeReceiver(redirect)
        val second = runBlocking { flow().begin() }.url.toHttpUrl().queryParameter("ext_agent_host_id")
        assertThat(second).isEqualTo(first)
    }

    /** A doc: repetir o OAuth com o conjunto COMPLETO de escopos e `prompt=consent` para reabilitar o uso do plano. */
    @Test
    fun `forcing consent adds prompt consent to the authorization url and only then`() {
        val ordinary = runBlocking { flow().begin() }.url.toHttpUrl()
        assertThat(ordinary.queryParameter("prompt")).isNull()

        receiver = FakeReceiver(redirect)
        val forced = runBlocking { flow().begin(forceConsent = true) }.url.toHttpUrl()
        assertThat(forced.queryParameter("prompt")).isEqualTo("consent")
        assertThat(forced.queryParameter("scope")!!.split(' ')).contains("chatgpt.tokens.use.direct") // o conjunto completo
    }

    @Test
    fun `a disabled provider is unavailable with its reason`() {
        assertThat(flow(enabled = false).availability).isEqualTo(Availability.Unavailable(UnavailableAuthReason.PROVIDER_DISABLED))
        assertThat(flow(enabled = true).availability).isEqualTo(Availability.Available)
    }

    // ── complete ──────────────────────────────────────────────────────────────────────────

    /** Review Focus 1. */
    @Test
    fun `a wrong state is refused, no code is exchanged, and the real attempt stays alive`() {
        val f = flow()
        val start = runBlocking { f.begin() }
        val real = stateOf(start.url)

        val failure = failure { f.complete(callback("estado-forjado")) }

        assertThat(failure.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
        assertThat(openai.requestsTo("/oauth/token")).isEmpty()
        openai.tokenResponses += openai.tokenOk(idToken = openai.signer.jwt(idTokenPayload(nonce = nonceOf(start.url))))
        assertThat(runBlocking { f.complete(callback(real)) }.accessToken).isEqualTo("access-1") // a legítima ainda vale
    }

    @Test
    fun `a callback with no pending attempt is refused — the app died mid login`() {
        val failure = failure { flow().complete(callback("qualquer")) }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
        assertThat(openai.requestsTo("/oauth/token")).isEmpty()
    }

    @Test
    fun `access denied stops without exchanging a code`() {
        val f = flow()
        val start = runBlocking { f.begin() }
        val failure = failure { f.complete(URI("$redirect?error=access_denied&state=${stateOf(start.url)}")) }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.DENIED)
        assertThat(openai.requestsTo("/oauth/token")).isEmpty()
    }

    @Test
    fun `an error callback with a wrong state is not trusted as a denial`() {
        val f = flow()
        runBlocking { f.begin() }
        val failure = failure { f.complete(URI("$redirect?error=access_denied&state=forjado")) }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `a new registration whose callback has no issued client id is incomplete`() {
        val f = flow()
        val start = runBlocking { f.begin() }
        val failure = failure { f.complete(URI("$redirect?code=CODE&state=${stateOf(start.url)}")) }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
        assertThat(openai.requestsTo("/oauth/token")).isEmpty()
    }

    @Test
    fun `the provisional client id is never accepted as the issued one`() {
        val f = flow()
        val start = runBlocking { f.begin() }
        val failure = failure { f.complete(callback(stateOf(start.url), "code=CODE&client_id=dynamic_agent_client")) }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `a reauthorization callback carrying a different client id is rejected`() {
        store.put(ProviderIds.OPENAI, oauth(clientId = "oaiapp_1"))
        val f = flow()
        val start = runBlocking { f.begin() }
        val failure = failure { f.complete(callback(stateOf(start.url), "code=CODE&client_id=oaiapp_OUTRO")) }
        assertThat(failure.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
        assertThat(openai.requestsTo("/oauth/token")).isEmpty()
    }

    @Test
    fun `a reauthorization callback may omit the client id and reuses the saved one`() {
        store.put(ProviderIds.OPENAI, oauth(clientId = "oaiapp_1", subject = "sub-1"))
        val f = flow()
        val start = runBlocking { f.begin() }
        openai.tokenResponses += openai.tokenOk(idToken = openai.signer.jwt(idTokenPayload(nonce = nonceOf(start.url))))
        val credential = runBlocking { f.complete(URI("$redirect?code=CODE&state=${stateOf(start.url)}")) }
        assertThat(credential.clientId).isEqualTo("oaiapp_1")
    }

    @Test
    fun `the token exchange is form encoded with the grant, code, verifier, same redirect uri and resource — and no client secret`() {
        val f = flow()
        val start = runBlocking { f.begin() }
        openai.tokenResponses += openai.tokenOk(idToken = openai.signer.jwt(idTokenPayload(nonce = nonceOf(start.url))))
        runBlocking { f.complete(callback(stateOf(start.url), "code=O-CODIGO&client_id=oaiapp_1")) }

        val request = openai.requestsTo("/oauth/token").single()
        val form = request.form()
        assertThat(request.getHeader("Content-Type")).contains("application/x-www-form-urlencoded")
        assertThat(form["grant_type"]).isEqualTo("authorization_code")
        assertThat(form["client_id"]).isEqualTo("oaiapp_1")      // o EMITIDO, não o provisório
        assertThat(form["code"]).isEqualTo("O-CODIGO")
        assertThat(form["redirect_uri"]).isEqualTo(redirect)      // a mesma URI exata da autorização
        assertThat(form["resource"]).isEqualTo("https://api.openai.com/v1")
        assertThat(form.keys).doesNotContain("client_secret")
        // O verifier enviado é o que gerou o challenge da URL de autorização (PKCE de verdade, ponta a ponta).
        assertThat(Pkce.challengeS256(form["code_verifier"]!!)).isEqualTo(start.url.toHttpUrl().queryParameter("code_challenge"))
    }

    @Test
    fun `an invalid grant on the exchange discards the code and a fresh attempt works`() {
        val f = flow()
        val start = runBlocking { f.begin() }
        openai.tokenResponses += openai.tokenError(400, "invalid_grant")

        val failure = failure { f.complete(callback(stateOf(start.url))) }

        assertThat(failure.kind).isEqualTo(AuthErrorKind.INVALID_GRANT)
        receiver = FakeReceiver(redirect)
        assertThat(loginOk(f).clientId).isEqualTo("oaiapp_1") // recomeça do zero
    }

    @Test
    fun `it saves the issued client id with the verified subject, email, scopes, expiry and earliest refresh`() {
        val credential = loginOk()

        assertThat(credential.accessToken).isEqualTo("access-1")
        assertThat(credential.refreshToken).isEqualTo("refresh-1")
        assertThat(credential.clientId).isEqualTo("oaiapp_1")
        assertThat(credential.subject).isEqualTo("sub-1")
        assertThat(credential.account).isEqualTo("pessoa@exemplo.com")
        assertThat(credential.scopes).contains("chatgpt.tokens.use.direct")
        assertThat(credential.expiresAtMs).isEqualTo(NOW_MS + 3_600_000)
        assertThat(credential.earliestRefreshAtMs).isEqualTo((NOW_SECONDS + 3_000) * 1000)
        assertThat(credential.idToken).isNotEmpty()
    }

    @Test
    fun `an id token with the wrong nonce, issuer or an expired one is rejected and no credential comes out`() {
        listOf(
            { _: String -> openai.signer.jwt(idTokenPayload(nonce = "outro")) },
            { n: String -> openai.signer.jwt(idTokenPayload(nonce = n, issuer = "https://outro.test")) },
            { n: String -> openai.signer.jwt(idTokenPayload(nonce = n, expSeconds = NOW_SECONDS - 1_000)) },
        ).forEach { makeToken ->
            receiver = FakeReceiver(redirect)
            val f = flow()
            val start = runBlocking { f.begin() }
            openai.tokenResponses += openai.tokenOk(idToken = makeToken(nonceOf(start.url)))
            assertThat(failure { f.complete(callback(stateOf(start.url))) }.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
        }
    }

    @Test
    fun `a token response with no id token is rejected — a valid access token alone proves no identity`() {
        val f = flow()
        val start = runBlocking { f.begin() }
        openai.tokenResponses += openai.tokenOk(idToken = null)
        assertThat(failure { f.complete(callback(stateOf(start.url))) }.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `without the plan scope the login still works and the provider says plan usage is off`() {
        val f = flow()
        val start = runBlocking { f.begin() }
        openai.tokenResponses += openai.tokenOk(
            idToken = openai.signer.jwt(idTokenPayload(nonce = nonceOf(start.url))), scope = "openid profile email offline_access",
        )
        val credential = runBlocking { f.complete(callback(stateOf(start.url))) }

        assertThat(credential.scopes).doesNotContain("chatgpt.tokens.use.direct")
        assertThat(OpenAiProvider.planUsage(credential)).isFalse()
        assertThat(OpenAiProvider.planUsage(loginOk(flow().also { receiver = FakeReceiver(redirect) }))).isTrue()
    }

    @Test
    fun `a reauthorization with a different account is refused before any credential is replaced`() {
        store.put(ProviderIds.OPENAI, oauth(clientId = "oaiapp_1", subject = "sub-1"))
        val f = flow()
        val start = runBlocking { f.begin() }
        openai.tokenResponses += openai.tokenOk(idToken = openai.signer.jwt(idTokenPayload(nonce = nonceOf(start.url), subject = "sub-2")))

        assertThat(failure { f.complete(callback(stateOf(start.url))) }.kind).isEqualTo(AuthErrorKind.DENIED)
    }

    @Test
    fun `a second begin invalidates the first attempt`() {
        val f = flow()
        val first = runBlocking { f.begin() }
        receiver = FakeReceiver(redirect)
        runBlocking { f.begin() }
        assertThat(failure { f.complete(callback(stateOf(first.url))) }.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    // ── refresh ───────────────────────────────────────────────────────────────────────────

    private val current get() = oauth(access = "velho", refresh = "refresh-velho", clientId = "oaiapp_1", scopes = setOf("openid"), idToken = "idt")

    @Test
    fun `refresh posts the grant, the issued client id, the refresh token and the resource — without scope`() {
        openai.tokenResponses += openai.tokenOk(access = "novo", refresh = "refresh-novo")
        runBlocking { flow().refresh(current) }

        val form = openai.requestsTo("/oauth/token").single().form()
        assertThat(form["grant_type"]).isEqualTo("refresh_token")
        assertThat(form["client_id"]).isEqualTo("oaiapp_1")      // o emitido, nunca o provisório
        assertThat(form["refresh_token"]).isEqualTo("refresh-velho")
        assertThat(form["resource"]).isEqualTo("https://api.openai.com/v1")
        assertThat(form.keys).doesNotContain("scope")             // omitir o scope mantém a concessão
        assertThat(form.keys).doesNotContain("client_secret")
    }

    @Test
    fun `refresh replaces access, expiry, scopes and the rotating refresh token together, keeping the registration`() {
        openai.tokenResponses += openai.tokenOk(access = "novo", refresh = "refresh-novo", expiresIn = 1_800, earliest = NOW_SECONDS + 900)

        val renewed = runBlocking { flow().refresh(current) }

        assertThat(renewed.accessToken).isEqualTo("novo")
        assertThat(renewed.refreshToken).isEqualTo("refresh-novo")
        assertThat(renewed.expiresAtMs).isEqualTo(NOW_MS + 1_800_000)
        assertThat(renewed.earliestRefreshAtMs).isEqualTo((NOW_SECONDS + 900) * 1000)
        assertThat(renewed.scopes).contains("chatgpt.tokens.use.direct")
        assertThat(renewed.clientId).isEqualTo("oaiapp_1")
        assertThat(renewed.subject).isEqualTo("sub-1")
        assertThat(renewed.idToken).isEqualTo("idt")              // sem id_token novo, o retido fica
    }

    @Test
    fun `every unusable refresh error code maps to invalid grant`() {
        listOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused").forEach { code ->
            openai.tokenResponses += openai.tokenError(400, code)
            assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.INVALID_GRANT)
        }
    }

    /**
     * Review, Important 3: a doc não define a forma do corpo de erro. Um código inutilizável dentro de `{"error":{"code":…}}` ou solto
     * em `{"code":…}` também vale — senão a conexão fica "Conectada" e morta, repetindo uma renovação que nunca vai passar.
     */
    @Test
    fun `an unusable code is recognised in an error object and in a top level code`() {
        listOf("invalid_grant", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused").forEach { code ->
            openai.tokenResponses += openai.tokenErrorObject(400, code)
            assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.INVALID_GRANT)
            openai.tokenResponses += openai.tokenErrorTopLevelCode(400, code)
            assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.INVALID_GRANT)
        }
    }

    @Test
    fun `an invalid client in an error object is still a configuration error`() {
        openai.tokenResponses += openai.tokenErrorObject(401, "invalid_client")
        assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.DENIED)
    }

    /** Um 401 da renovação com um código que ninguém conhece: as credenciais foram rejeitadas — pedir login, não repetir para sempre. */
    @Test
    fun `a 401 on refresh with an unknown code means the credentials were rejected`() {
        openai.tokenResponses += openai.tokenError(401, "algo_novo")
        assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.INVALID_GRANT)
    }

    /** …mas um 400 desconhecido pode ser um pedido nosso malformado: não se apaga o login por isso. */
    @Test
    fun `a 400 with an unknown code is an invalid response and never erases the login`() {
        openai.tokenResponses += openai.tokenError(400, "algo_novo")
        assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.INVALID_RESPONSE)
    }

    @Test
    fun `an invalid client is a configuration error, not a reason to erase the login`() {
        openai.tokenResponses += openai.tokenError(401, "invalid_client")
        assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.DENIED)
    }

    @Test
    fun `a server error or a network failure on refresh is temporary`() {
        openai.tokenResponses += MockResponseFactory.status(503)
        assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.NETWORK)
        openai.shutdown()
        assertThat(failure { flow().refresh(current) }.kind).isEqualTo(AuthErrorKind.NETWORK)
    }

    @Test
    fun `refreshing with no saved client id or refresh token is unusable`() {
        assertThat(failure { flow().refresh(current.copy(clientId = null)) }.kind).isEqualTo(AuthErrorKind.INVALID_GRANT)
        assertThat(failure { flow().refresh(current.copy(refreshToken = null)) }.kind).isEqualTo(AuthErrorKind.INVALID_GRANT)
    }

    @Test
    fun `earliest refresh at reads epoch seconds and ISO 8601 and ignores garbage`() {
        assertThat(OpenAiChatGptFlow.parseEarliest(1_800_000_000)).isEqualTo(1_800_000_000_000)
        assertThat(OpenAiChatGptFlow.parseEarliest(1_800_000_000L)).isEqualTo(1_800_000_000_000)
        assertThat(OpenAiChatGptFlow.parseEarliest("2027-01-15T08:00:00Z")).isEqualTo(1_800_000_000_000)
        assertThat(OpenAiChatGptFlow.parseEarliest("1800000000")).isEqualTo(1_800_000_000_000)
        assertThat(OpenAiChatGptFlow.parseEarliest("amanhã")).isNull()
        assertThat(OpenAiChatGptFlow.parseEarliest(null)).isNull()
        assertThat(OpenAiChatGptFlow.parseEarliest(-5)).isNull()
    }

    // ── revoke ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `revoke posts the refresh token, its hint and the client id to the discovered endpoint`() {
        runBlocking { flow().revoke(current) }

        val request = openai.requestsTo("/oauth/revoke").single()
        val form = request.form()
        assertThat(form["token"]).isEqualTo("refresh-velho")
        assertThat(form["token_type_hint"]).isEqualTo("refresh_token")
        assertThat(form["client_id"]).isEqualTo("oaiapp_1")
        assertThat(request.getHeader("Content-Type")).contains("application/x-www-form-urlencoded")
    }

    @Test
    fun `a failing revocation is retried with backoff and then reported, so the user can be told it was not confirmed`() {
        repeat(3) { openai.revokeResponses += MockResponseFactory.status(503) }

        val failure = failure { flow().revoke(current) }

        assertThat(failure.kind).isEqualTo(AuthErrorKind.NETWORK)
        assertThat(openai.requestsTo("/oauth/revoke")).hasSize(3)
        assertThat(pauses).hasSize(2)
        assertThat(pauses[1]).isGreaterThan(pauses[0]) // backoff
    }

    @Test
    fun `a revocation that succeeds on the retry is fine`() {
        openai.revokeResponses += MockResponseFactory.status(503)
        runBlocking { flow().revoke(current) }
        assertThat(openai.requestsTo("/oauth/revoke")).hasSize(2)
    }

    @Test
    fun `revoking an api key or a skeleton never calls the network`() {
        runBlocking { flow().revoke(Credential.ApiKey("sk")) }
        runBlocking { flow().revoke(current.copy(refreshToken = null)) }
        assertThat(openai.requestsTo("/oauth/revoke")).isEmpty()
    }
}

/** Respostas HTTP sem corpo para os cenários de "servidor caiu". */
private object MockResponseFactory {
    fun status(code: Int) = okhttp3.mockwebserver.MockResponse().setResponseCode(code)
}
