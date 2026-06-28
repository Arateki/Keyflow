package com.keyflow.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keyflow.core.TranscriptionSource
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.config.ConfigScreen
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.onboarding.OnboardingScreen
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette

/**
 * Única fonte de verdade das rotas de verificação (VERIFY-05). Cada rota é uma tela/estado isolado,
 * renderizado deterministicamente pela [GalleryActivity]. Telas reais (config/onboarding/…) entram
 * aqui conforme as tasks T10–T24; nesta etapa só as amostras do orbe são renderizáveis.
 */
data class Route(val key: String, val content: @Composable () -> Unit)

/** Tempo fixo do orbe no modo galeria → frames determinísticos (VERIFY-01). */
const val GALLERY_ORB_TIME_MS: Float = 1234f

object RouteCatalog {

    val routes: List<Route> = listOf(
        orbRoute("orb-idle", OrbState.Idle, OrbCenter.None),
        orbRoute("orb-listening", OrbState.Listening, OrbCenter.None),
        orbRoute("orb-paused", OrbState.Paused, OrbCenter.PauseGlyph),
        orbRoute("orb-transcribing", OrbState.Transcribing, OrbCenter.None),
        // Apresentação do orbe no botão flutuante do overlay (T22): tempo/glifo no centro.
        orbRoute("overlay-idle", OrbState.Idle, OrbCenter.None),
        orbRoute("overlay-recording", OrbState.Listening, OrbCenter.Time(12)),
        orbRoute("overlay-paused", OrbState.Paused, OrbCenter.PauseGlyph),
        Route("config-modo") { ConfigModoRoute() },
        Route("config-voz-creditos") { ConfigVozRoute(TranscriptionSource.CREDITS) },
        Route("config-voz-openai") { ConfigVozRoute(TranscriptionSource.OPENAI) },
        Route("config-voz-custom") { ConfigVozRoute(TranscriptionSource.CUSTOM) },
        Route("config-teclado") { ConfigTabRoute(ConfigTab.TECLADO, keyboardEnabled = true) },
        Route("config-overlay") { ConfigTabRoute(ConfigTab.OVERLAY, overlayEnabled = true) },
        Route("config-docs") { ConfigTabRoute(ConfigTab.DOCS) },
        Route("config-unlock") { ConfigTabRoute(ConfigTab.TECLADO, keyboardEnabled = true, unlockOpen = true) },
        Route("onboarding") {
            OnboardingScreen(
                permissions = PermissionStatus(
                    microphoneGranted = true,
                    overlayGranted = true,
                    keyboardEnabled = false, // espelha o mock (Teclado FALTA)
                    notificationsGranted = true,
                ),
                onGrant = {},
                onSkip = {},
            )
        },
    )

    private val byKey: Map<String, Route> = routes.associateBy { it.key }

    fun route(key: String): Route? = byKey[key]

    val keys: List<String> get() = routes.map { it.key }

    /** Aba Modo do scaffold de configuração (T10), com modos ligados para mostrar gating liberado. */
    @Composable
    private fun ConfigModoRoute() {
        ConfigScreen(
            settings = FakeSettings.settings.copy(overlayModeEnabled = true, keyboardModeEnabled = true),
            permissions = PermissionStatus(
                microphoneGranted = true,
                overlayGranted = true,
                keyboardEnabled = false, // espelha o mock (teclado PENDENTE + "Ativar")
                notificationsGranted = true,
            ),
            saveController = SaveController(),
            onOverlayModeChange = {},
            onKeyboardModeChange = {},
            onThemeChange = {},
            onLanguageClick = {},
            onRequestOverlayPermission = {},
            onRequestKeyboardPermission = {},
            onSettingsChange = {},
        )
    }

    /** Aba Voz por origem (Créditos/OpenAI/Custom) — espelha os mocks 02-v2/03-v2. */
    @Composable
    private fun ConfigVozRoute(source: TranscriptionSource) {
        ConfigScreen(
            settings = FakeSettings.settings.copy(source = source),
            permissions = PermissionStatus(
                microphoneGranted = true,
                overlayGranted = true,
                keyboardEnabled = true,
                notificationsGranted = true,
            ),
            saveController = SaveController(),
            onOverlayModeChange = {},
            onKeyboardModeChange = {},
            onThemeChange = {},
            onLanguageClick = {},
            onRequestOverlayPermission = {},
            onRequestKeyboardPermission = {},
            onSettingsChange = {},
            initialTab = ConfigTab.VOZ,
        )
    }

    /** Abre uma aba arbitrária do config (modos ligados conforme necessário) p/ os mocks 04/05-v2. */
    @Composable
    private fun ConfigTabRoute(
        tab: ConfigTab,
        overlayEnabled: Boolean = false,
        keyboardEnabled: Boolean = false,
        unlockOpen: Boolean = false,
    ) {
        ConfigScreen(
            settings = FakeSettings.settings.copy(
                overlayModeEnabled = overlayEnabled,
                keyboardModeEnabled = keyboardEnabled,
            ),
            permissions = PermissionStatus(
                microphoneGranted = true,
                overlayGranted = true,
                keyboardEnabled = keyboardEnabled,
                notificationsGranted = true,
            ),
            saveController = SaveController(),
            onOverlayModeChange = {},
            onKeyboardModeChange = {},
            onThemeChange = {},
            onLanguageClick = {},
            onRequestOverlayPermission = {},
            onRequestKeyboardPermission = {},
            onSettingsChange = {},
            initialTab = tab,
            initialUnlockOpen = unlockOpen,
        )
    }

    private fun orbRoute(key: String, state: OrbState, center: OrbCenter): Route = Route(key) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.Center,
        ) {
            VoiceOrb(
                state = state,
                modifier = Modifier.size(160.dp),
                center = center,
                timeProvider = { GALLERY_ORB_TIME_MS },
            )
        }
    }
}
