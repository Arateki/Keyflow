package com.keyflow.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.config.ConfigScreen
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

    private var settings by mutableStateOf(Settings())
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

        activityScope.launch {
            settingsRepository.settingsFlow().collectLatest { settings = it }
        }

        setContent {
            KeyFlowTheme(dark = settings.themeDark) {
                ConfigScreen(
                    settings = settings,
                    permissions = permissions,
                    saveController = saveController,
                    onOverlayModeChange = ::onOverlayModeChange,
                    onKeyboardModeChange = { enabled -> persist { it.copy(keyboardModeEnabled = enabled) } },
                    onThemeChange = { dark -> persist { it.copy(themeDark = dark) } },
                    onLanguageClick = {
                        Toast.makeText(this, "Outros idiomas em breve", Toast.LENGTH_SHORT).show()
                    },
                    onRequestOverlayPermission = ::openOverlaySettings,
                    onRequestKeyboardPermission = ::openKeyboardSettings,
                    onSettingsChange = { updated -> persist { updated } },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissions = permissionsChecker.snapshot(this)
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    /** Persiste a transformação do estado e sinaliza "salvo" (CFG-04/05). */
    private fun persist(transform: (Settings) -> Settings) {
        activityScope.launch {
            settingsRepository.saveSettings(transform(settings))
            saveController.notifySaved()
        }
    }

    /**
     * Liga/desliga o Modo Overlay: persiste o flag (gating da aba) e inicia/encerra o [OverlayService].
     * Ao ligar sem permissões, avisa e não inicia o serviço (o flag ainda persiste para reconciliar depois).
     */
    private fun onOverlayModeChange(enabled: Boolean) {
        persist { it.copy(overlayModeEnabled = enabled) }
        if (enabled) {
            val status = permissionsChecker.snapshot(this)
            if (!status.microphoneGranted || !status.overlayGranted || !status.notificationsGranted) {
                Toast.makeText(this, "Permissões pendentes", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = OverlayService.intent(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            stopService(OverlayService.intent(this))
        }
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
}
