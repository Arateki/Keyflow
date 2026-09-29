package com.keyflow.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
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
import com.keyflow.data.Settings
import com.keyflow.ui.tour.FingerPuppet
import com.keyflow.ui.tour.GestureTourPlayer
import com.keyflow.ui.tour.LocalTourClock
import com.keyflow.ui.tour.TourScene
import com.keyflow.ui.tour.buildKeyboardScripts
import com.keyflow.ui.tour.scenes.KeyboardScene
import com.keyflow.ui.tour.scenes.OrbScene
import com.keyflow.ui.tour.buildOrbScripts
import com.keyflow.ui.tour.tourFrameAt
import com.keyflow.core.TranscriptionSource
import android.view.KeyEvent
import android.widget.FrameLayout
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.GridCell
import com.keyflow.core.shortcuts.GridTriggerMode
import com.keyflow.core.shortcuts.PhysicalKeyMap
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.overlay.RecordingNotification
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.config.BarsEditor
import com.keyflow.ui.config.ConfigScreen
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.config.GridsEditor
import com.keyflow.ui.config.PhysicalKeyPicker
import com.keyflow.ui.onboarding.OnboardingPermission
import com.keyflow.ui.onboarding.OnboardingScreen
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbDragHint
import com.keyflow.ui.orb.OrbDragKind
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.config.LocalModelsUi
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.DownloadProgress

/**
 * Única fonte de verdade das rotas de verificação (VERIFY-05). Cada rota é uma tela/estado isolado,
 * renderizado deterministicamente pela [GalleryActivity]. Telas reais (config/onboarding/…) entram
 * aqui conforme as tasks T10–T24; nesta etapa só as amostras do orbe são renderizáveis.
 *
 * [minDelayMs] dá tempo extra antes do marcador `gallery_ready` (além dos 3 frames padrão) — só as
 * rotas que dependem de um estado assíncrono real (ex.: reveal por tempo parado da grade efêmera, SB-T10)
 * o usam; as demais mantêm o comportamento de sempre (0).
 */
data class Route(val key: String, val minDelayMs: Long = 0L, val content: @Composable () -> Unit)

/** Tempo fixo do orbe no modo galeria → frames determinísticos (VERIFY-01). */
const val GALLERY_ORB_TIME_MS: Float = 1234f

object RouteCatalog {

