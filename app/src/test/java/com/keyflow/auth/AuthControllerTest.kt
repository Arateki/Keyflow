package com.keyflow.auth

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.fake.FakeApiKeyFlow
import com.keyflow.auth.fake.FakeBrowser
import com.keyflow.auth.fake.FakeCredentialStore
import com.keyflow.auth.fake.FakeOAuthFlow
import com.keyflow.auth.fake.oauth
import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** AUTH-03/07: o que a tela pede (conectar, guardar chave, cancelar, desconectar) e o único estado que ela vê. */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthControllerTest {

    private val provider = ProviderId("fake")
    private val store = FakeCredentialStore()
    private val browser = FakeBrowser()
    private val oauthFlow = FakeOAuthFlow()
    private val keyFlow = FakeApiKeyFlow()

    private fun TestScope.controller(
        planUsage: (Credential.OAuth) -> Boolean = { true },
        flows: List<AuthFlow> = listOf(keyFlow, oauthFlow),
    ) = AuthController(
        registry = listOf(ProviderAuth(provider, "Fake", flows, headersFor = { emptyMap() }, planUsage = planUsage)),
        store = store,
        browser = browser,
        scope = backgroundScope,
        clock = { currentTime },
    )

    private suspend fun AuthController.current() = state(provider).first()

    @Test
    fun `nothing stored is disconnected`() = runTest {
        assertThat(controller().current()).isEqualTo(ConnectionState.Disconnected)
    }

    @Test
    fun `saving an api key stores it and reads as connected by key`() = runTest {
        val c = controller()
        c.saveApiKey(provider, "  sk-teste  ")
        assertThat(store.get(provider)).isEqualTo(Credential.ApiKey("sk-teste")) // sem os espaços do colar
        assertThat(c.current()).isEqualTo(ConnectionState.Connected(account = null, method = AuthMethod.API_KEY))
    }

    @Test
    fun `saving a blank api key clears instead of storing an empty one`() = runTest {
        val c = controller()
        c.saveApiKey(provider, "sk-teste")
        c.saveApiKey(provider, "   ")
        assertThat(store.get(provider)).isNull()
        assertThat(c.current()).isEqualTo(ConnectionState.Disconnected)
    }

    @Test
    fun `connect runs begin, opens the browser, waits for the redirect, completes and stores`() = runTest {
        val c = controller()
        oauthFlow.completeResult = oauth(access = "novo", account = "eu@exemplo.com")

        c.connect(provider, AuthMethod.OAUTH)
        runCurrent()
        assertThat(browser.opened).containsExactly("https://fake.test/authorize")
        assertThat(c.current()).isEqualTo(ConnectionState.Connecting) // esperando o navegador voltar

        oauthFlow.receiver.uri.complete(URI("http://127.0.0.1:1/callback?code=abc&state=xyz"))
        runCurrent()

        assertThat((store.get(provider) as Credential.OAuth).accessToken).isEqualTo("novo")
        assertThat(c.current()).isEqualTo(ConnectionState.Connected("eu@exemplo.com", AuthMethod.OAUTH, planUsage = true))
        assertThat(oauthFlow.receiver.closed).isTrue() // o socket de loopback não fica aberto
    }

    @Test
    fun `cancelling while waiting closes the receiver and goes back to disconnected`() = runTest {
        val c = controller()
        c.connect(provider, AuthMethod.OAUTH)
        runCurrent()
        assertThat(c.current()).isEqualTo(ConnectionState.Connecting)

        c.cancel(provider)
        runCurrent()

        assertThat(oauthFlow.receiver.closed).isTrue()
        assertThat(c.current()).isEqualTo(ConnectionState.Disconnected)
        assertThat(store.get(provider)).isNull()
    }

    @Test
    fun `a redirect timeout is an error and stores nothing`() = runTest {
        val c = controller()
        c.connect(provider, AuthMethod.OAUTH)
        runCurrent()
        // o receptor de teste dá timeout: ninguém completa o uri e o tempo passa
        testScheduler.advanceTimeBy(AuthController.REDIRECT_TIMEOUT_MS + 1)
        runCurrent()

        assertThat(c.current()).isEqualTo(ConnectionState.Error(AuthErrorKind.TIMEOUT))
        assertThat(store.get(provider)).isNull()
        assertThat(oauthFlow.receiver.closed).isTrue()
    }

    @Test
    fun `a failure completing the login is an error and stores nothing`() = runTest {
        val c = controller()
        oauthFlow.completeError = AuthException(AuthErrorKind.DENIED, "access_denied")
        c.connect(provider, AuthMethod.OAUTH)
        runCurrent()
        oauthFlow.receiver.uri.complete(URI("http://127.0.0.1:1/callback?error=access_denied"))
        runCurrent()

        assertThat(c.current()).isEqualTo(ConnectionState.Error(AuthErrorKind.DENIED))
        assertThat(store.get(provider)).isNull()
    }

    @Test
    fun `a new attempt clears the previous error`() = runTest {
        val c = controller()
        oauthFlow.completeError = AuthException(AuthErrorKind.DENIED, "access_denied")
        c.connect(provider, AuthMethod.OAUTH); runCurrent()
        oauthFlow.receiver.uri.complete(URI("http://127.0.0.1:1/cb")); runCurrent()
        assertThat(c.current()).isInstanceOf(ConnectionState.Error::class.java)

        c.connect(provider, AuthMethod.OAUTH); runCurrent()

        assertThat(c.current()).isEqualTo(ConnectionState.Connecting)
    }

    @Test
    fun `disconnect revokes, clears and reads as disconnected — even if the revocation fails`() = runTest {
        val c = controller()
        store.put(provider, oauth())
        oauthFlow.revokeError = RuntimeException("sem rede")

        c.disconnect(provider)
        runCurrent()

        assertThat(oauthFlow.revoked).hasSize(1)
        assertThat(store.get(provider)).isNull()
        assertThat(c.current()).isEqualTo(ConnectionState.Disconnected)
    }

    @Test
    fun `tokens cleared but the registration kept read as needs reconnect`() = runTest {
        store.put(provider, oauth(access = "", refresh = null, expiresAtMs = 0, scopes = emptySet()))
        assertThat(controller().current()).isEqualTo(ConnectionState.NeedsReconnect)
    }

    @Test
    fun `plan usage follows the provider's own rule`() = runTest {
        store.put(provider, oauth(scopes = setOf("openid")))
        val withPlan = controller(planUsage = { "plano" in it.scopes })
        assertThat(withPlan.current()).isEqualTo(ConnectionState.Connected("pessoa@exemplo.com", AuthMethod.OAUTH, planUsage = false))
    }

    @Test
    fun `an unavailable flow is refused with a denied error and never opens the browser`() = runTest {
        val blocked = FakeOAuthFlow(availability = Availability.Unavailable(UnavailableAuthReason.PROVIDER_DISABLED))
        val c = controller(flows = listOf(blocked))

        c.connect(provider, AuthMethod.OAUTH)
        runCurrent()

        assertThat(browser.opened).isEmpty()
        assertThat(blocked.beginCalls).isEqualTo(0)
        assertThat(c.current()).isEqualTo(ConnectionState.Error(AuthErrorKind.DENIED))
    }
}
