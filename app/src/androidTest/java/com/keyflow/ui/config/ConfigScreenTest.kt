package com.keyflow.ui.config

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.keyflow.ads.AdsController
import com.keyflow.ads.AdsRuntime
import com.keyflow.ads.BANNER_SLOT_TAG
import com.keyflow.ads.ADS_PRIVACY_OPTIONS_TAG
import com.keyflow.ads.ConsentRequester
import com.keyflow.ads.InterstitialPolicy
import com.keyflow.ads.InterstitialPolicyState
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.orb.LocalOrbClock
import com.keyflow.ui.tour.LocalTourClock
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes do scaffold de abas (T10 / CFG-01..05; gating redesenhado na Fase 2 de gestos). Exercitam
 * navegação, gating somente-leitura (scrim, em vez de bloquear o clique), auto-save (callbacks) e os
 * toggles inertes/persistentes do header — sem DataStore, controlando o estado direto no harness.
 */
@RunWith(AndroidJUnit4::class)
class ConfigScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val allGranted = PermissionStatus(
        microphoneGranted = true,
        overlayGranted = true,
        keyboardEnabled = true,
        keyboardActive = true,
        notificationsGranted = true,
    )

    /** Harness: ConfigScreen com estado mutável; callbacks aplicam a mudança e registram efeitos. */
    private inner class Harness {
        var settings by mutableStateOf(Settings())
        var permissions by mutableStateOf(allGranted)
        var lastOverlayChange: Boolean? = null
        var lastKeyboardChange: Boolean? = null
        var lastThemeChange: Boolean? = null
        val languageChanges = mutableListOf<com.keyflow.i18n.AppLanguage>()
        var overlayPermissionRequests = 0
        var notificationPermissionRequests = 0
        var keyboardPermissionRequests = 0
        val saveController = SaveController()
        var adsController: AdsController? = null
        var adsOff = false
        var showUpdateWarning = false
        var monetizationDisabled = false
        var agentEnabled = true
        var interstitialPolicy: InterstitialPolicy? = null
        var interstitialPolicyChanges = 0

        @Composable
        fun Content() {
            // Congela o orbe do header (senão a animação infinita nunca deixa a árvore "idle").
            KeyFlowTheme(dark = settings.themeDark) {
                CompositionLocalProvider(LocalOrbClock provides { 0f }, LocalTourClock provides { 0f }) {
                ConfigScreen(
                    settings = settings,
                    permissions = permissions,
                    saveController = saveController,
                    onOverlayModeChange = {
                        lastOverlayChange = it
                        settings = settings.copy(overlayModeEnabled = it)
                        saveController.notifySavedAsync()
                    },
                    onKeyboardModeChange = {
                        lastKeyboardChange = it
                        settings = settings.copy(keyboardModeEnabled = it)
                        saveController.notifySavedAsync()
                    },
                    onThemeChange = {
                        lastThemeChange = it
                        settings = settings.copy(themeDark = it)
                    },
                    onAppLanguageChange = { languageChanges += it },
                    onRequestOverlayPermission = { overlayPermissionRequests++ },
                    onRequestKeyboardPermission = { keyboardPermissionRequests++ },
                    onRequestMicPermission = {},
                    onRequestNotificationPermission = { notificationPermissionRequests++ },
                    onSwitchKeyboard = {},
                    onSettingsChange = { settings = it },
                    adsController = adsController,
                    adsOff = adsOff,
                    showUpdateWarning = showUpdateWarning,
                    monetizationDisabled = monetizationDisabled,
                    agentEnabled = agentEnabled,
                    interstitialPolicy = interstitialPolicy,
                    onInterstitialPolicyChanged = { interstitialPolicyChanges++ },
                    modifier = Modifier.fillMaxSize(),
                )
                }
            }
        }
    }

    @Test
    fun navigatesBetweenEnabledTabs() {
        val h = Harness().also { it.settings = Settings(overlayModeEnabled = true, keyboardModeEnabled = true) }
        rule.setContent { h.Content() }

        // Sem a aba Modo (2026-09-28), a configuração abre na Transcrição.
        rule.onNodeWithTag(panelTag(ConfigTab.VOZ)).assertIsDisplayed()
        // O cabeçalho fixo diz em que aba se está (o menu recolhido só mostra ícones).
        rule.onNodeWithTag(HEADER_TAB_TITLE_TAG).assertTextEquals("Transcrição")

        rule.onNodeWithTag(tabTag(ConfigTab.TECLADO)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.TECLADO)).assertIsDisplayed()
        rule.onNodeWithTag(HEADER_TAB_TITLE_TAG).assertTextEquals("Teclado")

        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
        rule.onNodeWithTag(HEADER_TAB_TITLE_TAG).assertTextEquals("Sobrepor")
    }

    @Test
    fun disabledAgentShowsComingSoonInsteadOfChat() {
        val h = Harness().also { it.agentEnabled = false }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.KEYFLOW)).performClick()
        rule.onNodeWithTag(COMING_SOON_PANEL_TAG).assertIsDisplayed()
        rule.onNodeWithTag(KEYFLOW_INPUT_TAG).assertDoesNotExist()
    }

    @Test
    fun bannerSlotsExistOnlyOnDocsModeAndKeyboard() {
        val h = Harness().also {
            it.settings = Settings(overlayModeEnabled = true, keyboardModeEnabled = true)
            it.adsController = AdsController(
                ApplicationProvider.getApplicationContext(),
                FakeAdsRuntime(),
                FakeConsentRequester(),
            )
        }
        rule.setContent { h.Content() }

        rule.onAllNodesWithTag(BANNER_SLOT_TAG).assertCountEquals(0)
        for (tab in listOf(ConfigTab.DOCS, ConfigTab.OVERLAY, ConfigTab.TECLADO)) {
            rule.onNodeWithTag(tabTag(tab)).performClick()
            rule.onNodeWithTag(BANNER_SLOT_TAG).assertExists()
        }
        for (tab in listOf(ConfigTab.VOZ, ConfigTab.FALA, ConfigTab.FLUXOS)) {
            rule.onNodeWithTag(tabTag(tab)).performClick()
            rule.onAllNodesWithTag(BANNER_SLOT_TAG).assertCountEquals(0)
        }
    }

    @Test
    fun requiredAdsPrivacyOptionsCanBeReopenedFromPrivacy() {
        val consent = FakeConsentRequester(required = true)
        val h = Harness().also {
            it.adsController = AdsController(
                ApplicationProvider.getApplicationContext(),
                FakeAdsRuntime(),
                consent,
            )
        }
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.PRIVACIDADE)).performClick()
        rule.onNodeWithTag(ADS_PRIVACY_OPTIONS_TAG).assertIsDisplayed().performClick()
        assertEquals(1, consent.privacyOptionsFormsShown)
    }

    @Test
    fun docsAbreNovaAbaDePrivacidade() {
        val h = Harness()
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.DOCS)).performClick()
        rule.onNodeWithTag(DOCS_OPEN_PRIVACY_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.PRIVACIDADE)).assertIsDisplayed()
        rule.onNodeWithTag(PRIVACY_POLICY_TAG).assertExists()
    }

    @Test
    fun desbloqueioOcultaOpcoesUmpNaAbaPrivacidade() {
        val h = Harness().also {
            it.adsOff = true
            it.adsController = AdsController(ApplicationProvider.getApplicationContext(), FakeAdsRuntime(), FakeConsentRequester(required = true))
        }
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.PRIVACIDADE)).performClick()
        rule.onNodeWithTag(ADS_PRIVACY_OPTIONS_TAG).assertDoesNotExist()
        rule.onNodeWithTag(PRIVACY_REVIEW_DISCLOSURE_TAG).assertExists()
    }

    @Test
    fun privacidadeNaoMostraOpcoesUmpSemExigencia() {
        val h = Harness().also {
            it.adsController = AdsController(ApplicationProvider.getApplicationContext(), FakeAdsRuntime(), FakeConsentRequester())
        }
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.PRIVACIDADE)).performClick()
        rule.onNodeWithTag(ADS_PRIVACY_OPTIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun tabSwitchWithoutEngagementDoesNotShowInterstitial() {
        val runtime = FakeAdsRuntime()
        val h = Harness().also {
            it.adsController = AdsController(
                ApplicationProvider.getApplicationContext(),
                runtime,
                FakeConsentRequester(),
            )
            it.interstitialPolicy = InterstitialPolicy(
                initialState = InterstitialPolicyState(sessionCount = 1),
            )
        }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.TECLADO)).performClick()
        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        assertEquals(0, runtime.interstitialShows)
        assertEquals(0, h.interstitialPolicyChanges)
    }

    @Test
    fun deferredTabsShowComingSoonCardWithoutControls() {
        // Estas abas continuam na navegação, mas não expõem controles antes da entrega do recurso.
        val h = Harness()
        rule.setContent { h.Content() }

        val tabs = listOf(
            ConfigTab.VISAO to "Visão",
            ConfigTab.FALA to "Voz",
            ConfigTab.FLUXOS to "Fluxos",
            ConfigTab.COMPUTADOR to "Computador",
            ConfigTab.NOTIFICACAO to "Notificação",
        )
        for ((tab, title) in tabs) {
            rule.onNodeWithTag(tabTag(tab)).performClick()
            rule.onNodeWithTag(panelTag(tab)).assertIsDisplayed()
            rule.onNodeWithTag(HEADER_TAB_TITLE_TAG).assertTextEquals(title)
            rule.onAllNodesWithTag(HEADER_TAB_HELP_TAG).assertCountEquals(0)
            rule.onNodeWithTag(COMING_SOON_LABEL_TAG).assertTextEquals("Em breve")
            rule.onAllNodes(hasAnyAncestor(hasTestTag(panelTag(tab))) and hasClickAction()).assertCountEquals(0)
            rule.onAllNodes(hasAnyAncestor(hasTestTag(panelTag(tab))) and hasSetTextAction()).assertCountEquals(0)
        }
    }

    @Test
    fun creditsSourceIsDisabledAndLegacySelectionUsesCapabilityDefault() {
        val h = Harness().also {
            it.settings = Settings(
                source = com.keyflow.core.TranscriptionSource.CREDITS,
                reasoningSource = com.keyflow.core.ReasoningSource.CREDITS,
            )
        }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(sourceTag(com.keyflow.core.TranscriptionSource.CREDITS))
            .assertIsNotEnabled()
            .assertTextContains("Em breve", substring = true)
        rule.onNodeWithTag(sourceTag(com.keyflow.core.TranscriptionSource.CUSTOM)).assertIsDisplayed()

        rule.onNodeWithTag(tabTag(ConfigTab.RACIOCINIO)).performClick()
        rule.onNodeWithTag(reasoningSourceTag(com.keyflow.core.ReasoningSource.CREDITS))
            .assertIsNotEnabled()
            .assertTextContains("Em breve", substring = true)
        rule.onNodeWithTag(reasoningSourceTag(com.keyflow.core.ReasoningSource.LOCAL)).assertIsDisplayed()
    }

    @Test
    fun theReasoningTabIsAlwaysEnabledAndHasItsHelp() {
        // Não depende de modo teclado/sobrepor nem de permissão (RSC-05): abre com os modos desligados e tem o "?".
        val h = Harness()
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.RACIOCINIO)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.RACIOCINIO)).assertIsDisplayed()
        rule.onNodeWithTag(HEADER_TAB_TITLE_TAG).assertTextEquals("Raciocínio")
        rule.onAllNodesWithTag(HEADER_TAB_HELP_TAG).assertCountEquals(1)
        rule.onNodeWithTag(reasoningSourceTag(com.keyflow.core.ReasoningSource.LOCAL)).assertIsDisplayed()

        rule.onNodeWithTag(HEADER_TAB_HELP_TAG).performClick()
        rule.onNodeWithTag(TAB_HELP_TAG).assertTextContains("Conecta um modelo de linguagem", substring = true)
    }

    @Test
    fun menuListsInterfacesThenCapabilitiesAndTheTitlesOnlyShowWhenExpanded() {
        // Yan, 2026-09-29: Teclado, Sobrepor e Notificação são interfaces; Transcrição, Visão, Raciocínio e Voz, capacidades.
        // Primeiro as interfaces. Recolhido, só uma linha separa os grupos; o título só aparece com o menu aberto.
        // Depois: o KeyFlow (agente principal) abre o menu, sem grupo nem título, e Fluxos e Computador entram nas capacidades.
        val h = Harness()
        rule.setContent { h.Content() }

        val order = listOf(
            ConfigTab.KEYFLOW,
            ConfigTab.TECLADO, ConfigTab.OVERLAY, ConfigTab.NOTIFICACAO,
            ConfigTab.FLUXOS, ConfigTab.VOZ, ConfigTab.VISAO, ConfigTab.RACIOCINIO, ConfigTab.FALA, ConfigTab.COMPUTADOR,
            ConfigTab.PRIVACIDADE, ConfigTab.DOCS,
        )
        assertEquals(order, ConfigTab.entries.toList())
        fun tops() = order.map { rule.onNodeWithTag(tabTag(it)).getUnclippedBoundsInRoot().top }

        val collapsed = tops()
        assertEquals(collapsed.sorted(), collapsed) // de cima para baixo, na ordem do menu
        for (group in listOf(TabGroup.INTERFACES, TabGroup.CAPACIDADES)) {
            rule.onNodeWithTag(navGroupTag(group)).assertIsDisplayed()
            rule.onAllNodesWithTag(navGroupTitleTag(group)).assertCountEquals(0)
        }

        rule.onNodeWithTag(NAV_RAIL_TOGGLE_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(navGroupTitleTag(TabGroup.INTERFACES)).assertIsDisplayed().assertTextEquals("INTERFACES")
        rule.onNodeWithTag(navGroupTitleTag(TabGroup.CAPACIDADES)).assertIsDisplayed().assertTextEquals("CAPACIDADES")

        // O layout vertical é o mesmo aberto e recolhido: a altura do cabeçalho está sempre reservada.
        assertEquals(collapsed, tops())
    }

    @Test
    fun headerHelpShowsTheTabSectionAndJumpsToItInTheDocs() {
        // "?" ao lado do nome da aba (Yan, 2026-09-28): a seção da Docs daquela aba, num modal, com atalho.
        val h = Harness()
        rule.setContent { h.Content() }

        rule.onNodeWithTag(HEADER_TAB_HELP_TAG).performClick()
        rule.onNodeWithTag(TAB_HELP_TAG).assertIsDisplayed()
        rule.onNodeWithTag(TAB_HELP_TAG).assertTextContains("Escolhe quem transforma sua voz em texto", substring = true)

        rule.onNodeWithTag(TAB_HELP_DOCS_TAG).performClick()
        rule.onAllNodesWithTag(TAB_HELP_TAG).assertCountEquals(0)
        rule.onNodeWithTag(panelTag(ConfigTab.DOCS)).assertIsDisplayed()
        rule.onNodeWithTag(docsSectionTag("aba-transcricao")).assertIsDisplayed()
        // A própria Docs não tem "?".
        rule.onAllNodesWithTag(HEADER_TAB_HELP_TAG).assertCountEquals(0)
    }

    @Test
    fun disabledTabNavigatesReadOnly() {
        val h = Harness() // overlay/keyboard off por padrão
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        // A aba abre com o cartão do modo no topo, tocável; só o resto fica somente-leitura sob a camada.
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
        rule.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).assertIsDisplayed()
        rule.onNodeWithTag(READONLY_SCRIM_TAG).assertExists()

        rule.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
        rule.runOnIdle { assertEquals(true, h.lastOverlayChange) }
        rule.onAllNodesWithTag(READONLY_SCRIM_TAG).assertCountEquals(0)
    }

    @Test
    fun tappingTheLockedRestExplainsAndPointsToTheModeCard() {
        val h = Harness() // overlay desligado
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        // Logo abaixo do cartão do modo começa a parte travada: o toque não mexe em nada, só explica. O
        // relógio fica parado: andando sozinho, ele passaria dos 2,5 s do aviso antes da checagem.
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(READONLY_SCRIM_TAG).performTouchInput { click(Offset(width / 2f, 8f)) }
        rule.mainClock.advanceTimeBy(FRAME_MS * 4)

        rule.onNodeWithTag(MESSAGE_TOAST_TAG)
            .assertTextContains("Ligue o Modo Sobrepor, no topo", substring = true)
    }

    @Test
    fun activeTabBecomesReadOnlyWhenModeTurnsOff() {
        val h = Harness().also { it.settings = Settings(overlayModeEnabled = true) }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
        rule.onAllNodesWithTag(READONLY_SCRIM_TAG).assertCountEquals(0)

        rule.runOnIdle { h.settings = h.settings.copy(overlayModeEnabled = false) }

        // Modo desligado → continua na aba Sobrepor; o resto (abaixo do cartão) fica somente-leitura.
        rule.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertIsDisplayed()
        rule.onNodeWithTag(READONLY_SCRIM_TAG).assertExists()
    }

    @Test
    fun overlayModeToggleInvokesAutoSave() {
        val h = Harness()
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        rule.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()

        rule.runOnIdle { assertEquals(true, h.lastOverlayChange) }
        // Aba Sobrepor agora liberada: nada mais sob a camada de somente-leitura.
        rule.onAllNodesWithTag(READONLY_SCRIM_TAG).assertCountEquals(0)
    }

    @Test
    fun keyboardModeToggleInvokesAutoSave() {
        val h = Harness()
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.TECLADO)).performClick()

        rule.onNodeWithTag(TOGGLE_KEYBOARD_MODE_TAG).performClick()

        rule.runOnIdle { assertEquals(true, h.lastKeyboardChange) }
    }

    /**
     * Ligar o Modo Sobrepor com sobrepor+notificações pendentes: o toast sobe, e pouco depois (sem
     * precisar do usuário achar o chip) a tela/diálogo da permissão que falta abre sozinha — primeiro
     * a de sobrepor (a que aparece primeiro nos chips), só depois a de notificações (Yan, 2026-07-04).
     */
    @Test
    fun overlayModeTogglePendingPermissionsAutoOpenAfterToast() {
        val h = Harness().also {
            it.permissions = allGranted.copy(overlayGranted = false, notificationsGranted = false)
        }
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        rule.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
        advancePastAutoPermissionDelay()

        // Só dá pra provar a ORDEM: sobrepor primeiro, notificações ainda não.
        rule.runOnIdle {
            assertEquals(1, h.overlayPermissionRequests)
            assertEquals(0, h.notificationPermissionRequests)
        }
    }

    /**
     * Com só notificações pendentes, o encadeamento chama o MESMO [onRequestNotificationPermission] do
     * clique manual no chip — ambos podem escalar pras configs do app se o Android já não mostrar mais
     * o popup (negada permanentemente): sem essa igualdade, o encadeamento ficava sem conseguir
     * terminar depois de uma negação (Yan, 2026-07-04).
     */
    @Test
    fun overlayModeToggleAutoRequestsNotificationsViaSameHandlerAsChip() {
        val h = Harness().also { it.permissions = allGranted.copy(notificationsGranted = false) }
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        rule.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
        advancePastAutoPermissionDelay()

        rule.runOnIdle { assertEquals(1, h.notificationPermissionRequests) }
    }

    /**
     * O pedido automático sai [AUTO_PERMISSION_DELAY_MS] depois do toggle, no relógio do Compose. Esperar
     * isso em tempo real (`waitUntil(2000)`) dependia de quantos quadros a máquina desenha por segundo: a
     * aba Modo (hoje o cartão no topo de Sobrepor/Teclado) passou a ter tours animados e, num runner de CI de 2 núcleos, 2 s de relógio de parede
     * não chegavam a 400 ms de relógio de teste. Avançar o relógio de teste é igual em qualquer máquina.
     */
    private fun advancePastAutoPermissionDelay() {
        rule.mainClock.advanceTimeBy(AUTO_PERMISSION_DELAY_MS + FRAME_MS)
    }

    /** O microfone nunca bloqueia: com só ele pendente, o Modo Sobrepor liga sem abrir nada sozinho. */
    @Test
    fun overlayModeToggleIgnoresMissingMicrophone() {
        val h = Harness().also { it.permissions = allGranted.copy(microphoneGranted = false) }
        rule.setContent { h.Content() }
        rule.onNodeWithTag(tabTag(ConfigTab.OVERLAY)).performClick()

        rule.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()

        rule.runOnIdle { assertEquals(true, h.lastOverlayChange) }
        Thread.sleep(700) // tempo de sobra pro auto-request (não deveria disparar nada)
        assertEquals(0, h.overlayPermissionRequests)
        assertEquals(0, h.notificationPermissionRequests)
    }

    @Test
    fun themeTogglePersistsAndLanguageMenuOffersTheThreeLanguages() {
        val h = Harness()
        rule.setContent { h.Content() }

        rule.onNodeWithTag(THEME_TOGGLE_TAG).performClick()
        rule.runOnIdle { assertEquals(true, h.lastThemeChange) }

        // O botão de idioma abre português, inglês e espanhol; escolher avisa quem salva (Yan, 2026-09-28).
        rule.onNodeWithTag(LANG_TOGGLE_TAG).performClick()
        rule.onNodeWithTag(languageOptionTag(com.keyflow.i18n.AppLanguage.ES)).performClick()
        rule.runOnIdle { assertEquals(listOf(com.keyflow.i18n.AppLanguage.ES), h.languageChanges) }
    }

    @Test
    fun unlockSheetOpensAndCloses() {
        val h = Harness().also { it.settings = Settings(keyboardModeEnabled = true) }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(tabTag(ConfigTab.TECLADO)).performClick()
        // A prévia ao vivo de tamanho do teclado (2026-07-02) empurrou "Desbloquear skins" pra fora do
        // viewport padrão do teste — precisa rolar até o nó antes do clique (performClick é por coordenada).
        rule.onNodeWithTag(TECLADO_UNLOCK_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(UNLOCK_SHEET_TAG).assertIsDisplayed()

        rule.onNodeWithTag(UNLOCK_CLOSE_TAG).performClick()
        rule.onAllNodesWithTag(UNLOCK_SHEET_TAG).assertCountEquals(0)
    }

    @Test
    fun remoteWarningsAreVisibleAndKillSwitchDoesNotOpenUnlockOrBanner() {
        val h = Harness().also {
            it.settings = Settings(keyboardModeEnabled = true)
            it.showUpdateWarning = true
            it.monetizationDisabled = true
            it.adsController = AdsController(
                ApplicationProvider.getApplicationContext(),
                FakeAdsRuntime(),
                FakeConsentRequester(),
            )
        }
        rule.setContent { h.Content() }

        rule.onNodeWithTag(REMOTE_UPDATE_WARNING_TAG).assertIsDisplayed()
        rule.onNodeWithTag(REMOTE_KILL_SWITCH_TAG).assertIsDisplayed()
        rule.onNodeWithTag(tabTag(ConfigTab.DOCS)).performClick()
        rule.onAllNodesWithTag(BANNER_SLOT_TAG).assertCountEquals(0)
        rule.onNodeWithTag(tabTag(ConfigTab.TECLADO)).performClick()
        rule.onNodeWithTag(TECLADO_UNLOCK_TAG).performScrollTo().performClick()
        rule.onAllNodesWithTag(UNLOCK_SHEET_TAG).assertCountEquals(0)
    }

    private companion object {
        /** Um quadro do relógio de teste do Compose — folga para o efeito rodar depois do atraso. */
        const val FRAME_MS = 16L
    }

    private class FakeConsentRequester(private val required: Boolean = false) : ConsentRequester {
        var privacyOptionsFormsShown = 0
        override fun request(activity: Activity, onReady: () -> Unit) = onReady()
        override fun privacyOptionsRequired(context: Context): Boolean = required
        override fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit) {
            privacyOptionsFormsShown++
            onDismissed()
        }
    }

    private class FakeAdsRuntime : AdsRuntime {
        var interstitialShows = 0

        override fun initialize(context: Context, onComplete: () -> Unit) = onComplete()
        override fun createBanner(context: Context, adUnitId: String): View? = null
        override fun loadInterstitial(
            context: Context,
            adUnitId: String,
            onReady: () -> Unit,
            onFailed: () -> Unit,
        ) = onReady()
        override fun showInterstitial(activity: Activity): Boolean {
            interstitialShows++
            return true
        }
        override fun destroy(view: View) = Unit
    }
}
