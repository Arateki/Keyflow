package com.keyflow.auth.fake

import com.keyflow.auth.ApiKeyFlow
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthException
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.AuthStart
import com.keyflow.auth.Availability
import com.keyflow.auth.BrowserLauncher
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.OAuthFlow
import com.keyflow.auth.ProviderId
import com.keyflow.auth.RedirectReceiver
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** Cofre em memória: mesmas regras do de verdade (`clear` não apaga o `meta`), com falha de gravação sob demanda. */
class FakeCredentialStore : CredentialStore {
    private val creds = MutableStateFlow<Map<ProviderId, Credential>>(emptyMap())
    private val metas = mutableMapOf<Pair<ProviderId, String>, String>()
    val putLog = mutableListOf<Credential>()
    var failNextPut: Throwable? = null

    override fun get(provider: ProviderId): Credential? = creds.value[provider]

    override fun put(provider: ProviderId, credential: Credential) {
        failNextPut?.let { failNextPut = null; throw it }
        putLog += credential
        creds.value = creds.value + (provider to credential)
    }

    override fun clear(provider: ProviderId) {
        creds.value = creds.value - provider
    }

    override fun changes(provider: ProviderId): Flow<Credential?> = creds.map { it[provider] }

    override fun meta(provider: ProviderId, key: String): String? = metas[provider to key]

    override fun putMeta(provider: ProviderId, key: String, value: String) {
        metas[provider to key] = value
    }
}

/** Receptor de redirect em memória: o teste completa o `uri` para "o navegador voltou". */
class FakeReceiver(override val redirectUri: String = "http://127.0.0.1:1/callback") : RedirectReceiver {
    val uri = CompletableDeferred<URI>()
    @Volatile var closed = false

    override suspend fun await(timeoutMs: Long): URI =
        withTimeoutOrNull(timeoutMs) { uri.await() } ?: throw AuthException(AuthErrorKind.TIMEOUT, "o navegador não voltou a tempo")

    override fun close() { closed = true }
}

class FakeBrowser : BrowserLauncher {
    val opened = mutableListOf<String>()
    override fun open(url: String) { opened += url }
}

/** Fluxo OAuth roteirizável: quantas renovações houve, quanto demoram, o que devolvem ou de que erro falham. */
class FakeOAuthFlow(
    override val availability: Availability = Availability.Available,
    /** Cada `begin()` cria um receptor NOVO (o navegador de cada tentativa) e o deixa aqui para o teste completar. */
    var receiver: FakeReceiver = FakeReceiver(),
) : OAuthFlow {
    override val method = AuthMethod.OAUTH
    var refreshCalls = 0
    var refreshDelayMs = 0L
    var refreshError: AuthException? = null
    // Uma renovação de verdade estende a validade: sem isso, quem esperava na fila ainda veria o token como "a renovar".
    var refreshResult: (Credential.OAuth) -> Credential.OAuth =
        { it.copy(accessToken = it.accessToken + "+", refreshToken = "rotated", expiresAtMs = it.expiresAtMs + 3_600_000) }
    var completeResult: Credential.OAuth = oauth()
    var completeError: AuthException? = null
    var beginCalls = 0
    var lastForceConsent: Boolean? = null
    val revoked = mutableListOf<Credential>()
    var revokeError: Throwable? = null

    override suspend fun begin(forceConsent: Boolean): AuthStart {
        beginCalls++
        lastForceConsent = forceConsent
        receiver = FakeReceiver()
        return AuthStart("https://fake.test/authorize", receiver)
    }

    override suspend fun complete(redirect: URI): Credential.OAuth {
        completeError?.let { throw it }
        return completeResult
    }

    override suspend fun refresh(current: Credential.OAuth): Credential.OAuth {
        refreshCalls++
        if (refreshDelayMs > 0) delay(refreshDelayMs)
        refreshError?.let { throw it }
        return refreshResult(current)
    }

    override suspend fun revoke(current: Credential) {
        revoked += current
        revokeError?.let { throw it }
    }
}

class FakeApiKeyFlow(override val availability: Availability = Availability.Available) : ApiKeyFlow {
    override val method = AuthMethod.API_KEY
    val revoked = mutableListOf<Credential>()
    override suspend fun revoke(current: Credential) { revoked += current }
}

fun oauth(
    access: String = "access1",
    refresh: String? = "refresh1",
    expiresAtMs: Long = 3_600_000,
    earliestRefreshAtMs: Long? = null,
    scopes: Set<String> = setOf("openid", "offline_access"),
    idToken: String? = "id-token-1",
    clientId: String? = "oaiapp_1",
    subject: String? = "sub-1",
    account: String? = "pessoa@exemplo.com",
) = Credential.OAuth(access, refresh, expiresAtMs, earliestRefreshAtMs, scopes, idToken, clientId, subject, account)
