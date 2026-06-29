package com.keyflow.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.config.ConfigScreen
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.onboarding.OnboardingScreen
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
        // Independente do resultado, conclui o onboarding; permissões finas ficam na aba Modo.
        permissions = permissionsChecker.snapshot(this)
        markOnboardingDone()
    }

    // Pedido pontual do microfone na aba Modo — só atualiza o snapshot, não mexe no onboarding.
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        permissions = permissionsChecker.snapshot(this)
    }

    private var settings by mutableStateOf(Settings())
    // Evita piscar o onboarding antes do 1º valor do DataStore chegar (ONB-03).
    private var settingsLoaded by mutableStateOf(false)
    // Desligar o Modo Teclado abre o seletor; só efetiva se o KeyFlow deixar de ser o teclado ativo.
    private var pendingKeyboardDisable = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        handleNavIntent(intent)

        activityScope.launch {
            settingsRepository.settingsFlow().collectLatest {
                settings = it
                settingsLoaded = true
            }
        }

        setContent {
            // Tema Ateliê app-wide; rota inicial: 1º uso → onboarding, depois → config (T27).
            KeyFlowTheme(dark = settings.themeDark) {
                if (!settingsLoaded) {
                    // Placeholder neutro enquanto o DataStore carrega — evita o flash do onboarding.
                    Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg))
                } else if (settings.onboardingDone) {
                    ConfigScreen(
                        settings = settings,
                        permissions = permissions,
                        saveController = saveController,
                        onOverlayModeChange = ::onOverlayModeChange,
                        onKeyboardModeChange = ::onKeyboardModeChange,
                        onThemeChange = { dark -> persist { it.copy(themeDark = dark) } },
                        onLanguageClick = {
                            Toast.makeText(this, "Outros idiomas em breve", Toast.LENGTH_SHORT).show()
                        },
                        onRequestOverlayPermission = ::openOverlaySettings,
                        onRequestKeyboardPermission = ::openKeyboardSettings,
                        onRequestMicPermission = ::onMicChipClicked,
                        onSwitchKeyboard = ::openKeyboardPicker,
                        onSettingsChange = { updated -> persist { updated } },
                        requestedTab = requestedTab,
                        requestedTabKey = requestedTabKey,
                    )
                } else {
                    OnboardingScreen(
                        permissions = permissions,
                        onGrant = ::requestRuntimePermissions,
                        onSkip = ::markOnboardingDone,
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

    /** Marca o onboarding como concluído (ONB-03) → a próxima composição vai para a config. */
    private fun markOnboardingDone() {
        persist { it.copy(onboardingDone = true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent(intent)
    }

    /** Lê extras de navegação: pular para a aba Voz (clique no erro). */
    private fun handleNavIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_NAV) == NAV_TAB_VOZ) {
            requestedTab = ConfigTab.VOZ
            requestedTabKey++
        }
    }

    override fun onResume() {
        super.onResume()
        permissions = permissionsChecker.snapshot(this)
        syncKeyboardModeToActive()
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
                    "KeyFlow ainda é o teclado ativo — troque para desativar.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        syncKeyboardModeToActive()
    }

    /**
     * Modo Teclado (KBD): o toggle reflete se o KeyFlow é o teclado ativo. Ativar abre o seletor (ou as
     * configs de teclado se ainda não habilitado); desativar abre o seletor para trocar. A efetivação
     * fica a cargo de [syncKeyboardModeToActive] quando a janela reganha foco.
     */
    private fun onKeyboardModeChange(enabled: Boolean) {
        if (enabled) {
            val status = permissionsChecker.snapshot(this)
            if (!status.keyboardEnabled) openKeyboardSettings() else openKeyboardPicker()
        } else {
            pendingKeyboardDisable = true
            openKeyboardPicker()
        }
    }

    /** Mantém `keyboardModeEnabled` em sincronia com o teclado realmente ativo (DEFAULT_INPUT_METHOD). */
    private fun syncKeyboardModeToActive() {
        // Nunca persistir antes do DataStore carregar: `settings` ainda seria o default e o `copy`
        // sobrescreveria toda a config (token/endpoint/source) com valores vazios.
        if (!settingsLoaded) return
        if (settings.keyboardModeEnabled != permissions.keyboardActive) {
            persist { it.copy(keyboardModeEnabled = permissions.keyboardActive) }
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    /** Persiste a transformação do estado e sinaliza "salvo" (CFG-04/05). */
    private fun persist(transform: (Settings) -> Settings) {
        // Rede de segurança: nunca salvar antes do DataStore carregar (senão `settings` é o default e o
        // `copy` apagaria a config). Toda a UI só aparece após `settingsLoaded`, então isto não bloqueia
        // nenhuma escrita legítima do usuário.
        if (!settingsLoaded) return
        activityScope.launch {
            settingsRepository.saveSettings(transform(settings))
            saveController.notifySaved()
        }
    }

    /**
     * Liga/desliga o Modo Overlay: persiste o flag (gating da aba) e inicia/encerra o [OverlayService].
     * Ao ligar sem permissões não inicia o serviço (o aviso ao usuário é o toast Compose da ConfigScreen;
     * o flag ainda persiste para reconciliar quando as permissões chegarem).
     */
    private fun onOverlayModeChange(enabled: Boolean) {
        persist { it.copy(overlayModeEnabled = enabled) }
        if (enabled) {
            val status = permissionsChecker.snapshot(this)
            if (!status.microphoneGranted || !status.overlayGranted || !status.notificationsGranted) {
                return
            }
            val intent = OverlayService.startOverlayIntent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            stopService(OverlayService.intent(this))
        }
    }

    /**
     * Chip do microfone (aba Modo): se ainda não concedido, pede em diálogo (1 toque); se já concedido,
     * abre os detalhes do app para revisar/revogar — assim o chip "abre as configs" em qualquer estado.
     */
    private fun onMicChipClicked() {
        if (permissions.microphoneGranted) {
            openAppDetailsSettings()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Abre a tela de detalhes/permissões do app no sistema. */
    private fun openAppDetailsSettings() {
        startActivity(
            Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    /** Abre as configurações de "desenhar sobre outros apps" (permissão do overlay). */
    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    /** Abre as configurações de teclados do sistema para habilitar o IME do KeyFlow. */
    private fun openKeyboardSettings() {
        startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
    }

    /** Abre o seletor de teclados do sistema para o usuário tornar o KeyFlow o teclado ativo. */
    private fun openKeyboardPicker() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.showInputMethodPicker()
    }

    companion object {
        private const val EXTRA_NAV = "com.keyflow.nav"
        private const val NAV_TAB_VOZ = "tab_voz"

        /** Abre o app já na aba Voz (clique no estado de erro). */
        fun openAtVozIntent(context: android.content.Context): Intent =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_NAV, NAV_TAB_VOZ)
    }
}
