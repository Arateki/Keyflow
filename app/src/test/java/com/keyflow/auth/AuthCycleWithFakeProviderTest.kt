package com.keyflow.auth

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.fake.FakeApiKeyFlow
import com.keyflow.auth.fake.FakeBrowser
import com.keyflow.auth.fake.FakeCredentialStore
import com.keyflow.auth.fake.FakeOAuthFlow
import com.keyflow.auth.fake.FakeProvider
import com.keyflow.auth.fake.FakeReasoningEngine
import com.keyflow.auth.fake.oauth
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningRequest
import java.net.URI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * AUTH-06: o ciclo inteiro — registro → botões → conectar → guardar → expirar → renovar → usar → desconectar — com um provedor
 * inventado, sem importar nada de `providers/openai`. Se este teste precisar mudar para acomodar um provedor novo, o
 * isolamento quebrou: conserte o núcleo, não o teste.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthCycleWithFakeProviderTest {

    private val store = FakeCredentialStore()
    private val browser = FakeBrowser()
    private val oauthFlow = FakeOAuthFlow()
    private val keyFlow = FakeApiKeyFlow()
    private val provider = FakeProvider.id

    private fun TestScope.registry(vararg flows: AuthFlow) = listOf(FakeProvider.auth(*flows))
    private fun TestScope.controller(registry: List<ProviderAuth>) =
        AuthController(registry, store, browser, backgroundScope) { currentTime }
    private fun TestScope.authorization(registry: List<ProviderAuth>) =
        DefaultAuthorizationProvider(registry, store, { currentTime })

    @Test
    fun `the registry lists the fake provider and the buttons come from its flows`() = runTest {
        val registry = registry(keyFlow, oauthFlow)

        assertThat(registry.map { it.id }).containsExactly(provider)
        assertThat(registry.single().flows.map { it.method }).containsExactly(AuthMethod.API_KEY, AuthMethod.OAUTH).inOrder()
        assertThat(registry.single().flows.map { it.availability }.toSet()).containsExactly(Availability.Available)
    }

    @Test
    fun `an unavailable flow is listed with its reason and cannot connect`() = runTest {
        val blocked = FakeOAuthFlow(availability = Availability.Unavailable(UnavailableAuthReason.PROVIDER_DISABLED))
        val registry = registry(keyFlow, blocked)
        val controller = controller(registry)

        assertThat(registry.single().flows.last().availability)
            .isEqualTo(Availability.Unavailable(UnavailableAuthReason.PROVIDER_DISABLED))
        controller.connect(provider, AuthMethod.OAUTH)
        runCurrent()

        assertThat(controller.state(provider).first()).isEqualTo(ConnectionState.Error(AuthErrorKind.DENIED))
        assertThat(browser.opened).isEmpty()
    }

    @Test
    fun `the whole oauth cycle — connect, use, expire, refresh, use again, disconnect`() = runTest {
        val registry = registry(keyFlow, oauthFlow)
        val controller = controller(registry)
        val engine = FakeReasoningEngine(authorization(registry))
        oauthFlow.completeResult = oauth(access = "t1", expiresAtMs = 3_600_000, account = "eu@exemplo.com")

        // 1. conectar
        controller.connect(provider, AuthMethod.OAUTH); runCurrent()
        assertThat(controller.state(provider).first()).isEqualTo(ConnectionState.Connecting)
        oauthFlow.receiver.uri.complete(URI("http://127.0.0.1:1/callback?code=c&state=s")); runCurrent()
        assertThat(controller.state(provider).first()).isEqualTo(ConnectionState.Connected("eu@exemplo.com", AuthMethod.OAUTH))

        // 2. usar (o cabeçalho é do provedor fake, não do núcleo)
        assertThat(engine.generate(ReasoningRequest(user = "oi")).toList())
            .containsExactly(ReasoningEvent.Delta("t1|oi"), ReasoningEvent.Done).inOrder()

        // 3. expirar → o uso seguinte renova sozinho e o novo token é o que vai no cabeçalho
        advanceTimeBy(3_600_000 - 30_000) // dentro da margem de renovação
        val renewed = engine.generate(ReasoningRequest(user = "de novo")).toList()
        assertThat(oauthFlow.refreshCalls).isEqualTo(1)
        assertThat(renewed.first()).isEqualTo(ReasoningEvent.Delta("t1+|de novo"))
        assertThat((store.get(provider) as Credential.OAuth).refreshToken).isEqualTo("rotated")

        // 4. desconectar: revoga, apaga, e o uso seguinte diz "não conectado"
        controller.disconnect(provider); runCurrent()
        assertThat(oauthFlow.revoked).hasSize(1)
        assertThat(store.get(provider)).isNull()
        assertThat(controller.state(provider).first()).isEqualTo(ConnectionState.Disconnected)
        assertThat(engine.generate(ReasoningRequest(user = "x")).toList())
            .containsExactly(ReasoningEvent.Error(ReasoningError.UNAUTHORIZED, "NOT_CONNECTED"))
    }

    @Test
    fun `an api key is stored, used with the provider's own header and cleared`() = runTest {
        val registry = registry(keyFlow, oauthFlow)
        val controller = controller(registry)
        val engine = FakeReasoningEngine(authorization(registry))

        controller.saveApiKey(provider, "chave-123")

        assertThat(controller.state(provider).first()).isEqualTo(ConnectionState.Connected(null, AuthMethod.API_KEY))
        assertThat(engine.generate(ReasoningRequest(user = "oi")).toList().first()).isEqualTo(ReasoningEvent.Delta("chave-123|oi"))

        controller.disconnect(provider); runCurrent()
        assertThat(store.get(provider)).isNull()
        assertThat(keyFlow.revoked).hasSize(1)
    }

    @Test
    fun `a dead refresh token surfaces as needs reconnect through the whole stack`() = runTest {
        val registry = registry(keyFlow, oauthFlow)
        val controller = controller(registry)
        val engine = FakeReasoningEngine(authorization(registry))
        store.put(provider, oauth(expiresAtMs = 30_000))
        oauthFlow.refreshError = AuthException(AuthErrorKind.INVALID_GRANT, "refresh_token_invalidated")

        val events = engine.generate(ReasoningRequest(user = "oi")).toList()

        assertThat(events).containsExactly(ReasoningEvent.Error(ReasoningError.UNAUTHORIZED, "NEEDS_RECONNECT"))
        assertThat(controller.state(provider).first()).isEqualTo(ConnectionState.NeedsReconnect)
    }

    @Test
    fun `two providers never mix their credentials`() = runTest {
        val other = ProviderId("outro")
        val registry = listOf(FakeProvider.auth(keyFlow), ProviderAuth(other, "Outro", listOf(FakeApiKeyFlow()), headersFor = { emptyMap() }))
        val controller = controller(registry)

        controller.saveApiKey(provider, "so-do-fake")

        assertThat(controller.state(provider).first()).isInstanceOf(ConnectionState.Connected::class.java)
        assertThat(controller.state(other).first()).isEqualTo(ConnectionState.Disconnected)
        assertThat(store.get(other)).isNull()
    }
}
