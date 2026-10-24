package com.keyflow.ui

import com.keyflow.i18n.tr
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.common.SaveController
import com.keyflow.i18n.AppLanguage
import com.keyflow.ui.config.AppScreenOrientation
import com.keyflow.ui.config.ConfigScreen
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.onboarding.OnboardingScreen
import com.keyflow.ui.onboarding.ProminentDisclosureScreen
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import com.keyflow.ui.onboarding.DisclosureConsent
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelKind
import com.keyflow.data.models.ModelStore
import com.keyflow.reasoning.ReasoningEngines
import com.keyflow.reasoning.local.LocalLlmSupport
import com.keyflow.reasoning.local.LocalReasoningEngine
import com.keyflow.auth.AuthController
import com.keyflow.App
import com.keyflow.BuildConfig
import com.keyflow.auth.ProviderIds
import com.keyflow.ui.config.AccountModel
import com.keyflow.ui.config.AuthActions
import com.keyflow.ui.config.ControllerAuthActions
import com.keyflow.ui.config.DirectApiKeyActions
import com.keyflow.reasoning.openai.OpenAiModels
import com.keyflow.reasoning.openai.OpenAiReasoningEngine
import com.keyflow.reasoning.anthropic.AnthropicAgentEngine
import com.keyflow.reasoning.anthropic.AnthropicModels
import com.keyflow.agenthost.AgentBackends
import com.keyflow.agenthost.AgentHost
import kotlinx.coroutines.flow.map
import com.keyflow.reasoning.anthropic.AnthropicReasoningEngine
import com.keyflow.reasoning.google.GoogleModels
import com.keyflow.reasoning.google.GoogleReasoningEngine
import com.keyflow.ui.config.LocalModelsUi
import com.keyflow.ui.config.LocalModelsSnapshot
import com.keyflow.ui.config.LocalModelsController
import com.keyflow.data.monetization.BillingManager
import com.keyflow.data.monetization.Entitlements
import com.keyflow.data.monetization.EntitlementsRepository
import com.keyflow.data.monetization.KeyFlowApiClient
import com.keyflow.data.monetization.MonetizationProducts
import com.keyflow.data.monetization.StorePurchaseState
import com.keyflow.data.monetization.RemoteConfigRepository
import com.keyflow.data.monetization.RemoteConfigSnapshot
import com.keyflow.ui.config.UnlockPurchaseState
import com.android.billingclient.api.BillingClient
import com.keyflow.ads.AdsController
import com.keyflow.ads.DataStoreInterstitialPolicyStore
import com.keyflow.ads.InterstitialPolicy
import com.keyflow.ads.toInterstitialPolicyParams

/**
 * Tela de configuração em Compose (T10). Substitui o formulário XML legado pelo scaffold de abas
 * [ConfigScreen]. O estado vem de [SettingsRepository.settingsFlow] e é persistido com auto-save via
 * [SettingsRepository.saveSettings]. O toggle de Modo Overlay também liga/desliga o [OverlayService]
 * (a fiação do FGS foi reaproveitada do antigo "Ativar botão flutuante").
 */
class SetupActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionsChecker = PermissionsChecker()
    private lateinit var settingsRepository: SettingsRepository
    private val saveController = SaveController()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Independente do resultado, conclui o onboarding e encadeia sobrepor + teclado em seguida.
        permissions = permissionsChecker.snapshot(this)
        markOnboardingDone()
        startOnboardingOverlayAndKeyboardChain()
    }

    // Pedido pontual do microfone na aba Modo — só atualiza o snapshot, não mexe no onboarding.
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissions = permissionsChecker.snapshot(this)
    }

    // Idem para notificações (aba Modo, card Sobrepor) — sem ela o Modo Sobrepor persiste o flag mas o
    // OverlayService nunca sobe (ver onOverlayModeChange), e antes disso não havia chip nenhum avisando.
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissions = permissionsChecker.snapshot(this)
    }

    private var settings by mutableStateOf(Settings())
    private var showDisclosure by mutableStateOf(false)
    private var showDeclineExplanation by mutableStateOf(false)

    // Modelos de voz locais (native-voice-models, NM-T10).
    private val localModels by lazy { LocalModelsController(this) }

    // Autenticação dos provedores de nuvem (módulo `auth/`): cofre próprio, registro, controlador da tela e quem entrega os
    // cabeçalhos ao motor. A UI só conhece o registro e o `AuthController` — nada de provedor concreto.
    // O grafo é do PROCESSO (App), não desta Activity: a renovação do token é serializada por um Mutex que tem de ser o mesmo
    // para todos, e um login em andamento não pode morrer com a Activity.
    private val auth by lazy { (application as App).authServices }
    private val openAiAuth by lazy {
        val models = OpenAiModels(auth.http)
        ControllerAuthActions(auth.registry.first { it.id == ProviderIds.OPENAI }, auth.controller, auth.browser) {
            val headers = runCatching { auth.authorization.headers(ProviderIds.OPENAI) }.getOrNull()
            val list = if (headers == null) emptyList() else models.list(headers)
            if (list.isNotEmpty()) list.map { AccountModel(it.id, it.displayName) }
            else OpenAiModels.DEFAULT_MODELS.map { AccountModel(it.id, it.displayName) }
        }
    }

    private val groqAuth by lazy { DirectApiKeyActions(auth.store, ProviderIds.GROQ, activityScope) }
    private val elevenLabsAuth by lazy { DirectApiKeyActions(auth.store, ProviderIds.ELEVENLABS, activityScope) }

    private val anthropicAuth by lazy {
        val provider = auth.registry.firstOrNull { it.id == ProviderIds.ANTHROPIC }
        if (provider == null) AuthActions.None
        else {
            val models = AnthropicModels(auth.http)
            ControllerAuthActions(provider, auth.controller, auth.browser) {
                val headers = runCatching { auth.authorization.headers(ProviderIds.ANTHROPIC) }.getOrNull()
                val list = models.list(headers.orEmpty())
                list.map { AccountModel(it.id, it.displayName) }
            }
        }
    }

    private val googleAuth by lazy {
        val provider = auth.registry.firstOrNull { it.id == ProviderIds.GOOGLE }
        if (provider == null) AuthActions.None
        else {
            val models = GoogleModels(auth.http)
            ControllerAuthActions(provider, auth.controller, auth.browser) {
                val headers = runCatching { auth.authorization.headers(ProviderIds.GOOGLE) }.getOrNull()
                val list = models.list(headers.orEmpty())
                list.map { AccountModel(it.id, it.displayName) }
            }
        }
    }

    // O motor do Raciocínio: local pelo :reasoning; OpenAI, Anthropic e Google por APIs na nuvem com as credenciais do cofre.
    private val reasoningEngines by lazy {
        ReasoningEngines(
            isModelInstalled = { ModelStore.get(this).isInstalled(it) },
            // Um login sem tokens (o "esqueleto" da renovação que falhou) não é credencial: pede entrar de novo.
            hasOpenAiCredential = { auth.store.getApiKey(ProviderIds.OPENAI) != null || auth.store.getOAuth(ProviderIds.OPENAI)?.isSkeleton == false },
            hasAnthropicCredential = { auth.store.getApiKey(ProviderIds.ANTHROPIC) != null || auth.store.getOAuth(ProviderIds.ANTHROPIC)?.isSkeleton == false },
            hasGoogleCredential = { auth.store.getApiKey(ProviderIds.GOOGLE) != null || auth.store.getOAuth(ProviderIds.GOOGLE)?.isSkeleton == false },
            local = { LocalReasoningEngine.create(this, it) },
            openAi = { OpenAiReasoningEngine(auth.authorization, auth.http, it) },
            anthropic = { AnthropicReasoningEngine(auth.authorization, auth.http, it) },
            google = { GoogleReasoningEngine(auth.authorization, auth.http, it) },
        )
    }
    // O agente da aba KeyFlow (agent-harness): as peças vivem no processo; aqui só se escolhe, pela origem de raciocínio, se a próxima
    // mensagem usa ferramentas (Anthropic por chave de API) ou o chat de texto de sempre.
    private val agentRuntime by lazy {
        AgentHost.of(this).runtime(
            AgentBackends(
                hasTools = { AgentHost.of(this).registry.all().isNotEmpty() },
                anthropicApiKeyConnected = { auth.store.getApiKey(ProviderIds.ANTHROPIC) != null },
                anthropicCredentialChanges = auth.store.changes(ProviderIds.ANTHROPIC).map { },
                agentEngine = { model -> AnthropicAgentEngine(auth.authorization, auth.http, model) },
                resolveText = reasoningEngines::resolve,
            ),
        )
    }
    private var localSnapshot by mutableStateOf(LocalModelsSnapshot.EMPTY)
    private var localModelsJob: Job? = null
    // Evita piscar o onboarding antes do 1º valor do DataStore chegar (ONB-03).
    private var settingsLoaded by mutableStateOf(false)
    // Barras/grades de atalhos do teclado (SB-T9) — chaves de DataStore próprias, fora de `Settings`.
    private var barsConfig by mutableStateOf(BarsConfig.DEFAULT)
    private var shortcutGrids by mutableStateOf<List<ShortcutGrid>>(emptyList())
    // Monetização só é criada/conectada depois que o onboarding terminou; o onboarding não toca Play,
    // Integrity, API nem a camada de ads (MZ-T9/ADS-01).
    private var monetizationApi: KeyFlowApiClient? = null
    private var billingManager: BillingManager? = null
    private var entitlementsRepository: EntitlementsRepository? = null
    private var remoteConfigRepository: RemoteConfigRepository? = null
    private var adsController: AdsController? = null
    private var interstitialPolicy by mutableStateOf<InterstitialPolicy?>(null)
    private var interstitialPolicyStore: DataStoreInterstitialPolicyStore? = null
    private var entitlements by mutableStateOf(Entitlements())
    private var entitlementsLoaded by mutableStateOf(false)
    private var remoteSnapshot by mutableStateOf(RemoteConfigSnapshot())
    private var remoteConfigLoaded by mutableStateOf(false)
    private var unlockPurchaseState by mutableStateOf(UnlockPurchaseState.IDLE)
    private var monetizationJobs = mutableListOf<Job>()
    private var monetizationStarted = false
    // Desligar o Modo Teclado abre o seletor; só efetiva se o KeyFlow deixar de ser o teclado ativo.
    private var pendingKeyboardDisable = false
    // Ativação pendente (Yan, 2026-07-04): o usuário só precisa tocar o toggle UMA vez — cada permissão
    // concedida (sobrepor, depois notificações; ou o teclado habilitado) continua a ativação sozinha
    // quando a janela reganha foco, sem exigir tocar o toggle de novo a cada permissão. MAS só UMA
    // tentativa por pendência: se a janela reganha foco e a MESMA permissão ainda falta, cancela (não
    // fica reabrindo a tela/diálogo sozinho a cada retomada) — só tenta de novo se o usuário tocar o
    // toggle outra vez (Yan, 2026-07-04: sem isso, negar e voltar ficava preso reabrindo a mesma tela
    // em loop, sem nem mostrar o app atrás).
    private var pendingOverlayEnable = false
    private var pendingKeyboardEnable = false
    // Qual passo (permissão) já tentamos pra pendência atual — usado pra distinguir "1ª tentativa desse
    // passo" (segue) de "voltou sem resolver O MESMO passo" (cancela). Nulo = nenhuma tentativa ainda.
    private var lastOverlayBlockingStep: String? = null
    private var keyboardStepAttempted = false
    // Debounce entre a abertura de telas do sistema e os callbacks de foco/retomada que elas podem
    // disparar durante a própria transição, além de evitar reprocessar o mesmo retorno em onResume e
    // onWindowFocusChanged.
    private val foregroundReturnGate = ForegroundReturnGate(debounceMs = 250)
    // Banner inicial do onboarding (Yan, 2026-07-05): "Conceder permissões" pedia só mic/notificações e
    // deixava sobrepor/teclado só pra aba Modo — agora encadeia os dois também, um de cada vez (só dá
    // pra abrir uma tela de configs do sistema por vez), reaproveitando o mesmo mecanismo de pendência.
    private var onboardingKeyboardChainPending = false
    // Navegação via intent (clique no erro → aba Voz). Consumido pela ConfigScreen.
    private var requestedTab by mutableStateOf<ConfigTab?>(null)
    private var requestedTabKey by mutableStateOf(0)
    private var permissions by mutableStateOf(
        PermissionStatus(
            microphoneGranted = false,
            overlayGranted = false,
            keyboardEnabled = false,
            notificationsGranted = false,
        ),
    )

    // Rotação da tela controlada exclusivamente pelo botão do cabeçalho em sentido único contínuo.
    private var screenOrientation by mutableStateOf(AppScreenOrientation.PORTRAIT)
    private var rotationStep by mutableStateOf(0)

    private fun applyScreenOrientation(orientation: AppScreenOrientation) {
        requestedOrientation = when (orientation) {
            AppScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            AppScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            AppScreenOrientation.REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            AppScreenOrientation.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uiPrefs = getSharedPreferences("keyflow_ui_state", Context.MODE_PRIVATE)
        val savedOrientationId = if (uiPrefs.contains("screen_orientation")) {
            uiPrefs.getInt("screen_orientation", 0)
        } else if (uiPrefs.getBoolean("screen_landscape", false)) {
            1
        } else {
            0
        }
        screenOrientation = AppScreenOrientation.fromId(savedOrientationId)
        rotationStep = screenOrientation.id
        applyScreenOrientation(screenOrientation)
        settingsRepository = SettingsRepository(this)
        showDisclosure = intent.getBooleanExtra(EXTRA_REQUIRE_DISCLOSURE, false)
        handleNavIntent(intent)

        activityScope.launch {
            settingsRepository.settingsFlow().collectLatest {
                settings = it
                settingsLoaded = true
                if (it.onboardingDone) startMonetizationIfNeeded()
                // Primeira abertura: a interface assume o idioma do aparelho (PT/EN/ES; outros → inglês).
                // Depois disso, quem manda é o botão de idioma do cabeçalho.
                if (it.appLanguage.isBlank()) persist { s -> s.copy(appLanguage = AppLanguage.fromSystem().code) }
            }
        }
        activityScope.launch {
            settingsRepository.barsConfigFlow().collectLatest { barsConfig = it }
        }
        activityScope.launch {
            settingsRepository.shortcutGridsFlow().collectLatest { shortcutGrids = it }
        }

        setContent {
            // Tema Ateliê app-wide; rota inicial: 1º uso → onboarding, depois → config (T27).
            KeyFlowTheme(dark = settings.themeDark) {
                if (!settingsLoaded) {
                    // Placeholder neutro enquanto o DataStore carrega — evita o flash do onboarding.
                    Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg))
                } else if (
                    showDisclosure ||
                    showDeclineExplanation ||
                    !DisclosureConsent.fromStoredVersion(settings.disclosureAcceptedVersion).isAcceptedForCurrentVersion() &&
                    (!settings.onboardingDone || !settings.disclosureDeclined)
                ) {
                    ProminentDisclosureScreen(
                        declined = settings.disclosureDeclined,
                        onAccept = ::acceptDisclosure,
                        onDecline = ::declineDisclosure,
                    )
                } else if (settings.onboardingDone) {
                    // Ler o snapshot aqui faz a composição reagir à chegada do manifesto; o catálogo
                    // global também é atualizado para SettingsRepository/ModelDownloader.
                    val remoteCatalog = remoteSnapshot.models
                    ConfigScreen(
                        settings = settings,
                        permissions = permissions,
                        saveController = saveController,
                        onOverlayModeChange = ::onOverlayModeChange,
                        onKeyboardModeChange = ::onKeyboardModeChange,
                        onThemeChange = { dark -> persist { it.copy(themeDark = dark) } },
                        onAppLanguageChange = { language -> persist { it.copy(appLanguage = language.code) } },
                        screenOrientation = screenOrientation,
                        rotationStep = rotationStep,
                        onRotationClick = {
                            rotationStep++
                            val target = screenOrientation.next()
                            screenOrientation = target
                            getSharedPreferences("keyflow_ui_state", Context.MODE_PRIVATE)
                                .edit()
                                .putInt("screen_orientation", target.id)
                                .apply()
                            applyScreenOrientation(target)
                        },
                        onRequestOverlayPermission = ::openOverlaySettings,
                        onRequestKeyboardPermission = ::openKeyboardSettings,
                        onRequestMicPermission = ::onMicChipClicked,
                        onRequestNotificationPermission = ::onNotificationChipClicked,
                        onSwitchKeyboard = ::openKeyboardPicker,
                        onSettingsChange = { updated -> persist { updated } },
                        barsConfig = barsConfig,
                        onBarsConfigChange = { updated ->
                            barsConfig = updated
                            activityScope.launch { settingsRepository.saveBarsConfig(updated) }
                        },
                        shortcutGrids = shortcutGrids,
                        onShortcutGridsChange = { updated ->
                            shortcutGrids = updated
                            activityScope.launch { settingsRepository.saveShortcutGrids(updated) }
                        },
                        requestedTab = requestedTab,
                        requestedTabKey = requestedTabKey,
                        skinsUnlocked = entitlements.skinsUnlocked,
                        onUnlockPurchase = ::buyUnlock,
                        unlockPurchaseState = unlockPurchaseState,
                        onDeleteMyData = {
                            val api = checkNotNull(monetizationApi)
                            checkNotNull(entitlementsRepository).deleteRemoteData { api.deleteAccount() }
                        },
                        adsController = adsController,
                        adsOff = entitlements.adsOff,
                        entitlementsLoaded = entitlementsLoaded,
                        remoteConfigLoaded = remoteConfigLoaded,
                        showUpdateWarning = remoteSnapshot.requiresUpdate(),
                        monetizationDisabled = remoteSnapshot.config.killSwitch,
                        interstitialPolicy = interstitialPolicy,
                        onInterstitialPolicyChanged = ::persistInterstitialPolicy,
                        localModels = LocalModelsUi.compose(
                            catalog = remoteCatalog.filter { it.kind == ModelKind.SPEECH }
                                .ifEmpty { ModelCatalog.current(ModelKind.SPEECH) },
                            installed = localSnapshot.installed,
                            active = localSnapshot.active,
                            failures = localSnapshot.failures,
                            selectedId = settings.localModelId,
                            deviceRamMb = localModels.deviceRamMb,
                        ),
                        reasoningModels = LocalModelsUi.compose(
                            catalog = remoteCatalog.filter { it.kind == ModelKind.TEXT }
                                .ifEmpty { ModelCatalog.current(ModelKind.TEXT) },
                            installed = localSnapshot.installed,
                            active = localSnapshot.active,
                            failures = localSnapshot.failures,
                            selectedId = settings.reasoningLocalModelId,
                            deviceRamMb = localModels.deviceRamMb,
                        ),
                        visionModels = LocalModelsUi.compose(
                            catalog = remoteCatalog.filter { it.kind == ModelKind.VISION }
                                .ifEmpty { ModelCatalog.current(ModelKind.VISION) },
                            installed = localSnapshot.installed,
                            active = localSnapshot.active,
                            failures = localSnapshot.failures,
                            selectedId = settings.visionLocalModelId,
                            deviceRamMb = localModels.deviceRamMb,
                        ),
                        falaModels = LocalModelsUi.compose(
                            catalog = remoteCatalog.filter { it.kind == ModelKind.VOICE }
                                .ifEmpty { ModelCatalog.current(ModelKind.VOICE) },
                            installed = localSnapshot.installed,
                            active = localSnapshot.active,
                            failures = localSnapshot.failures,
                            selectedId = settings.falaLocalModelId,
                            deviceRamMb = localModels.deviceRamMb,
                        ),
                        resolveReasoning = reasoningEngines::resolve,
                        reasoningAuth = openAiAuth,
                        reasoningAnthropicAuth = anthropicAuth,
                        reasoningGoogleAuth = googleAuth,
                        transcriptionOpenAiAuth = openAiAuth,
                        transcriptionGroqAuth = groqAuth,
                        falaElevenLabsAuth = elevenLabsAuth,
                        reasoningLocalSupported = LocalLlmSupport.isSupported(android.os.Build.SUPPORTED_ABIS),
                        localActions = localModels,
                        agentEnabled = BuildConfig.AGENT_ENABLED,
                        agent = if (BuildConfig.AGENT_ENABLED) agentRuntime else null,
                    )
                } else {
                    OnboardingScreen(
                        permissions = permissions,
                        onGrant = ::requestRuntimePermissions,
                        onSkip = ::skipOnboarding,
                        onRequestMic = ::onMicChipClicked,
                        onRequestOverlay = ::onOverlayChipClickedInOnboarding,
                        onRequestKeyboard = ::onKeyboardChipClickedInOnboarding,
                        onRequestNotifications = ::onNotificationChipClicked,
                    )
                }
            }
        }
    }

    /** Pede as permissões de runtime (mic + notificações); o resultado conclui o onboarding. */
    private fun requestRuntimePermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestPermissions.launch(perms.toTypedArray())
    }

    /** Só este aceite libera a solicitação de RECORD_AUDIO; aceite antigo reapresenta a tela. */
    private fun acceptDisclosure() {
        settings = settings.copy(disclosureAcceptedVersion = DISCLOSURE_VERSION, disclosureDeclined = false)
        showDeclineExplanation = false
        activityScope.launch { settingsRepository.saveDisclosureConsent(DISCLOSURE_VERSION, declined = false) }
        showDisclosure = false
        if (permissions.microphoneGranted) return
        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun declineDisclosure() {
        if (settings.disclosureDeclined) {
            showDeclineExplanation = false
            requestedTab = ConfigTab.OVERLAY
            requestedTabKey++
            showDisclosure = false
            persistOnboardingDone(true)
            return
        }
        settings = settings.copy(disclosureDeclined = true)
        showDeclineExplanation = true
        activityScope.launch {
            settingsRepository.saveDisclosureConsent(settings.disclosureAcceptedVersion, declined = true)
        }
    }

    /**
     * Continua o banner inicial pro Modo Sobrepor, na volta do popup de mic/notificações: reaproveita
     * [onOverlayModeChange] pra marcar a pendência, mas como o onboarding não tem o wrapper da
     * ConfigScreen (que abre a 1ª tela de configs sozinha), abre aqui manualmente. Só continua pro
     * teclado depois que o sobrepor terminar (dá certo ou é cancelado) — só dá pra navegar pra uma tela
     * de configs do sistema por vez.
     */
    private fun startOnboardingOverlayAndKeyboardChain() {
        onboardingKeyboardChainPending = true
        onOverlayModeChange(true)
        when (lastOverlayBlockingStep) {
            "overlay" -> openOverlaySettings()
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestOrOpenSettings(Manifest.permission.POST_NOTIFICATIONS, requestNotificationPermission)
            }
            null -> continueOnboardingKeyboardChainIfNeeded() // sobrepor já ativou de vez.
        }
    }

    /** Dispara a pendência do Modo Teclado assim que o sobrepor (dar certo ou cancelar) libera a vez. */
    private fun continueOnboardingKeyboardChainIfNeeded() {
        if (!onboardingKeyboardChainPending) return
        onboardingKeyboardChainPending = false
        onKeyboardModeChange(true)
        if (pendingKeyboardEnable && keyboardStepAttempted) {
            openKeyboardSettings()
        }
    }

    /** Marca o onboarding como concluído (ONB-03) → a próxima composição vai para a config. */
    private fun markOnboardingDone() {
        persistOnboardingDone(true)
    }

    /**
     * "Pular por agora": conclui o onboarding sem forçar mais nenhuma tela de permissão — mas ativa
     * sozinho o que já estiver pronto (o usuário pode ter concedido alguns itens do checklist um a um
     * antes de pular). Sem isso, a pessoa saía do onboarding com sobrepor/teclado já permitidos no
     * sistema mas os toggles continuando desligados, precisando ligar tudo de novo manualmente na aba
     * Modo (Yan, 2026-07-05).
     */
    private fun skipOnboarding() {
        markOnboardingDone()
        activateWhatIsAlreadyGranted()
    }

    /**
     * Liga Sobrepor/Teclado se as permissões de cada um já estiverem concedidas — sem forçar nenhuma
     * navegação para o que ainda falta (diferente de [startOnboardingOverlayAndKeyboardChain], que
     * encadeia e força as telas que faltam). Chamada ao concluir o onboarding por qualquer caminho, pra
     * refletir permissões que o usuário já tenha concedido pelos itens do checklist.
     */
    private fun activateWhatIsAlreadyGranted() {
        val status = permissionsChecker.snapshot(this)
        if (status.overlayGranted && status.notificationsGranted && !settings.overlayModeEnabled) {
            activateOverlayNow()
        }
        if (status.keyboardEnabled && !status.keyboardActive) {
            activateKeyboardNow()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUIRE_DISCLOSURE, false)) showDisclosure = true
        handleNavIntent(intent)
    }

    /** Lê extras de navegação: pular para a aba Transcrição, Sobrepor ou Teclado (clique num erro clicável). */
    private fun handleNavIntent(intent: Intent?) {
        val tab = when (intent?.getStringExtra(EXTRA_NAV)) {
            NAV_TAB_VOZ -> ConfigTab.VOZ
            NAV_TAB_OVERLAY_MIC -> ConfigTab.OVERLAY
            NAV_TAB_KEYBOARD_MIC -> ConfigTab.TECLADO
            else -> null
        } ?: return
        requestedTab = tab
        requestedTabKey++
    }

    override fun onResume() {
        super.onResume()
        permissions = permissionsChecker.snapshot(this)
        syncKeyboardModeToActive()
        onForegroundRegained()
    }

    /**
     * Reatualiza as permissões ao reganhar foco. Cobre o seletor de teclados (`showInputMethodPicker`),
     * que é um diálogo do sistema e **não** dispara onResume — sem isso, "teclado ativo" não atualizava
     * após trocar. Também resolve o desligamento pendente do Modo Teclado e sincroniza o toggle.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        permissions = permissionsChecker.snapshot(this)
        if (pendingKeyboardDisable) {
            pendingKeyboardDisable = false
            if (permissions.keyboardActive) {
                Toast.makeText(
                    this,
                    tr("KeyFlow ainda é o teclado ativo — troque para desativar."),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        syncKeyboardModeToActive()
        onForegroundRegained()
        // `tryActivateKeyboard` pode chamar `showInputMethodPicker()`, que só funciona com a janela
        // REALMENTE com foco de entrada — `onResume()` dispara ANTES desse foco ser restabelecido (ao
        // voltar de uma tela cheia como as configs do teclado), então chamar por lá faz o Android
        // ignorar a chamada em silêncio, sem abrir nada e sem erro nenhum (Yan, 2026-07-04: o
        // encadeamento "parecia" cancelar sozinho, mas na real o seletor nunca chegava a abrir). Por
        // isso mora aqui — mas ainda precisa ignorar callbacks espúrios gerados pela própria abertura
        // das telas do sistema, antes do usuário ter tempo de interagir.
        if (foregroundReturnGate.shouldRunFocusSensitiveContinuation(SystemClock.elapsedRealtime())) {
            tryActivateKeyboard()
        }
    }

    /**
     * Roda a checagem de "voltou a primeiro plano" pro Modo Sobrepor (desativar se revogaram permissão,
     * continuar ativação pendente): chamada tanto de [onResume] quanto de [onWindowFocusChanged] porque
     * nenhum dos dois sozinho cobre TODOS os jeitos de voltar (telas de configs em tela cheia disparam
     * onResume; alguns diálogos/painéis do sistema só mudam o foco da janela). Os dois disparam em
     * sequência pra UM único evento real de "voltar" — sem o debounce abaixo, a 2ª chamada via o MESMO
     * passo que a 1ª acabara de registrar como pendente e cancelava a ativação na hora, antes do usuário
     * sequer ver a próxima tela abrir (Yan, 2026-07-04).
     */
    private fun onForegroundRegained() {
        val now = SystemClock.elapsedRealtime()
        if (!foregroundReturnGate.shouldProcessForegroundReturn(now)) return
        deactivateOverlayIfPermissionsRevoked()
        tryActivateOverlay()
    }

    /**
     * Se o Modo Sobrepor já está ligado mas sobrepor/notificações foram revogadas nas configs do
     * sistema (fora do app), desliga sozinho — sem isso o toggle ficava "ligado" visualmente mas a
     * permissão já não valia mais (Yan, 2026-07-04: negar notificação com o modo já ativo não desligava
     * nada, só devia desativar).
     */
    private fun deactivateOverlayIfPermissionsRevoked() {
        if (!settingsLoaded || !settings.overlayModeEnabled) return
        val status = permissionsChecker.snapshot(this)
        if (!status.overlayGranted || !status.notificationsGranted) {
            persistOverlayModeEnabled(false)
            OverlayService.stop(this)
        }
    }

    /**
     * Modo Teclado (KBD): o toggle reflete se o KeyFlow é o teclado ativo. Ativar com o teclado já
     * habilitado abre o seletor na hora; desativar abre o seletor para trocar. Se o teclado ainda não
     * está habilitado, não faz nada aqui — a ConfigScreen mostra o toast e abre as configs do teclado
     * sozinha logo em seguida (mesmo padrão do Modo Sobrepor). A efetivação do toggle em si fica a
     * cargo de [syncKeyboardModeToActive] quando a janela reganha foco.
     */
    private fun onKeyboardModeChange(enabled: Boolean) {
        if (enabled) {
            pendingKeyboardEnable = true
            keyboardStepAttempted = false
            if (permissionsChecker.snapshot(this).keyboardEnabled) {
                activateKeyboardNow()
            } else {
                // A ConfigScreen mostra o toast e abre as configs do teclado (1ª tentativa) sozinha —
                // marca já de cara, pra saber que a próxima retomada de foco é O RETORNO dessa tentativa.
                keyboardStepAttempted = true
            }
        } else {
            pendingKeyboardEnable = false
            keyboardStepAttempted = false
            pendingKeyboardDisable = true
            openKeyboardPicker()
        }
    }

    /**
     * Continua a ativação pendente do Modo Teclado: abre o seletor sozinho assim que o teclado for
     * habilitado nas configs do sistema (sem exigir um 2º toque no toggle). Se voltou e o teclado ainda
     * não foi habilitado, cancela em vez de reabrir as configs sozinho de novo (evita o loop).
     */
    private fun tryActivateKeyboard() {
        if (!pendingKeyboardEnable) return
        if (permissionsChecker.snapshot(this).keyboardEnabled) {
            activateKeyboardNow()
        } else if (keyboardStepAttempted) {
            pendingKeyboardEnable = false
            keyboardStepAttempted = false
        }
    }

    /** Abre o seletor de teclados (pra selecionar o KeyFlow como ativo). O microfone NÃO é encadeado
     * aqui — o usuário ativa quando quiser, tocando o chip dele (Yan, 2026-07-04). */
    private fun activateKeyboardNow() {
        pendingKeyboardEnable = false
        keyboardStepAttempted = false
        openKeyboardPicker()
    }

    /** Mantém `keyboardModeEnabled` em sincronia com o teclado realmente ativo (DEFAULT_INPUT_METHOD). */
    private fun syncKeyboardModeToActive() {
        // Nunca persistir antes do DataStore carregar: `settings` ainda seria o default e o `copy`
        // sobrescreveria toda a config (token/endpoint/source) com valores vazios.
        if (!settingsLoaded) return
        if (settings.keyboardModeEnabled != permissions.keyboardActive) {
            persistKeyboardModeEnabled(permissions.keyboardActive)
        }
    }

    /** Gerenciador de modelos só acompanha disco/downloads com a tela visível (polling de 500 ms). */
    override fun onStart() {
        super.onStart()
        localModelsJob = activityScope.launch { localModels.snapshots().collect { localSnapshot = it } }
    }

    override fun onStop() {
        localModelsJob?.cancel()
        localModelsJob = null
        super.onStop()
    }

    override fun onDestroy() {
        monetizationJobs.forEach(Job::cancel)
        entitlementsRepository?.close()
        remoteConfigRepository?.close()
        adsController?.close()
        interstitialPolicyStore?.close()
        billingManager?.close()
        activityScope.cancel()
        super.onDestroy()
    }

    /** Inicializa o grafo de compra somente na configuração, nunca durante o onboarding. */
    private fun startMonetizationIfNeeded() {
        if (monetizationStarted) return
        monetizationStarted = true
        val api = KeyFlowApiClient(this, http = auth.http)
        monetizationApi = api
        val billing = BillingManager(this, redeemPurchase = { purchase -> api.redeem(purchase) })
        val repository = EntitlementsRepository(this, api, billing)
        val remote = RemoteConfigRepository(this, http = auth.http)
        val ads = AdsController(this)
        val policyStore = DataStoreInterstitialPolicyStore(this)
        billingManager = billing
        entitlementsRepository = repository
        remoteConfigRepository = remote
        adsController = ads
        interstitialPolicyStore = policyStore
        monetizationJobs += activityScope.launch {
            val policy = InterstitialPolicy(
                params = remoteSnapshot.config.ads.toInterstitialPolicyParams(),
                initialState = policyStore.read(),
            )
            interstitialPolicy = policy
            policyStore.write(policy.snapshot())
        }
        monetizationJobs += activityScope.launch {
            repository.entitlements.collect { value ->
                entitlements = value
                if (value.skinsUnlocked) unlockPurchaseState = UnlockPurchaseState.IDLE
                if (entitlementsLoaded) settingsRepository.saveSkinsUnlocked(value.skinsUnlocked)
            }
        }
        monetizationJobs += activityScope.launch {
            billing.purchases.drop(1).collectLatest { purchases ->
                if (purchases.any { it.products.contains(MonetizationProducts.UNLOCK_FOREVER) && it.state == StorePurchaseState.PENDING }) {
                    unlockPurchaseState = UnlockPurchaseState.PENDING
                }
                val refreshed = repository.refresh()
                entitlements = refreshed
                settingsRepository.saveSkinsUnlocked(refreshed.skinsUnlocked)
                entitlementsLoaded = true
            }
        }
        monetizationJobs += activityScope.launch {
            billing.connect()
            val refreshed = repository.refresh()
            entitlements = refreshed
            settingsRepository.saveSkinsUnlocked(refreshed.skinsUnlocked)
            entitlementsLoaded = true
        }
        monetizationJobs += activityScope.launch {
            val snapshot = remote.refresh()
            remoteSnapshot = snapshot
            remoteConfigLoaded = true
            interstitialPolicy?.updateParams(snapshot.config.ads.toInterstitialPolicyParams())
            ModelCatalog.applyRemoteManifest(snapshot.models)
        }
    }

    private fun persistInterstitialPolicy() {
        val policy = interstitialPolicy ?: return
        val store = interstitialPolicyStore ?: return
        activityScope.launch { store.write(policy.snapshot()) }
    }

    private fun buyUnlock() {
        val billing = billingManager ?: run {
            unlockPurchaseState = UnlockPurchaseState.UNAVAILABLE
            return
        }
        if (unlockPurchaseState == UnlockPurchaseState.PURCHASING) return
        interstitialPolicy?.markPurchaseStarted()
        persistInterstitialPolicy()
        unlockPurchaseState = UnlockPurchaseState.PURCHASING
        monetizationJobs += activityScope.launch {
            val result = runCatching {
                monetizationApi?.resumeRegistrationAfterDeletion()
                billing.purchase(this@SetupActivity, MonetizationProducts.UNLOCK_FOREVER)
            }
                .getOrElse {
                    unlockPurchaseState = UnlockPurchaseState.ERROR
                    return@launch
                }
            unlockPurchaseState = when {
                result.ok -> UnlockPurchaseState.PENDING
                result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> UnlockPurchaseState.IDLE
                else -> UnlockPurchaseState.ERROR
            }
        }
    }

    /** Persiste a transformação do estado e sinaliza "salvo" (CFG-04/05). */
    private fun persist(transform: (Settings) -> Settings) {
        // Rede de segurança: nunca salvar antes do DataStore carregar (senão `settings` é o default e o
        // `copy` apagaria a config). Toda a UI só aparece após `settingsLoaded`, então isto não bloqueia
        // nenhuma escrita legítima do usuário.
        if (!settingsLoaded) return
        val updated = transform(settings)
        settings = updated
        activityScope.launch {
            settingsRepository.saveSettings(updated)
            saveController.notifySaved()
        }
    }

    private fun persistOnboardingDone(done: Boolean) {
        if (!settingsLoaded) return
        settings = settings.copy(onboardingDone = done)
        activityScope.launch {
            settingsRepository.saveOnboardingDone(done)
            saveController.notifySaved()
        }
    }

    private fun persistOverlayModeEnabled(enabled: Boolean) {
        if (!settingsLoaded) return
        settings = settings.copy(overlayModeEnabled = enabled)
        activityScope.launch {
            settingsRepository.saveOverlayModeEnabled(enabled)
            saveController.notifySaved()
        }
    }

    private fun persistKeyboardModeEnabled(enabled: Boolean) {
        if (!settingsLoaded) return
        settings = settings.copy(keyboardModeEnabled = enabled)
        activityScope.launch {
            settingsRepository.saveKeyboardModeEnabled(enabled)
            saveController.notifySaved()
        }
    }

    /**
     * Liga/desliga o Modo Overlay: persiste o flag (gating da aba) e inicia/encerra o [OverlayService].
     * Ligar exige sobrepor + notificações concedidas — sem isso o toggle nem chega a marcar (o flag não
     * persiste) e o aviso é o toast Compose da ConfigScreen, que também abre a permissão pendente sozinha
     * logo em seguida. O microfone NÃO entra nessa trava: a orbe funciona mesmo sem mic (atalhos/gestos
     * que não gravam) — falta só pulsar o chip de Microfone, igual já acontece no card do Teclado (Yan,
     * 2026-07-04). Sem essa trava do sobrepor/notificações, o flag persistia mesmo sem permissão e o
     * serviço nunca subia, sem nenhum retorno visual (a orbe simplesmente não aparecia).
     *
     * Se faltar mais de uma permissão, o usuário só toca o toggle UMA vez: essa função só marca a
     * ativação como pendente (a ConfigScreen pede a 1ª que falta, pouco depois do toast) e
     * [tryActivateOverlay] cuida do resto — pede a 2ª que falta e só então ativa de vez — quando a
     * janela reganhar foco (Yan, 2026-07-04: antes era preciso tocar o toggle de novo a cada permissão).
     */
    private fun onOverlayModeChange(enabled: Boolean) {
        if (enabled) {
            pendingOverlayEnable = true
            val status = permissionsChecker.snapshot(this)
            when {
                status.overlayGranted && status.notificationsGranted -> activateOverlayNow()
                // A ConfigScreen já pede a 1ª pendente (sobrepor ou notificações) sozinha, pouco depois
                // do toast — registra qual passo é esse, pra reconhecer o RETORNO dessa tentativa.
                !status.overlayGranted -> lastOverlayBlockingStep = "overlay"
                else -> lastOverlayBlockingStep = "notifications"
            }
        } else {
            pendingOverlayEnable = false
            lastOverlayBlockingStep = null
            persistOverlayModeEnabled(false)
            OverlayService.stop(this)
        }
    }

    /**
     * Continua a ativação pendente do Modo Sobrepor quando a janela reganha foco: pede a PRÓXIMA
     * permissão que falta (sobrepor → notificações), ou ativa de vez se as duas já estiverem OK. Cada
     * passo só é tentado UMA vez — se a janela reganha foco e ainda é O MESMO passo pendente, cancela
     * (só retoma se o usuário tocar o toggle de novo); se o passo MUDOU (o anterior foi resolvido), faz
     * a 1ª tentativa do novo.
     */
    private fun tryActivateOverlay() {
        if (!pendingOverlayEnable) return
        val status = permissionsChecker.snapshot(this)
        val blockingStep = when {
            !status.overlayGranted -> "overlay"
            !status.notificationsGranted -> "notifications"
            else -> null
        }
        if (blockingStep == null) {
            activateOverlayNow()
            return
        }
        if (blockingStep == lastOverlayBlockingStep) {
            // Voltou sem resolver o MESMO passo: cancela, sem reabrir nada sozinho.
            pendingOverlayEnable = false
            lastOverlayBlockingStep = null
            continueOnboardingKeyboardChainIfNeeded()
            return
        }
        lastOverlayBlockingStep = blockingStep
        // "overlay" já foi pedido pela ConfigScreen (configs do sistema); só "notifications" precisa de
        // ação daqui. Mesma regra do clique manual no chip: tenta o popup se o sistema ainda mostrar: se
        // já foi negada permanentemente (o Android simplesmente para de mostrar o diálogo — não é bug
        // nosso), cai pras configs do app, senão o encadeamento nunca conseguiria terminar (Yan,
        // 2026-07-04). Não trava: se a pessoa voltar sem liberar, o passo é cancelado no próximo retorno
        // (ver o `if (blockingStep == lastOverlayBlockingStep)` acima).
        if (blockingStep == "notifications" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestOrOpenSettings(Manifest.permission.POST_NOTIFICATIONS, requestNotificationPermission)
        }
    }

    /** Sobe o serviço. O microfone NÃO é encadeado aqui — o usuário ativa quando quiser, tocando o chip
     * dele (Yan, 2026-07-04). */
    private fun activateOverlayNow() {
        pendingOverlayEnable = false
        lastOverlayBlockingStep = null
        persistOverlayModeEnabled(true)
        OverlayService.start(this, OverlayService.startOverlayIntent(this))
        continueOnboardingKeyboardChainIfNeeded()
    }

    /**
     * Chip do microfone (aba Modo, nos dois cards): se ainda não concedido, pede em diálogo; se já
     * concedido, abre os detalhes do app para revisar/revogar — assim o chip "abre as configs" em
     * qualquer estado. Usa [requestOrOpenSettings] pra continuar funcionando mesmo negada antes.
     */
    private fun onMicChipClicked() {
        if (!DisclosureConsent.fromStoredVersion(settings.disclosureAcceptedVersion).isAcceptedForCurrentVersion()) {
            showDisclosure = true
            return
        }
        if (permissions.microphoneGranted) {
            openAppDetailsSettings()
        } else {
            requestOrOpenSettings(Manifest.permission.RECORD_AUDIO, requestMicPermission)
        }
    }

    /** Item "Sobreposição" do checklist do onboarding (clique individual, não é o botão de encadear tudo). */
    private fun onOverlayChipClickedInOnboarding() {
        openOverlaySettings()
    }

    /** Item "Teclado" do checklist do onboarding (clique individual, não é o botão de encadear tudo). */
    private fun onKeyboardChipClickedInOnboarding() {
        openKeyboardSettings()
    }

    /** Chip de notificações (aba Modo, card Sobrepor): mesmo padrão do [onMicChipClicked]. */
    private fun onNotificationChipClicked() {
        if (permissions.notificationsGranted) {
            openAppDetailsSettings()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestOrOpenSettings(Manifest.permission.POST_NOTIFICATIONS, requestNotificationPermission)
        }
    }

    /**
     * Pede a permissão de novo; se o sistema não for mais mostrar o diálogo (negada permanentemente —
     * "não perguntar de novo" ou 2ª recusa), abre as configs do app em vez de falhar em silêncio. Sem
     * isso, negar uma vez "travava" o chip/a retomada automática para sempre (Yan, 2026-07-04: nem
     * clicar no chip fazia algo depois de negar) — o Sobrepor nunca tinha esse problema por ser configs
     * do sistema (`ACTION_MANAGE_OVERLAY_PERMISSION`), sempre reabrível; agora mic/notificações também.
     */
    private fun requestOrOpenSettings(
        permission: String,
        launcher: androidx.activity.result.ActivityResultLauncher<String>,
    ) {
        if (shouldShowRequestPermissionRationale(permission)) {
            launcher.launch(permission)
        } else {
            openAppDetailsSettings()
        }
    }

    /**
     * Marca a saída pra uma tela de configs do sistema como se já fosse o último "retorno ao
     * primeiro plano" processado — alimenta o mesmo debounce de [onForegroundRegained]. Sem isso, um
     * evento de foco espúrio que às vezes dispara logo após o `startActivity()` abaixo (durante a
     * própria transição de tela, antes do usuário ver a tela nova) era lido como "voltou sem resolver
     * a MESMA pendência" e cancelava a ativação na hora — visto ao vivo no device: abrir a tela de
     * sobrepor e, 37ms depois, já pular pra tela de teclado sem o usuário ter feito nada (Yan,
     * 2026-07-05, banner de onboarding).
     */
    private fun startSettingsScreen(intent: Intent) {
        foregroundReturnGate.markExternalSurfaceOpened(SystemClock.elapsedRealtime())
        startActivity(intent)
    }

    /** Abre a tela de detalhes/permissões do app no sistema. */
    private fun openAppDetailsSettings() {
        startSettingsScreen(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    /** Abre as configurações de "desenhar sobre outros apps" (permissão do overlay). */
    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startSettingsScreen(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    /** Abre as configurações de teclados do sistema para habilitar o IME do KeyFlow. */
    private fun openKeyboardSettings() {
        startSettingsScreen(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
    }

    /** Abre o seletor de teclados do sistema para o usuário tornar o KeyFlow o teclado ativo. */
    private fun openKeyboardPicker() {
        // Chamar showInputMethodPicker() direto de dentro de onWindowFocusChanged (via a cadeia
        // tryActivateKeyboard -> activateKeyboardNow) é cedo demais: a Activity já sabe que tem foco
        // (hasWindowFocus()==true), mas o InputMethodManagerService ainda não processou essa mudança de
        // foco no lado dele (chega por Binder assíncrono) — o pedido do picker é descartado em silêncio,
        // sem erro nem log. Adiar pro próximo laço do looper dá tempo desse foco propagar (Yan,
        // 2026-07-05: confirmado ao vivo no device — hasWindowFocus=true no log, mas o seletor não abria).
        window.decorView.post {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showInputMethodPicker()
        }
    }

    companion object {
        private const val EXTRA_NAV = "com.keyflow.nav"
        private const val EXTRA_REQUIRE_DISCLOSURE = "com.keyflow.require_disclosure"
        private const val NAV_TAB_VOZ = "tab_voz"
        private const val NAV_TAB_OVERLAY_MIC = "tab_overlay_mic"
        private const val NAV_TAB_KEYBOARD_MIC = "tab_keyboard_mic"

        /** Abre o app já na aba Voz (clique no estado de erro resolvível lá — config/token). */
        fun openAtVozIntent(context: android.content.Context): Intent =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_NAV, NAV_TAB_VOZ)

        /**
         * Abre o app na aba da interface que avisou da falta de microfone ([ConfigTab.OVERLAY] pela orbe,
         * [ConfigTab.TECLADO] pelo teclado), já no cartão do modo, onde o chip do microfone pulsa.
         */
        fun openAtMicIntent(context: android.content.Context, tab: ConfigTab): Intent =
            Intent(context, SetupActivity::class.java).putExtra(
                EXTRA_NAV,
                if (tab == ConfigTab.TECLADO) NAV_TAB_KEYBOARD_MIC else NAV_TAB_OVERLAY_MIC,
            )

        fun openDisclosureIntent(context: android.content.Context): Intent =
            Intent(context, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_REQUIRE_DISCLOSURE, true)
    }
}
