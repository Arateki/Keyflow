package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.auth.AuthErrorKind
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.Availability
import com.keyflow.auth.ConnectionState
import com.keyflow.auth.UnavailableAuthReason
import com.keyflow.auth.fake.FakeApiKeyFlow
import com.keyflow.auth.fake.FakeOAuthFlow
import org.junit.Test

/** RSC-03, RCL-01/07, AUTH-03/07: o que o painel da OpenAI mostra, decidido por uma função pura — a UI só desenha. */
class OpenAiPanelStateTest {

    private val both = listOf(FakeApiKeyFlow(), FakeOAuthFlow())
    private fun state(connection: ConnectionState, flows: List<com.keyflow.auth.AuthFlow> = both) = openAiPanelState(connection, flows)

    @Test
    fun `disconnected offers the login and the key`() {
        val s = state(ConnectionState.Disconnected)
        assertThat(s.status).isEqualTo(OpenAiStatus.NOT_CONNECTED)
        assertThat(s.login).isEqualTo(LoginButton(enabled = true, disabledReason = null, again = false))
        assertThat(s.showKeyField).isTrue()
        assertThat(s.showDisconnect).isFalse()
        assertThat(s.showCancel).isFalse()
    }

    @Test
    fun `connecting disables the login and offers to cancel`() {
        val s = state(ConnectionState.Connecting)
        assertThat(s.status).isEqualTo(OpenAiStatus.CONNECTING)
        assertThat(s.login!!.enabled).isFalse()
        assertThat(s.showCancel).isTrue()
    }

    @Test
    fun `connected by login with the plan shows the plan in use`() {
        val s = state(ConnectionState.Connected("eu@exemplo.com", AuthMethod.OAUTH, planUsage = true))
        assertThat(s.status).isEqualTo(OpenAiStatus.CONNECTED_LOGIN)
        assertThat(s.account).isEqualTo("eu@exemplo.com")
        assertThat(s.planInUse).isTrue()
        assertThat(s.planMissing).isFalse()
        assertThat(s.showDisconnect).isTrue()
    }

    @Test
    fun `connected by login WITHOUT the plan scope warns and still offers the key`() {
        val s = state(ConnectionState.Connected("eu@exemplo.com", AuthMethod.OAUTH, planUsage = false))
        assertThat(s.status).isEqualTo(OpenAiStatus.CONNECTED_LOGIN)
        assertThat(s.planInUse).isFalse()
        assertThat(s.planMissing).isTrue()
        assertThat(s.showKeyField).isTrue()
    }

    @Test
    fun `connected by api key never talks about the plan`() {
        val s = state(ConnectionState.Connected(null, AuthMethod.API_KEY))
        assertThat(s.status).isEqualTo(OpenAiStatus.CONNECTED_KEY)
        assertThat(s.planInUse).isFalse()
        assertThat(s.planMissing).isFalse()
        assertThat(s.showDisconnect).isTrue()
    }

    @Test
    fun `needs reconnect offers to sign in again and to disconnect`() {
        val s = state(ConnectionState.NeedsReconnect)
        assertThat(s.status).isEqualTo(OpenAiStatus.NEEDS_RECONNECT)
        assertThat(s.login!!.again).isTrue()
        assertThat(s.login!!.enabled).isTrue()
        assertThat(s.showDisconnect).isTrue()
    }

    @Test
    fun `an error keeps its kind and leaves the login available to try again`() {
        val s = state(ConnectionState.Error(AuthErrorKind.TIMEOUT))
        assertThat(s.status).isEqualTo(OpenAiStatus.ERROR)
        assertThat(s.error).isEqualTo(AuthErrorKind.TIMEOUT)
        assertThat(s.login!!.enabled).isTrue()
    }

    /**
     * Review, Important 6: o estado `Error` esconde a credencial guardada (um login que falhou por cima de uma conexão que existia).
     * A tela nunca pode ficar sem saída: Desconectar continua ali — e limpa o erro e o que estiver no cofre.
     */
    @Test
    fun `an error always offers to disconnect, because a credential may still be stored`() {
        AuthErrorKind.entries.forEach { kind ->
            assertThat(state(ConnectionState.Error(kind)).showDisconnect).isTrue()
        }
    }

    /** Entrar com OUTRA conta volta como acesso negado: a mensagem diz o caminho (desconectar antes) em vez de girar em círculo. */
    @Test
    fun `the denied message tells how to switch accounts`() {
        assertThat(openAiErrorMessage(AuthErrorKind.DENIED)).contains("Desconectar")
    }

    @Test
    fun `an unavailable login is disabled with its reason — and the key still works`() {
        val blocked = listOf(FakeApiKeyFlow(), FakeOAuthFlow(availability = Availability.Unavailable(UnavailableAuthReason.PROVIDER_DISABLED)))
        val s = state(ConnectionState.Disconnected, blocked)
        assertThat(s.login).isEqualTo(LoginButton(enabled = false, disabledReason = UnavailableAuthReason.PROVIDER_DISABLED, again = false))
        assertThat(s.showKeyField).isTrue()
    }

    /** AUTH-03: os controles vêm do que o REGISTRO oferece — sem fluxo de login, não há botão; sem fluxo de chave, não há campo. */
    @Test
    fun `the controls come from the flows the registry offers`() {
        val keyOnly = state(ConnectionState.Disconnected, listOf(FakeApiKeyFlow()))
        assertThat(keyOnly.login).isNull()
        assertThat(keyOnly.showKeyField).isTrue()

        val loginOnly = state(ConnectionState.Disconnected, listOf(FakeOAuthFlow()))
        assertThat(loginOnly.login).isNotNull()
        assertThat(loginOnly.showKeyField).isFalse()

        val nothing = state(ConnectionState.Disconnected, emptyList())
        assertThat(nothing.login).isNull()
        assertThat(nothing.showKeyField).isFalse()
    }

    @Test
    fun `every auth error kind has a readable message and no two share one`() {
        val messages = AuthErrorKind.entries.map { openAiErrorMessage(it) }
        assertThat(messages.all { it.isNotBlank() }).isTrue()
        assertThat(messages.map { it.lowercase() }).containsNoDuplicates()
    }

    @Test
    fun `every unavailable reason has a readable message`() {
        UnavailableAuthReason.entries.forEach { assertThat(openAiUnavailableMessage(it).isNotBlank()).isTrue() }
    }
}