    val routes: List<Route> = listOf(
        orbRoute("orb-idle", OrbState.Idle, OrbCenter.None),
        orbRoute("orb-listening", OrbState.Listening, OrbCenter.None),
        orbRoute("orb-paused", OrbState.Paused, OrbCenter.PauseGlyph(8)),
        orbRoute("orb-transcribing", OrbState.Transcribing, OrbCenter.None),
        // Glifo-alvo do modo-arrasto travado (segurar+arrastar até a zona) — pedido do Yan, 2026-07-04:
        // print de cada estado do gesto sem precisar simular o toque (VoiceOrb já aceita o dragHint como
        // parâmetro puro do Compose).
        orbDragRoute("orb-drag-pause", OrbState.Listening, OrbDragKind.PAUSE, seconds = 8),
        orbDragRoute("orb-drag-resume", OrbState.Paused, OrbDragKind.RESUME, seconds = 8),
        orbDragRoute("orb-drag-cancel-recording", OrbState.Listening, OrbDragKind.CANCEL, seconds = 8),
        orbDragRoute("orb-drag-cancel-sending", OrbState.Transcribing, OrbDragKind.CANCEL, seconds = 8),
        orbDragRoute("orb-drag-config", OrbState.Listening, OrbDragKind.CONFIG, seconds = 8),

        // Sanity de render do dedo (GT-T2), num instante fixo do roteiro de cancelar/desfazer — o
        // passo de arrasto, que exercita trilha + posição interpolada de uma vez. As rotas de tour
        // de verdade (com as cenas reais) chegam na GT-T8.
        fingerPuppetRoute("tour-finger-drag", scriptId = "orb-cancel-undo", atMs = 900),
        fingerPuppetRoute("tour-finger-multitap", scriptId = "orb-shortcut-3tap", atMs = 700),
        // GT-T8 / MODO-05: o tour de verdade (player + cena real + dedo) num instante fixo de cada roteiro.
        // É também a prova de reuso do TOUR-01: fora da aba Modo, cada rota toca só um SUBCONJUNTO dos
        // roteiros e põe título/descrição embaixo da cena, onde a aba Modo não põe.
        tourRoute("tour-orb-basics", TourScene.ORB, listOf("orb-toggle-basics", "orb-toggle-pause"), atMs = 770),
        tourRoute("tour-orb-shortcuts", TourScene.ORB, listOf("orb-shortcut-2tap", "orb-shortcut-3tap"), atMs = 980),
        tourRoute("tour-keyboard-mic", TourScene.KEYBOARD, listOf("kbd-mic-hold", "kbd-mic-tap"), atMs = 1670),
        // Apresentação do orbe no botão flutuante do overlay (T22): tempo/glifo no centro.
        orbRoute("overlay-idle", OrbState.Idle, OrbCenter.None),
        orbRoute("overlay-recording", OrbState.Listening, OrbCenter.Time(12)),
        orbRoute("overlay-paused", OrbState.Paused, OrbCenter.PauseGlyph(12)),
        // Teclado reskinado (T23), grade Canvas (KC-T10, padrão único desde a promoção): View hospedado
        // via AndroidView, ancorado ao rodapé.
        keyboardRoute("keyboard-canvas-expanded", expanded = true, recording = false),
        keyboardRoute("keyboard-canvas-collapsed", expanded = false, recording = false),
        keyboardCanvasSymbolsRoute("keyboard-canvas-symbols"),
        keyboardCanvasCandidatesRoute("keyboard-canvas-candidates"),
        // Barras Canvas (SB-T10/ROL-03): paridade com a barra de fábrica + estados só alcançáveis por
        // gesto (grade revelada/alavanca), simulados via toque sintético (mesma técnica de
        // `tapLayerToggle`) já que a galeria não tem dedo de verdade.
        barRoute("bar-default-collapsed", expanded = false),
        barRoute("bar-default-expanded", expanded = true),
        barCustomOverflowRoute("bar-custom-overflow"),
        barGridRevealedRoute("bar-grid-revealed"),
        barJoystickPulledRoute("bar-joystick-pulled"),
        // Editor de barras: a lista de chips com os controles à direita, e o catálogo (modal) por cima.
        Route("bars-editor") {
            Box(Modifier.fillMaxSize()) {
                BarsEditor(
                    barsConfig = BarsConfig(
                        mirrored = true,
                        expanded = emptyList(),
                        collapsed = listOf(
                            BarDef(
                                listOf(
                                    BarButton.Mic,
                                    BarButton.ConfigBtn,
                                    BarButton.Paste,
                                    BarButton.SingleKey(PhysicalKeyMap.key(android.view.KeyEvent.KEYCODE_ESCAPE)),
                                    BarButton.Joystick,
                                ),
                            ),
                        ),
                    ),
                    onBarsConfigChange = {},
                    grids = emptyList(),
                )
            }
        },
        // Editor de grade no modal (Q8): grade grande o bastante pra exercitar o encolhimento de célula.
        // Formulário de gatilho de grade: a explicação sob cada modo, e o prefixo só fora do "Só grade".
        barsEditorGridFormRoute("bars-editor-grid-form", GridTriggerMode.OPEN_ONLY),
        barsEditorGridFormRoute("bars-editor-grid-form-sequencia", GridTriggerMode.SEQUENCE),
        Route("grids-editor-modal") {
            Box(Modifier.fillMaxSize()) {
                GridsEditor(
                    grids = listOf(
                        ShortcutGrid(
                            id = "g1",
                            name = "tmux",
                            rows = 5,
                            cols = 6,
                            cells = mapOf(
                                0 to GridCell(PhysicalKeyMap.key(android.view.KeyEvent.KEYCODE_C)),
                                1 to GridCell(PhysicalKeyMap.key(android.view.KeyEvent.KEYCODE_N)),
                                7 to GridCell(PhysicalKeyMap.key(android.view.KeyEvent.KEYCODE_P, ctrl = true)),
                            ),
                        ),
                    ),
                    onGridsChange = {},
                    referencedGridIds = emptySet(),
                    initialEditingGridId = "g1",
                )
            }
        },
        Route("shortcut-key-picker") {
            Box(Modifier.fillMaxSize()) {
                PhysicalKeyPicker(visible = true, onSelect = {}, onDismiss = {})
            }
        },
        // Notificação Ateliê (T24): RemoteViews inflada via AndroidView.
        notificationRoute("notification-recording", ButtonState.RECORDING),
        notificationRoute("notification-paused", ButtonState.PAUSED),
        // Navegação lateral aberta (ícones + nomes) — a posição normal é a recolhida, das demais rotas.
        Route("config-menu-aberto") { ConfigMenuRoute(railExpanded = true) },
        Route("config-voz-creditos") { ConfigVozRoute(TranscriptionSource.CREDITS) },
        Route("config-voz-openai") { ConfigVozRoute(TranscriptionSource.OPENAI) },
        Route("config-voz-custom") { ConfigVozRoute(TranscriptionSource.CUSTOM) },
        // Gerenciador de modelos locais (MGR-07): nada baixado / baixando (progresso congelado) /
        // um selecionado e os outros disponíveis.
        Route("config-voz-local") { ConfigVozRoute(TranscriptionSource.LOCAL, localModels = LocalModelsUi.EMPTY) },
        Route("config-voz-local-baixando") {
            ConfigVozRoute(
                TranscriptionSource.LOCAL,
                localModels = galleryLocalModels(
                    installed = emptySet(),
                    active = DownloadProgress(
                        ModelCatalog.WHISPER_SMALL.id,
                        DownloadState.RUNNING,
                        bytesDone = ModelCatalog.WHISPER_SMALL.totalBytes * 42 / 100,
                        totalBytes = ModelCatalog.WHISPER_SMALL.totalBytes,
                    ),
                ),
            )
        },
        Route("config-voz-local-pronto") {
            ConfigVozRoute(
                TranscriptionSource.LOCAL,
                localModelId = ModelCatalog.WHISPER_BASE.id,
                localModels = galleryLocalModels(installed = setOf(ModelCatalog.WHISPER_BASE.id), selected = ModelCatalog.WHISPER_BASE.id),
            )
        },
        Route("config-teclado") { ConfigTabRoute(ConfigTab.TECLADO, keyboardEnabled = true) },
        Route("config-overlay") { ConfigTabRoute(ConfigTab.OVERLAY, overlayEnabled = true) },
        // Interface desligada: o cartão do modo no topo segue tocável; o resto da aba, esmaecido.
        Route("config-teclado-travado") { ConfigTabRoute(ConfigTab.TECLADO) },
        Route("config-overlay-travado") { ConfigTabRoute(ConfigTab.OVERLAY) },
        Route("config-docs") { ConfigTabRoute(ConfigTab.DOCS) },
        Route("config-unlock") { ConfigTabRoute(ConfigTab.TECLADO, keyboardEnabled = true, unlockOpen = true) },
        // "?" do cabeçalho aberto: a seção da Docs da aba, com o atalho para a Docs.
        Route("config-ajuda-teclado") { ConfigTabRoute(ConfigTab.TECLADO, keyboardEnabled = true, tabHelpOpen = true) },
        Route("onboarding") { OnboardingRoute() },
        // PERM-04: o porquê de uma permissão aberto sob a linha (a do Teclado, que é a que FALTA no mock).
        Route("onboarding-why") { OnboardingRoute(explained = OnboardingPermission.KEYBOARD) },
    )

