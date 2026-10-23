package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.auth.CredentialMask
import com.keyflow.auth.ApiKeyFlow
import com.keyflow.auth.AuthFlow
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.AuthStart
import com.keyflow.auth.Availability
import com.keyflow.auth.ConnectionState
import com.keyflow.auth.Credential
import com.keyflow.auth.OAuthFlow
import com.keyflow.auth.UnavailableAuthReason
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.ui.theme.KeyFlowTheme
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RSC-03/04, RCL-01/06/07, AUTH-03/07 no aparelho: o painel da OpenAI desenhado a partir do REGISTRO e do estado da conexão,
 * com um [AuthActions] falso — a tela só pede (conectar, guardar a chave, desconectar) e só observa o [ConnectionState].
 */
@RunWith(AndroidJUnit4::class)
class OpenAiPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private class KeyFlow : ApiKeyFlow {
        override val method = AuthMethod.API_KEY
        override val availability: Availability = Availability.Available
        override suspend fun revoke(current: Credential) = Unit
    }

    private class LoginFlow(override val availability: Availability = Availability.Available) : OAuthFlow {
        override val method = AuthMethod.OAUTH
        override suspend fun begin(forceConsent: Boolean): AuthStart = error("não usado aqui")
        override suspend fun complete(redirect: URI): Credential.OAuth = error("não usado aqui")
        override suspend fun refresh(current: Credential.OAuth): Credential.OAuth = error("não usado aqui")
        override suspend fun revoke(current: Credential) = Unit
    }

    private class FakeAuth(
        override val flows: List<AuthFlow> = listOf(KeyFlow(), LoginFlow()),
        initial: ConnectionState = ConnectionState.Disconnected,
        override val usageUrl: String? = "https://exemplo.test/uso",
        var models: List<AccountModel> = emptyList(),
        var storedKey: String? = null,
        var noticeFirst: Boolean = true,
    ) : AuthActions {
        val connection = MutableStateFlow(initial)
        private val maskedKey = MutableStateFlow(CredentialMask.of(storedKey))
        val connects = mutableListOf<Pair<AuthMethod, Boolean>>()
        val savedKeys = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var cancels = 0
        var disconnects = 0
        var modelCalls = 0

        override fun state(): Flow<ConnectionState> = connection
        override fun connect(method: AuthMethod, forceConsent: Boolean) { connects += method to forceConsent }
        override fun cancel() { cancels++ }
        override fun disconnect() { disconnects++; storedKey = null; maskedKey.value = null; connection.value = ConnectionState.Disconnected }
        override fun saveApiKey(key: String) {
            savedKeys += key
            storedKey = key
            maskedKey.value = CredentialMask.of(key)
            connection.value = ConnectionState.Connected(null, AuthMethod.API_KEY)
        }
        override fun apiKey(): String? = maskedKey.value
        override fun apiKeyFlow(): Flow<String?> = maskedKey
        override fun consumeNotice(key: String): Boolean = noticeFirst.also { noticeFirst = false }
        override fun openUrl(url: String) { opened += url }
        override suspend fun listModels(): List<AccountModel> { modelCalls++; return models }
    }

    private inner class Harness(val auth: FakeAuth, initial: Settings = Settings(reasoningSource = ReasoningSource.OPENAI)) {
        var settings by mutableStateOf(initial)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                ReasoningPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    resolve = { ReasoningResolution.Unavailable(com.keyflow.reasoning.UnavailableReason.NOT_CONNECTED) },
                    auth = auth,
                )
            }
        }
    }

    private fun show(h: Harness) = rule.setContent { h.Content() }

    private fun text(tag: String) = rule.onNodeWithTag(tag).performScrollTo()

    /** O cartão abre na aba Assinatura (o login); o campo da chave e o "Desconectar" da chave moram na aba API. */
    private fun apiTab() = rule.onNodeWithTag(OPENAI_TAB_API_TAG).performScrollTo().performClick()

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    // ── os botões vêm do registro ─────────────────────────────────────────────────────────

    @Test
    fun disconnectedShowsTheLoginAndTheKeyFromTheRegistry() {
        show(Harness(FakeAuth()))

        text(OPENAI_STATUS_TAG).assertTextEquals("Não conectado")
        text(OPENAI_LOGIN_TAG).assertIsDisplayed()
        rule.onNodeWithTag(OPENAI_KEY_TAG).assertDoesNotExist() // a chave está na aba API
        rule.onNodeWithTag(OPENAI_DISCONNECT_TAG).assertDoesNotExist()
        // Sem conta o seletor mostra as listas de reserva do cartão, mas nenhum modelo vem escolhido: o motor só responde com um escolhido.
        text(OPENAI_MODEL_TAG).assertTextContains("Escolha um modelo", substring = true)
        text(RACIOCINIO_OPENAI_PRIVACY_TAG).assertIsDisplayed()

        apiTab()
        text(OPENAI_KEY_TAG).assertIsDisplayed()
        rule.onNodeWithTag(OPENAI_LOGIN_TAG).assertDoesNotExist()
        rule.onNodeWithTag(OPENAI_DISCONNECT_TAG).assertDoesNotExist()
        text(OPENAI_MODEL_TAG).assertTextContains("Escolha um modelo", substring = true)
    }

    /** AUTH-03: um registro sem login não tem o botão; um registro sem chave não tem o campo. */
    @Test
    fun theControlsAreBuiltFromTheFlowsTheRegistryOffers() {
        show(Harness(FakeAuth(flows = listOf(KeyFlow()))))
        text(OPENAI_KEY_TAG).assertIsDisplayed()
        rule.onNodeWithTag(OPENAI_LOGIN_TAG).assertDoesNotExist()
    }

    @Test
    fun aLoginTheRegistryDisablesIsShownDisabledWithItsReason() {
        show(Harness(FakeAuth(flows = listOf(KeyFlow(), LoginFlow(Availability.Unavailable(UnavailableAuthReason.PROVIDER_DISABLED))))))

        text(OPENAI_LOGIN_TAG).assertIsNotEnabled()
        text(OPENAI_LOGIN_REASON_TAG).assertIsDisplayed()
        apiTab()
        text(OPENAI_KEY_TAG).assertIsDisplayed() // a chave segue funcionando
    }

    @Test
    fun theLoginButtonUsesTheApprovedLabelAndAsksForTheOauthLogin() {
        val auth = FakeAuth()
        show(Harness(auth))

        text(OPENAI_LOGIN_TAG).assertTextContains("Continue with ChatGPT", substring = true).performClick()

        assertEquals(listOf(AuthMethod.OAUTH to false), auth.connects)
    }

    @Test
    fun connectingDisablesTheLoginAndOffersToCancel() {
        val auth = FakeAuth(initial = ConnectionState.Connecting)
        show(Harness(auth))

        text(OPENAI_LOGIN_TAG).assertIsNotEnabled()
        text(OPENAI_CANCEL_TAG).performClick()
        assertEquals(1, auth.cancels)
        rule.onNodeWithTag(OPENAI_KEY_TAG).assertDoesNotExist()
    }

    // ── chave de API ──────────────────────────────────────────────────────────────────────

    @Test
    fun aTypedKeyIsSavedAndTheStatusFollowsTheConnectionState() {
        val auth = FakeAuth()
        show(Harness(auth))

        apiTab()
        text(OPENAI_KEY_TAG).performTextInput("sk-teste-1234")
        text(OPENAI_STATUS_TAG).performClick() // tira o foco do campo: salva no desfoque

        rule.waitUntil(3_000) { auth.savedKeys.isNotEmpty() }
        assertEquals(listOf("sk-teste-1234"), auth.savedKeys)
        text(OPENAI_STATUS_TAG).assertTextEquals("Chave de API ativa")
        text(OPENAI_DISCONNECT_TAG).assertIsDisplayed()
    }

    /** A chave nunca aparece em claro: o campo é mascarado, e o texto de estado não a repete. */
    @Test
    fun theStoredKeyFillsTheFieldMaskedAndIsNeverShownInPlainText() {
        val auth = FakeAuth(initial = ConnectionState.Connected(null, AuthMethod.API_KEY), storedKey = "sk-segredo-999")
        show(Harness(auth))

        apiTab()
        val statusNode = rule.onNodeWithTag(OPENAI_STATUS_TAG).performScrollTo()
        statusNode.assertTextEquals("Chave de API ativa")
        // O texto editável de um campo Password não expõe a chave em claro na árvore de acessibilidade.
        rule.onNodeWithTag(OPENAI_KEY_TAG).performScrollTo().assertIsDisplayed()
        val shown = rule.onNodeWithTag(OPENAI_KEY_TAG).fetchSemanticsNode().config.toString()
        assertFalse("a chave apareceu em claro: $shown", shown.contains("sk-segredo-999"))
    }

    @Test
    fun disconnectAsksTheControllerAndClearsTheField() {
        val auth = FakeAuth(initial = ConnectionState.Connected(null, AuthMethod.API_KEY), storedKey = "sk-x")
        show(Harness(auth))

        text(OPENAI_DISCONNECT_TAG).performClick()

        rule.waitUntil(3_000) { auth.disconnects == 1 }
        text(OPENAI_STATUS_TAG).assertTextEquals("Não conectado")
        assertNull(auth.storedKey)
    }

    // ── login com o plano ─────────────────────────────────────────────────────────────────

    @Test
    fun connectedByLoginShowsTheAccountThePlanInUseAndManageUsage() {
        val auth = FakeAuth(initial = ConnectionState.Connected("eu@exemplo.com", AuthMethod.OAUTH, planUsage = true), noticeFirst = false)
        show(Harness(auth))

        text(OPENAI_STATUS_TAG).assertTextContains("eu@exemplo.com", substring = true)
        text(OPENAI_PLAN_TAG).assertTextEquals("Using ChatGPT plan")
        text(OPENAI_PLAN_NOTE_TAG).assertIsDisplayed() // distingue o plano das cobranças do app
        text(OPENAI_MANAGE_USAGE_TAG).assertTextEquals("Manage usage").performClick()
        assertEquals(listOf("https://exemplo.test/uso"), auth.opened)
        rule.onNodeWithTag(OPENAI_KEY_TAG).assertDoesNotExist() // com o plano em uso, não se troca por chave sem desconectar
    }

    @Test
    fun theFirstLoginWithThePlanShowsTheApprovedModalOnce() {
        val auth = FakeAuth(initial = ConnectionState.Connected("eu@exemplo.com", AuthMethod.OAUTH, planUsage = true), noticeFirst = true)
        show(Harness(auth))

        rule.waitUntil(3_000) { count(OPENAI_WELCOME_TAG) == 1 }
        rule.onNodeWithTag(OPENAI_WELCOME_GOT_IT_TAG).assertTextEquals("Got it").performClick()
        rule.waitUntil(3_000) { count(OPENAI_WELCOME_TAG) == 0 }
    }

    @Test
    fun theModalIsNotShownAgainAfterTheFirstTime() {
        val auth = FakeAuth(initial = ConnectionState.Connected("eu@exemplo.com", AuthMethod.OAUTH, planUsage = true), noticeFirst = false)
        show(Harness(auth))

        text(OPENAI_PLAN_TAG).assertIsDisplayed()
        rule.waitForIdle()
        rule.onNodeWithTag(OPENAI_WELCOME_TAG).assertDoesNotExist()
    }

    /** RCL-07: nunca esconder o motivo, e oferecer a chave OU reautorizar com `prompt=consent`. */
    @Test
    fun aLoginWithoutThePlanWarnsOffersTheKeyAndReauthorizesWithConsent() {
        val auth = FakeAuth(initial = ConnectionState.Connected("eu@exemplo.com", AuthMethod.OAUTH, planUsage = false))
        show(Harness(auth))

        text(OPENAI_PLAN_MISSING_TAG).assertIsDisplayed()
        rule.onNodeWithTag(OPENAI_PLAN_TAG).assertDoesNotExist()

        text(OPENAI_LOGIN_TAG).performClick()
        assertEquals(listOf(AuthMethod.OAUTH to true), auth.connects) // forceConsent

        // E a chave de API segue oferecida — na aba API.
        apiTab()
        text(OPENAI_KEY_TAG).assertIsDisplayed()
    }

    @Test
    fun needsReconnectOffersToSignInAgain() {
        val auth = FakeAuth(initial = ConnectionState.NeedsReconnect)
        show(Harness(auth))

        text(OPENAI_STATUS_TAG).assertTextContains("expirou", substring = true)
        text(OPENAI_LOGIN_TAG).performClick()
        text(OPENAI_DISCONNECT_TAG).assertIsDisplayed()
        assertEquals(listOf(AuthMethod.OAUTH to false), auth.connects)
    }

    @Test
    fun anErrorShowsItsMessageAndKeepsTheLoginToTryAgain() {
        show(Harness(FakeAuth(initial = ConnectionState.Error(com.keyflow.auth.AuthErrorKind.TIMEOUT))))

        text(OPENAI_STATUS_TAG).assertTextEquals("Não foi possível conectar")
        text(OPENAI_ERROR_TAG).assertIsDisplayed()
        text(OPENAI_LOGIN_TAG).assertIsDisplayed()
        text(OPENAI_DISCONNECT_TAG).assertIsDisplayed() // a credencial pode estar no cofre: sempre há saída
    }

    // ── modelo da conta ───────────────────────────────────────────────────────────────────

    @Test
    fun theModelDropdownListsTheAccountModelsAndSavesTheChoice() {
        val auth = FakeAuth(
            initial = ConnectionState.Connected(null, AuthMethod.API_KEY),
            models = listOf(AccountModel("gpt-a", "Modelo A"), AccountModel("gpt-b", "Modelo B")),
        )
        val h = Harness(auth)
        show(h)

        apiTab() // a conexão é por chave
        rule.waitUntil(3_000) { auth.modelCalls >= 1 }
        text(OPENAI_MODEL_TAG).assertTextContains("Escolha um modelo", substring = true).performClick()
        rule.onNodeWithText("Modelo B").performClick()

        rule.waitUntil(3_000) { h.settings.reasoningOpenAiModel == "gpt-b" }
        text(OPENAI_MODEL_TAG).assertTextContains("Modelo B", substring = true)
    }

    /** RCL-06: falhou a lista → o modelo que já estava escolhido continua aparecendo, e "Atualizar" tenta de novo. */
    @Test
    fun whenTheListFailsTheChosenModelStaysAndRefreshTriesAgain() {
        val auth = FakeAuth(initial = ConnectionState.Connected(null, AuthMethod.API_KEY), models = emptyList())
        show(Harness(auth, Settings(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = "gpt-guardado")))

        apiTab() // a conexão é por chave
        // O painel lista na composição (sem conexão ainda) e de novo quando a conexão chega: espera a falha da lista da conta aparecer.
        rule.waitUntil(3_000) { count(OPENAI_MODELS_EMPTY_TAG) == 1 }
        val callsBeforeRefresh = auth.modelCalls
        text(OPENAI_MODEL_TAG).assertTextContains("gpt-guardado", substring = true)
        text(OPENAI_MODELS_EMPTY_TAG).assertIsDisplayed()
        // O rótulo mostra o id escolhido de qualquer jeito; o que garante que ele continua SELECIONÁVEL é o item no menu aberto.
        text(OPENAI_MODEL_TAG).performClick()
        rule.onAllNodesWithText("gpt-guardado").assertCountEquals(2) // o rótulo e o item do menu
        rule.onAllNodesWithText("gpt-guardado").onLast().performClick()

        auth.models = listOf(AccountModel("gpt-guardado", "Nome bonito"))
        text(OPENAI_MODELS_REFRESH_TAG).performClick()

        rule.waitUntil(3_000) { auth.modelCalls > callsBeforeRefresh }
        text(OPENAI_MODEL_TAG).assertTextContains("Nome bonito", substring = true)
    }

    // ── o Testar acompanha a conexão ──────────────────────────────────────────────────────

    /** O "pronto para testar" é recalculado quando a conexão muda — sem trocar de aba nem mexer nas Settings. */
    @Test
    fun theTestButtonFollowsTheConnection() {
        val auth = FakeAuth()
        var ready = false
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ReasoningPanel(
                    settings = Settings(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = "m"),
                    onSettingsChange = {},
                    resolve = {
                        if (ready) ReasoningResolution.Ready(object : ReasoningEngine {
                            override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = flowOf(ReasoningEvent.Done)
                        }) else ReasoningResolution.Unavailable(com.keyflow.reasoning.UnavailableReason.NOT_CONNECTED)
                    },
                    auth = auth,
                )
            }
        }
        text(REASONING_TEST_BUTTON_TAG).assertIsNotEnabled()

        ready = true
        auth.connection.value = ConnectionState.Connected(null, AuthMethod.API_KEY)

        rule.waitUntil(3_000) { runCatching { rule.onNodeWithTag(REASONING_TEST_REASON_TAG).assertDoesNotExist() }.isSuccess }
    }
}
