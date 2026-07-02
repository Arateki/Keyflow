package com.keyflow.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.viewinterop.AndroidView
import com.keyflow.core.ButtonState
import com.keyflow.core.TranscriptionSource
import android.widget.FrameLayout
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.overlay.RecordingNotification
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
        orbRoute("orb-paused", OrbState.Paused, OrbCenter.PauseGlyph(8)),
        orbRoute("orb-transcribing", OrbState.Transcribing, OrbCenter.None),
        // Apresentação do orbe no botão flutuante do overlay (T22): tempo/glifo no centro.
        orbRoute("overlay-idle", OrbState.Idle, OrbCenter.None),
        orbRoute("overlay-recording", OrbState.Listening, OrbCenter.Time(12)),
        orbRoute("overlay-paused", OrbState.Paused, OrbCenter.PauseGlyph(12)),
        // Teclado reskinado (T23): View hospedado via AndroidView, ancorado ao rodapé.
        keyboardRoute("keyboard-expanded", expanded = true, recording = false),
        keyboardRoute("keyboard-collapsed", expanded = false, recording = false),
        keyboardRoute("keyboard-recording", expanded = true, recording = true),
        // Grade Canvas nova (Trabalho 2, KC-T10) — golden oficial do híbrido (mic/action row antigos + grade nova).
        keyboardRoute("keyboard-canvas-expanded", expanded = true, recording = false, useCanvasGrid = true),
        keyboardRoute("keyboard-canvas-collapsed", expanded = false, recording = false, useCanvasGrid = true),
        keyboardCanvasSymbolsRoute("keyboard-canvas-symbols"),
        keyboardCanvasCandidatesRoute("keyboard-canvas-candidates"),
        // Notificação Ateliê (T24): RemoteViews inflada via AndroidView.
        notificationRoute("notification-recording", ButtonState.RECORDING),
        notificationRoute("notification-paused", ButtonState.PAUSED),
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
            onRequestMicPermission = {},
            onSwitchKeyboard = {},
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
            onRequestMicPermission = {},
            onSwitchKeyboard = {},
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
            onRequestMicPermission = {},
            onSwitchKeyboard = {},
            onSettingsChange = {},
            initialTab = tab,
            initialUnlockOpen = unlockOpen,
        )
    }

    /** Hospeda o [KeyFlowKeyboardView] (View) numa rota Compose, ancorado ao rodapé (KBD screenshots). */
    private fun keyboardRoute(
        key: String,
        expanded: Boolean,
        recording: Boolean,
        useCanvasGrid: Boolean = false,
    ): Route = Route(key) {
        val dark = LocalKeyFlowPalette.current.isDark
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom),
                factory = { ctx ->
                    KeyFlowKeyboardView(
                        context = ctx,
                        onKey = {},
                        onMic = {},
                        onHide = {},
                        dark = dark,
                        useCanvasGrid = useCanvasGrid,
                        frozenSeconds = 12,
                    ).apply {
                        setExpanded(expanded)
                        if (recording) updateMicState(ButtonState.RECORDING)
                    }
                },
            )
        }
    }

    /** Golden da camada `?123` (KC-T10): mesma grade híbrida, mas já trocada pra símbolos. */
    private fun keyboardCanvasSymbolsRoute(key: String): Route = Route(key) {
        val dark = LocalKeyFlowPalette.current.isDark
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom),
                factory = { ctx ->
                    KeyFlowKeyboardView(
                        context = ctx,
                        onKey = {},
                        onMic = {},
                        onHide = {},
                        dark = dark,
                        useCanvasGrid = true,
                        frozenSeconds = 12,
                    ).apply {
                        setExpanded(true)
                        // Troca pra ?123 via toque sintético — a única forma de layer switch é o gesto real
                        // (sem accessor de estado), então simulamos assim que a geometria estiver medida.
                        canvasGridForTest?.post { tapLayerToggle(canvasGridForTest!!) }
                    }
                },
            )
        }
    }

    /** Golden da faixa de sugestões (KC-T10/SPL-01): candidatos fixos, sem depender do corretor real. */
    private fun keyboardCanvasCandidatesRoute(key: String): Route = Route(key) {
        val dark = LocalKeyFlowPalette.current.isDark
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom),
                factory = { ctx ->
                    KeyFlowKeyboardView(
                        context = ctx,
                        onKey = {},
                        onMic = {},
                        onHide = {},
                        dark = dark,
                        useCanvasGrid = true,
                        frozenSeconds = 12,
                    ).apply {
                        setExpanded(true)
                        updateCandidates(listOf("olá", "olar", "olé"))
                    }
                },
            )
        }
    }

    /** Toca a tecla de troca de camada (?123/ABC) — sempre a primeira da 4ª fileira da grade Canvas. */
    private fun tapLayerToggle(grid: com.keyflow.ime.keyboard.KeyboardGridView) {
        val placed = grid.placedRowsForTest.lastOrNull()?.keys?.firstOrNull() ?: return
        val cx = (placed.left + placed.right) / 2
        val cy = (placed.top + placed.bottom) / 2
        val time = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, cx, cy, 0)
        val up = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_UP, cx, cy, 0)
        grid.dispatchTouchEvent(down)
        grid.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /** Notificação de gravação (RemoteViews inflada) sobre uma "persiana" simulada (KBD screenshots). */
    private fun notificationRoute(key: String, state: ButtonState): Route = Route(key) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.TopCenter,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Top),
                factory = { ctx ->
                    RecordingNotification(ctx, "gallery")
                        .galleryRemoteViews(state, frozenSeconds = 12)
                        .apply(ctx, FrameLayout(ctx))
                },
            )
        }
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