    private val byKey: Map<String, Route> = routes.associateBy { it.key }

    fun route(key: String): Route? = byKey[key]

    val keys: List<String> get() = routes.map { it.key }

    /** Scaffold de configuração na aba padrão (Transcrição), com modos ligados — base do menu aberto. */
    @Composable
    private fun ConfigMenuRoute(railExpanded: Boolean = false) {
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
            onRequestNotificationPermission = {},
            onSwitchKeyboard = {},
            onSettingsChange = {},
            initialRailExpanded = railExpanded,
        )
    }

    /** Estado fixo do painel Local: RAM de sobra (sem aviso de peso) para os goldens não dependerem do aparelho. */
    private fun galleryLocalModels(installed: Set<String>, active: DownloadProgress? = null, selected: String? = null) =
        LocalModelsUi.compose(installed = installed, active = active, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_192)

    /** Aba Voz por origem (Créditos/OpenAI/Custom/Local) — espelha os mocks 02-v2/03-v2. */
    @Composable
    private fun ConfigVozRoute(
        source: TranscriptionSource,
        localModelId: String? = null,
        localModels: LocalModelsUi = LocalModelsUi.EMPTY,
    ) {
        ConfigScreen(
            settings = FakeSettings.settings.copy(source = source, localModelId = localModelId),
            localModels = localModels,
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
            onRequestNotificationPermission = {},
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
        tabHelpOpen: Boolean = false,
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
                // Sem isto a aba Teclado saía travada (somente-leitura) desde que o gating passou a exigir
                // o KeyFlow como teclado ativo — o golden mostrava a aba esmaecida, não a tela de verdade.
                keyboardActive = keyboardEnabled,
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
            onRequestNotificationPermission = {},
            onSwitchKeyboard = {},
            onSettingsChange = {},
            initialTab = tab,
            initialUnlockOpen = unlockOpen,
            initialTabHelpOpen = tabHelpOpen,
        )
    }

    /** Hospeda o [KeyFlowKeyboardView] (View) numa rota Compose, ancorado ao rodapé (KBD screenshots). */
    private fun keyboardRoute(
        key: String,
        expanded: Boolean,
        recording: Boolean,
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
                        frozenSeconds = 12,
                    ).apply {
                        setExpanded(expanded)
                        if (recording) updateMicState(ButtonState.RECORDING)
                    }
                },
            )
        }
    }

    private fun barsEditorGridFormRoute(key: String, mode: GridTriggerMode): Route = Route(key) {
        val tmux = ShortcutGrid(id = "g1", name = "tmux", rows = 2, cols = 3, cells = emptyMap())
        Box(Modifier.fillMaxSize()) {
            BarsEditor(
                barsConfig = BarsConfig(mirrored = true, expanded = emptyList(), collapsed = listOf(BarDef(listOf(BarButton.Mic)))),
                onBarsConfigChange = {},
                grids = listOf(tmux),
                initialGridForm = BarButton.GridTrigger(
                    gridId = tmux.id,
                    prefix = if (mode == GridTriggerMode.OPEN_ONLY) null else PhysicalKeyMap.key(android.view.KeyEvent.KEYCODE_B, ctrl = true),
                    mode = mode,
                ),
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
                        frozenSeconds = 12,
                    ).apply {
                        setExpanded(true)
                        updateCandidates(listOf("olá", "olar", "olé"))
                    }
                },
            )
        }
    }

    /** Barra Canvas (SB-T5..T10) via `useBars=true`: paridade com a barra de fábrica (ROL-03). */
    private fun barRoute(key: String, expanded: Boolean): Route = Route(key) {
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
                        frozenSeconds = 12,
                    ).apply { setExpanded(expanded) }
                },
            )
        }
    }

    /** Barra custom com mais teclas avulsas do que cabem na largura — golden do overflow com rolagem (BAR-06). */
    private fun barCustomOverflowRoute(key: String): Route = Route(key) {
        val dark = LocalKeyFlowPalette.current.isDark
        val config = BarsConfig(
            mirrored = true,
            collapsed = listOf(
                BarDef(
                    listOf(BarButton.Mic) +
                        (0..9).map { BarButton.SingleKey(PhysicalKeyMap.key(KeyEvent.KEYCODE_A + it)) } +
                        listOf(BarButton.ToggleExpand),
                )
            ),
            expanded = emptyList(),
        )
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
                        barsConfig = config,
                        frozenSeconds = 12,
                    ).apply { setExpanded(false) }
                },
            )
        }
    }

    /**
     * Grade efêmera revelada (modo aprendizado, GRD-06): sem dedo de verdade nem UiAutomator (a galeria
     * roda no processo do app, não num teste instrumentado), simula DOWN+MOVE direto na `BarView` via
     * [dispatchSynthetic] e dá tempo real (`minDelayMs`) pro tick de reveal da própria view rodar —
     * mesma máquina de estados usada no toque real (SB-T6/GridSession), só o gatilho do gesto é sintético.
     */
    private fun barGridRevealedRoute(key: String): Route = Route(key, minDelayMs = 700L) {
        val dark = LocalKeyFlowPalette.current.isDark
        val grid = ShortcutGrid(
            id = "gallery",
            name = "galeria",
            rows = 1,
            cols = 3,
            cells = mapOf(
                0 to GridCell(PhysicalKeyMap.key(KeyEvent.KEYCODE_C), label = "copiar"),
                1 to GridCell(PhysicalKeyMap.key(KeyEvent.KEYCODE_V), label = "colar"),
                2 to GridCell(PhysicalKeyMap.key(KeyEvent.KEYCODE_X), label = "recortar"),
            ),
        )
        val config = BarsConfig(
            mirrored = true,
            collapsed = listOf(
                BarDef(
                    listOf(
                        BarButton.Mic,
                        BarButton.GridTrigger(gridId = "gallery", mode = GridTriggerMode.OPEN_ONLY),
                        BarButton.ToggleExpand,
                    )
                )
            ),
            expanded = emptyList(),
        )
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
                        barsConfig = config,
                        shortcutGrids = listOf(grid),
                        frozenSeconds = 12,
                    ).apply {
                        setExpanded(false)
                        // `setExpanded(false)` reconstrói a pilha de barras (a barra expandida do
                        // `init` fica órfã, sem jamais anexar à janela) — a barra retraída de verdade é
                        // sempre a ÚLTIMA da lista após essa troca.
                        val bar = barViewsForTest.lastOrNull()
                        bar?.post { revealGridTrigger(bar) }
                    }
                },
            )
        }
    }

    /** DOWN no gatilho + MOVE pra baixo (âncora, GRD-01) e SEGURA — o parado é o que dispara o reveal. */
    private fun revealGridTrigger(bar: com.keyflow.ime.bars.BarView) {
        val placed = bar.layoutForTest.buttons.firstOrNull { it.button is BarButton.GridTrigger } ?: return
        val rect = bar.buttonScreenRectForTest(placed.index)
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val time = android.os.SystemClock.uptimeMillis()
        dispatchSynthetic(bar, android.view.MotionEvent.ACTION_DOWN, cx, cy, time)
        dispatchSynthetic(bar, android.view.MotionEvent.ACTION_MOVE, cx, cy + 220f, time)
    }

    /** Alavanca puxada (JOY-01): a bolinha desloca de forma síncrona ao MOVE, sem depender de tempo. */
    private fun barJoystickPulledRoute(key: String): Route = Route(key) {
        val dark = LocalKeyFlowPalette.current.isDark
        val config = BarsConfig(
            mirrored = true,
            collapsed = listOf(BarDef(listOf(BarButton.Mic, BarButton.Joystick, BarButton.ToggleExpand))),
            expanded = emptyList(),
        )
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
                        barsConfig = config,
                        frozenSeconds = 12,
                    ).apply {
                        setExpanded(false)
                        val bar = barViewsForTest.lastOrNull()
                        bar?.post { pullJoystick(bar) }
                    }
                },
            )
        }
    }

    private fun pullJoystick(bar: com.keyflow.ime.bars.BarView) {
        val placed = bar.layoutForTest.buttons.firstOrNull { it.button == BarButton.Joystick } ?: return
        val cx = (placed.left + placed.right) / 2f
        val cy = bar.height / 2f
        val time = android.os.SystemClock.uptimeMillis()
        dispatchSynthetic(bar, android.view.MotionEvent.ACTION_DOWN, cx, cy, time)
        dispatchSynthetic(bar, android.view.MotionEvent.ACTION_MOVE, cx + 80f, cy, time)
    }

    /**
     * Despacha direto na `BarView` (sem passar pela hierarquia do pai): `rawX/rawY` só mudam via
     * `View.offsetLocation`, nunca por um `dispatchTouchEvent` direto — então saem iguais a x/y aqui, o
     * que é exatamente o que a `GridSession` precisa (a barra ocupa a tela inteira a partir de x=0, então
     * coordenada local == coordenada de tela).
     */
    private fun dispatchSynthetic(view: android.view.View, action: Int, x: Float, y: Float, downTime: Long) {
        val event = android.view.MotionEvent.obtain(downTime, downTime, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
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

    /**
     * Sanidade de render do [FingerPuppet] (GT-T2): frame congelado num instante fixo de um roteiro
     * real do catálogo, sobre um fundo neutro. Não é o tour — a cena real entra na GT-T4/T5.
     */
    private fun fingerPuppetRoute(key: String, scriptId: String, atMs: Int): Route = Route(key) {
        val settings = Settings(shortcut3Tap = com.keyflow.core.OrbShortcutAction.SWITCH_IME)
        val script = buildOrbScripts(settings).first { it.id == scriptId }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
        ) {
            FingerPuppet(frame = tourFrameAt(script, atMs), modifier = Modifier.fillMaxSize())
        }
    }

    /** Golden do glifo-alvo do modo-arrasto travado (segurar a orbe até a zona [kind] — proximidade 1). */
    private fun orbDragRoute(key: String, state: OrbState, kind: OrbDragKind, seconds: Int): Route = Route(key) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.Center,
        ) {
            VoiceOrb(
                state = state,
                modifier = Modifier.size(160.dp),
                dragHint = OrbDragHint(centerKind = kind, centerAlpha = 1f, seconds = seconds),
                timeProvider = { GALLERY_ORB_TIME_MS },
            )
        }
    }

    @Composable
    private fun OnboardingRoute(explained: OnboardingPermission? = null) {
        OnboardingScreen(
            permissions = PermissionStatus(
                microphoneGranted = true,
                overlayGranted = true,
                keyboardEnabled = false, // espelha o mock (Teclado FALTA)
                notificationsGranted = true,
            ),
            onGrant = {},
            onSkip = {},
            onRequestMic = {},
            onRequestOverlay = {},
            onRequestKeyboard = {},
            onRequestNotifications = {},
            initiallyExplained = explained,
        )
    }

    /**
     * Tour real fora da aba Modo (GT-T8): toca [scriptIds] — nesta ordem, um subconjunto do catálogo das
     * Settings de fábrica — com o relógio do tour parado em [atMs] do primeiro roteiro. O título e a
     * descrição vêm do estado exposto pelo player, lidos pelo hospedeiro (TOUR-01).
     */
    private fun tourRoute(key: String, scene: TourScene, scriptIds: List<String>, atMs: Int): Route = Route(key) {
        val settings = Settings()
        val catalog = if (scene == TourScene.ORB) buildOrbScripts(settings) else buildKeyboardScripts(settings)
        val scripts = scriptIds.map { id -> catalog.first { it.id == id } }
        val state = com.keyflow.ui.tour.rememberGestureTourState(scripts)
        val palette = LocalKeyFlowPalette.current
        androidx.compose.runtime.CompositionLocalProvider(LocalTourClock provides { atMs.toFloat() }) {
            androidx.compose.foundation.layout.Column(
                Modifier
                    .fillMaxSize()
                    .background(palette.bg)
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                GestureTourPlayer(
                    scripts = scripts,
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.line2, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) { frame ->
                    if (scene == TourScene.ORB) {
                        OrbScene(frame, Modifier.fillMaxWidth())
                    } else {
                        KeyboardScene(frame, Modifier.fillMaxWidth())
                    }
                }
                androidx.compose.material3.Text(
                    state.title,
                    color = palette.fg,
                    fontFamily = com.keyflow.ui.theme.SpaceGrotesk,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
                androidx.compose.material3.Text(
                    state.description,
                    color = palette.muted,
                    fontFamily = com.keyflow.ui.theme.SpaceGrotesk,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
