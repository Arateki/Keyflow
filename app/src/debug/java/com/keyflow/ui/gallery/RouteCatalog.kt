package com.keyflow.ui.gallery

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
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
import com.keyflow.core.VisionSource
import com.keyflow.core.FalaSource
import android.view.KeyEvent
import android.widget.FrameLayout
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.GridCell
import com.keyflow.core.shortcuts.GridTriggerMode
import com.keyflow.core.shortcuts.PhysicalKeyMap
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.ime.GlitchTimeline
import com.keyflow.ime.ShimmerTimeline
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.ime.KeyboardSkin
import com.keyflow.overlay.RecordingNotification
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.config.BarsEditor
import com.keyflow.ui.config.ConfigScreen
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.config.GridsEditor
import com.keyflow.ui.config.PhysicalKeyPicker
import com.keyflow.ui.config.SettingsGroup
import com.keyflow.ui.config.SkinImportReportDialog
import com.keyflow.ui.config.SkinPackSurface
import com.keyflow.ui.config.SkinPacksContent
import com.keyflow.ui.config.SkinsSection
import com.keyflow.ui.config.loadSkinPackCards
import com.keyflow.skins.SkinIds
import com.keyflow.ui.onboarding.OnboardingPermission
import com.keyflow.ui.onboarding.OnboardingScreen
import com.keyflow.ui.onboarding.ProminentDisclosureScreen
import com.keyflow.ui.orb.OrbCenter
import com.keyflow.ui.orb.OrbDragHint
import com.keyflow.ui.orb.OrbDragKind
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.OrbStyle
import com.keyflow.ui.orb.glitchBurstMidMs
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.config.LocalModelsUi
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.DownloadProgress
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.ConnectionState
import com.keyflow.core.ReasoningSource
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.ui.config.AccountModel
import com.keyflow.ui.config.AuthActions
import com.keyflow.ui.config.TestState
import com.keyflow.ads.AdsController
import com.keyflow.ads.AdsRuntime
import com.keyflow.ads.ConsentRequester
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

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
        // Protótipo Aero: a orbe de vidro, parada e gravando.
        orbRoute("overlay-aero-idle", OrbState.Idle, OrbCenter.None, OrbStyle.AERO),
        orbRoute("overlay-aero-recording", OrbState.Listening, OrbCenter.Time(12), OrbStyle.AERO),
        // Glitch (2026-09-29): a orbe preta com franjas ciano e vermelha, congelada no pico de uma rajada de falha.
        orbRoute("overlay-glitch-idle", OrbState.Idle, OrbCenter.None, OrbStyle.GLITCH),
        // O mesmo repouso entre rajadas (t = 200 ms): só as franjas de sempre, sem faixas deslocadas.
        orbRoute("overlay-glitch-idle-calm", OrbState.Idle, OrbCenter.None, OrbStyle.GLITCH, timeMs = 200f),
        orbRoute("overlay-glitch-recording", OrbState.Listening, OrbCenter.Time(12), OrbStyle.GLITCH),
        orbRoute("overlay-glitch-transcribing", OrbState.Transcribing, OrbCenter.None, OrbStyle.GLITCH),
        // Rose Gold (2026-09-29): a esfera de metal rosé, parada e gravando.
        orbRoute("overlay-rosegold-idle", OrbState.Idle, OrbCenter.None, OrbStyle.ROSE_GOLD),
        orbRoute("overlay-rosegold-recording", OrbState.Listening, OrbCenter.Time(12), OrbStyle.ROSE_GOLD),
        // O Ouvi (2026-10-02): o mascote da orbe, um por estado; a placa e a "folha" põem tudo o que o app escreve no centro
        // sobre a barriga, em fundo branco e preto lado a lado, e os "tamanhos" mostram o que o slider da aba Sobrepor dá.
        orbRoute("overlay-mascot-idle", OrbState.Idle, OrbCenter.None, OrbStyle.MASCOT, timeMs = mascotStillMs(OrbState.Idle)),
        orbRoute("overlay-mascot-recording", OrbState.Listening, OrbCenter.Time(12), OrbStyle.MASCOT, timeMs = mascotStillMs(OrbState.Listening)),
        orbRoute("overlay-mascot-paused", OrbState.Paused, OrbCenter.PauseGlyph(12), OrbStyle.MASCOT, timeMs = mascotStillMs(OrbState.Paused)),
        orbRoute("overlay-mascot-transcribing", OrbState.Transcribing, OrbCenter.None, OrbStyle.MASCOT, timeMs = mascotStillMs(OrbState.Transcribing)),
        mascotBoardRoute("overlay-mascot-claro-escuro"),
        mascotSizesRoute("overlay-mascot-tamanhos"),
        // Teclado reskinado (T23), grade Canvas (KC-T10, padrão único desde a promoção): View hospedado
        // via AndroidView, ancorado ao rodapé.
        keyboardRoute("keyboard-canvas-expanded", expanded = true, recording = false),
        keyboardRoute("keyboard-canvas-collapsed", expanded = false, recording = false),
        keyboardCanvasSymbolsRoute("keyboard-canvas-symbols"),
        keyboardCanvasCandidatesRoute("keyboard-canvas-candidates"),
        // Protótipo Aero (2026-09-28): paisagem própria + teclas de vidro, com sugestões na faixa.
        keyboardCanvasCandidatesRoute("keyboard-aero-expanded", skin = KeyboardSkin.AERO),
        keyboardRoute("keyboard-aero-collapsed", expanded = false, recording = false, skin = KeyboardSkin.AERO),
        // Glitch (2026-09-29): faces pretas, borda em três canais, rótulos em camadas, fundo com rasgos.
        keyboardCanvasCandidatesRoute("keyboard-glitch-expanded", skin = KeyboardSkin.GLITCH),
        keyboardRoute("keyboard-glitch-collapsed", expanded = false, recording = false, skin = KeyboardSkin.GLITCH),
        // O teclado Glitch congelado no pico de uma rajada de falha: faixas deslocadas, franjas e rótulos abertos.
        keyboardCanvasCandidatesRoute("keyboard-glitch-burst", skin = KeyboardSkin.GLITCH, motionTimeMs = GlitchTimeline.peakOf(1)),
        // Rose Gold (2026-09-29): cetim rosé com bisel metálico; e o mesmo teclado no meio do brilho que o atravessa.
        keyboardCanvasCandidatesRoute("keyboard-rosegold-expanded", skin = KeyboardSkin.ROSE_GOLD),
        keyboardRoute("keyboard-rosegold-collapsed", expanded = false, recording = false, skin = KeyboardSkin.ROSE_GOLD),
        keyboardCanvasCandidatesRoute("keyboard-rosegold-shimmer", skin = KeyboardSkin.ROSE_GOLD, motionTimeMs = ShimmerTimeline.peakOf(1)),
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
        // A seção Skins das duas abas (fica abaixo da dobra nas telas acima): a da orbe, na Sobrepor, e a do
        // teclado, na Teclado — escolhas separadas (Yan, 2026-09-29), aqui com o Aero marcado nas duas.
        Route("config-overlay-skins") { SkinsRoute(orb = true) },
        // A mesma seção com o Mascote escolhido: o anel no cartão novo.
        Route("config-overlay-skins-mascote") { SkinsRoute(orb = true, selectedOrb = "mascot") },
        Route("config-teclado-skins") { SkinsRoute(orb = false) },
        // Com pacotes importados (SKP-T6): a seção "Suas skins" com os cartões — um selecionado —, a pergunta de remoção aberta e os dois
        // relatórios do import (instalado com avisos; recusado com erros). Os pacotes são instalados de verdade, por `SkinPackStore.import`.
        Route("config-overlay-skins-importadas") { SkinsRoute(orb = true, imported = true) },
        Route("config-teclado-skins-importadas") { SkinsRoute(orb = false, imported = true) },
        Route("config-teclado-skins-remover") { SkinsRoute(orb = false, imported = true, confirming = true) },
        Route("skin-pack-relatorio") { SkinReportRoute(errors = false) },
        Route("skin-pack-relatorio-erro") { SkinReportRoute(errors = true) },
        // Visão: `config-visao` é a origem Local sem nenhum modelo baixado; as outras cobrem baixando e pronto.
        Route("config-visao") { ConfigVisaoRoute() },
        Route("config-visao-local-baixando") {
            ConfigVisaoRoute(
                localModels = galleryVisionModels(
                    active = DownloadProgress(
                        ModelCatalog.OCR_FAST.id,
                        DownloadState.RUNNING,
                        bytesDone = ModelCatalog.OCR_FAST.totalBytes * 42 / 100,
                        totalBytes = ModelCatalog.OCR_FAST.totalBytes,
                    ),
                ),
            )
        },
        Route("config-visao-local-pronto") {
            ConfigVisaoRoute(
                localModelId = ModelCatalog.OCR_FAST.id,
                localModels = galleryVisionModels(installed = setOf(ModelCatalog.OCR_FAST.id), selected = ModelCatalog.OCR_FAST.id),
            )
        },
        Route("config-visao-google") { ConfigVisaoRoute(source = VisionSource.GOOGLE, googleKey = "AIzaSyMockKeyForScreenshots") },
        Route("config-visao-anthropic") { ConfigVisaoRoute(source = VisionSource.ANTHROPIC, anthropicKey = "sk-ant-api-mock-key") },
        // Voz (Fala): `config-fala` é a origem Local sem nenhum modelo baixado; as outras cobrem baixando e pronto.
        Route("config-fala") { ConfigFalaRoute() },
        Route("config-fala-local-baixando") {
            ConfigFalaRoute(
                localModels = galleryVoiceModels(
                    active = DownloadProgress(
                        ModelCatalog.VOICE_FAST.id,
                        DownloadState.RUNNING,
                        bytesDone = ModelCatalog.VOICE_FAST.totalBytes * 42 / 100,
                        totalBytes = ModelCatalog.VOICE_FAST.totalBytes,
                    ),
                ),
            )
        },
        Route("config-fala-local-pronto") {
            ConfigFalaRoute(
                localModelId = ModelCatalog.VOICE_FAST.id,
                localModels = galleryVoiceModels(installed = setOf(ModelCatalog.VOICE_FAST.id), selected = ModelCatalog.VOICE_FAST.id),
            )
        },
        Route("config-fala-elevenlabs") { ConfigFalaRoute(source = FalaSource.ELEVENLABS, elevenLabsKey = "eleven_mock_key") },
        Route("config-fala-google") { ConfigFalaRoute(source = FalaSource.GOOGLE, googleKey = "AIzaSyMockGoogleVoiceKey") },
        // Raciocínio: `config-raciocinio` é a origem Local sem nenhum modelo baixado; as outras cobrem baixando, pronto, a nuvem
        // (desconectada, conectada com o plano) e a área do Testar em andamento e no limite de uso.
        Route("config-raciocinio") { ConfigTabRoute(ConfigTab.RACIOCINIO) },
        Route("config-raciocinio-local-baixando") {
            ConfigReasoningRoute(
                localModels = galleryTextModels(
                    active = DownloadProgress(
                        ModelCatalog.GEMMA_4_E2B.id,
                        DownloadState.RUNNING,
                        bytesDone = ModelCatalog.GEMMA_4_E2B.totalBytes * 42 / 100,
                        totalBytes = ModelCatalog.GEMMA_4_E2B.totalBytes,
                    ),
                ),
            )
        },
        Route("config-raciocinio-local-pronto") {
            ConfigReasoningRoute(
                localModelId = ModelCatalog.QWEN3_0_6B.id,
                localModels = galleryTextModels(installed = setOf(ModelCatalog.QWEN3_0_6B.id), selected = ModelCatalog.QWEN3_0_6B.id),
                ready = true,
            )
        },
        Route("config-raciocinio-openai-desconectado") {
            ConfigReasoningRoute(source = ReasoningSource.OPENAI, auth = GalleryAuth(ConnectionState.Disconnected, storedKey = FakeSettings.OPENAI_API_KEY))
        },
        Route("config-raciocinio-openai-conectado") {
            ConfigReasoningRoute(
                source = ReasoningSource.OPENAI,
                openAiModel = "modelo-a",
                auth = GalleryAuth(
                    ConnectionState.Connected("voce@exemplo.com", AuthMethod.OAUTH, planUsage = true),
                    models = listOf(AccountModel("modelo-a", "Modelo A"), AccountModel("modelo-b", "Modelo B")),
                    storedKey = FakeSettings.OPENAI_API_KEY,
                ),
                ready = true,
            )
        },
        Route("config-raciocinio-anthropic-desconectado") {
            ConfigReasoningRoute(source = ReasoningSource.ANTHROPIC, anthropicAuth = GalleryAuth(ConnectionState.Disconnected))
        },
        Route("config-raciocinio-anthropic-conectado") {
            ConfigReasoningRoute(
                source = ReasoningSource.ANTHROPIC,
                anthropicModel = "claude-3-5-sonnet-latest",
                anthropicAuth = GalleryAuth(
                    ConnectionState.Connected("claude@exemplo.com", AuthMethod.OAUTH),
                    models = com.keyflow.ui.config.ANTHROPIC_MODELS_DEFAULT,
                ),
                ready = true,
            )
        },
        Route("config-raciocinio-google-desconectado") {
            ConfigReasoningRoute(source = ReasoningSource.GOOGLE, googleAuth = GalleryAuth(ConnectionState.Disconnected))
        },
        Route("config-raciocinio-google-conectado") {
            ConfigReasoningRoute(
                source = ReasoningSource.GOOGLE,
                googleModel = "gemini-2.0-flash",
                googleAuth = GalleryAuth(
                    ConnectionState.Connected("gemini@exemplo.com", AuthMethod.OAUTH),
                    models = com.keyflow.ui.config.GOOGLE_MODELS_DEFAULT,
                ),
                ready = true,
            )
        },
        // Testar em andamento: um trecho já chegou (tempo até o 1º) e o botão virou "Parar".
        Route("config-raciocinio-testando") {
            ConfigReasoningRoute(
                localModelId = ModelCatalog.QWEN3_0_6B.id,
                localModels = galleryTextModels(installed = setOf(ModelCatalog.QWEN3_0_6B.id), selected = ModelCatalog.QWEN3_0_6B.id),
                ready = true,
                test = TestState(text = "Olá! Tudo bem por aí? Espero que o seu dia", running = true, firstDeltaMs = 820),
            )
        },
        // Limite do plano atingido: "Manage usage" vira a ação principal (sem o texto parcial, para o botão caber na tela; o parcial
        // que permanece tem teste próprio).
        Route("config-raciocinio-openai-limite") {
            ConfigReasoningRoute(
                source = ReasoningSource.OPENAI,
                openAiModel = "modelo-a",
                auth = GalleryAuth(
                    ConnectionState.Connected("voce@exemplo.com", AuthMethod.OAUTH, planUsage = true),
                    models = listOf(AccountModel("modelo-a", "Modelo A")),
                    storedKey = FakeSettings.OPENAI_API_KEY,
                ),
                ready = true,
                test = TestState(totalMs = 2_100, error = ReasoningError.RATE_LIMIT),
            )
        },
        Route("config-notificacao") { ConfigTabRoute(ConfigTab.NOTIFICACAO) },
        Route("config-unlock") { ConfigTabRoute(ConfigTab.TECLADO, keyboardEnabled = true, unlockOpen = true) },
        // "?" do cabeçalho aberto: a seção da Docs da aba, com o atalho para a Docs.
        Route("config-ajuda-teclado") { ConfigTabRoute(ConfigTab.TECLADO, keyboardEnabled = true, tabHelpOpen = true) },
        Route("disclosure") { ProminentDisclosureScreen(declined = false, onAccept = {}, onDecline = {}) },
        Route("onboarding") { OnboardingRoute() },
        // PERM-04: o porquê de uma permissão aberto sob a linha (a do Teclado, que é a que FALTA no mock).
        Route("onboarding-why") { OnboardingRoute(explained = OnboardingPermission.KEYBOARD) },
    ) + AgentGalleryRoutes.routes // agent-harness (AGT-T3..T5): o chat do agente, o painel de confirmação, os Ajustes e o registro

    private val byKey: Map<String, Route> = routes.associateBy { it.key }

    fun route(key: String): Route? = byKey[key]

    val keys: List<String> get() = routes.map { it.key }

    /** Scaffold de configuração na aba padrão (Transcrição), com modos ligados — base do menu aberto. */
    @Composable
    private fun ConfigMenuRoute(railExpanded: Boolean = false) {
        ConfigScreen(
            settings = FakeSettings.settings.copy(overlayModeEnabled = true, keyboardModeEnabled = true),
            transcriptionOpenAiAuth = GalleryAuth(ConnectionState.Disconnected, storedKey = FakeSettings.OPENAI_API_KEY),
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

    /** Estado fixo da lista de modelos de TEXTO do Raciocínio (RAM de sobra, como a de voz). */
    private fun galleryTextModels(installed: Set<String> = emptySet(), active: DownloadProgress? = null, selected: String? = null) =
        LocalModelsUi.compose(
            catalog = ModelCatalog.TEXT, installed = installed, active = active, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_192,
        )

    /** Estado fixo da lista de modelos de VISÃO/OCR (RAM de sobra, como a de voz). */
    private fun galleryVisionModels(installed: Set<String> = emptySet(), active: DownloadProgress? = null, selected: String? = null) =
        LocalModelsUi.compose(
            catalog = ModelCatalog.VISION, installed = installed, active = active, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_192,
        )

    /** Aba Visão: a origem, a lista local e o seletor de tradução num estado fixo. */
    @Composable
    private fun ConfigVisaoRoute(
        source: VisionSource = VisionSource.LOCAL,
        localModelId: String? = null,
        localModels: LocalModelsUi = galleryVisionModels(),
        googleKey: String = "",
        anthropicKey: String = "",
    ) {
        ConfigScreen(
            settings = FakeSettings.settings.copy(
                visionSource = source,
                visionLocalModelId = localModelId,
            ),
            visionModels = localModels,
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
            onRequestOverlayPermission = {},
            onRequestKeyboardPermission = {},
            onRequestMicPermission = {},
            onRequestNotificationPermission = {},
            onSwitchKeyboard = {},
            onSettingsChange = {},
            initialTab = ConfigTab.VISAO,
        )
    }

    private fun galleryVoiceModels(
        installed: Set<String> = emptySet(),
        active: DownloadProgress? = null,
        selected: String? = null,
    ): LocalModelsUi =
        LocalModelsUi.compose(
            catalog = ModelCatalog.VOICE, installed = installed, active = active, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_192,
        )

    /** Aba Voz (Fala): a origem, a lista local e os controles de voz num estado fixo. */
    @Composable
    private fun ConfigFalaRoute(
        source: FalaSource = FalaSource.LOCAL,
        localModelId: String? = null,
        localModels: LocalModelsUi = galleryVoiceModels(),
        elevenLabsKey: String = "",
        googleKey: String = "",
    ) {
        ConfigScreen(
            settings = FakeSettings.settings.copy(
                falaSource = source,
                falaLocalModelId = localModelId,
            ),
            falaModels = localModels,
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
            onRequestOverlayPermission = {},
            onRequestKeyboardPermission = {},
            onRequestMicPermission = {},
            onRequestNotificationPermission = {},
            onSwitchKeyboard = {},
            onSettingsChange = {},
            initialTab = ConfigTab.FALA,
        )
    }

    /** Aba Raciocínio: a origem, a lista local, o provedor de nuvem e a área do Testar num estado fixo. [ready] liga o botão Testar. */
    @Composable
    private fun ConfigReasoningRoute(
        source: ReasoningSource = ReasoningSource.LOCAL,
        localModelId: String? = null,
        openAiModel: String = "",
        anthropicModel: String = "claude-3-5-sonnet-latest",
        googleModel: String = "gemini-2.0-flash",
        localModels: LocalModelsUi = galleryTextModels(),
        auth: AuthActions = if (source == ReasoningSource.OPENAI) {
            GalleryAuth(ConnectionState.Disconnected, storedKey = FakeSettings.OPENAI_API_KEY)
        } else AuthActions.None,
        anthropicAuth: AuthActions = AuthActions.None,
        googleAuth: AuthActions = AuthActions.None,
        ready: Boolean = false,
        test: TestState = TestState(),
    ) {
        ConfigScreen(
            settings = FakeSettings.settings.copy(
                reasoningSource = source,
                reasoningLocalModelId = localModelId,
                reasoningOpenAiModel = openAiModel,
                reasoningAnthropicModel = anthropicModel,
                reasoningGoogleModel = googleModel,
            ),
            reasoningModels = localModels,
            reasoningAuth = auth,
            reasoningAnthropicAuth = anthropicAuth,
            reasoningGoogleAuth = googleAuth,
            initialReasoningTest = test,
            resolveReasoning = {
                if (ready) ReasoningResolution.Ready(GalleryReasoningEngine)
                else ReasoningResolution.Unavailable(if (source != ReasoningSource.LOCAL) UnavailableReason.NOT_CONNECTED else UnavailableReason.NO_LOCAL_MODEL)
            },
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
            onRequestOverlayPermission = {},
            onRequestKeyboardPermission = {},
            onRequestMicPermission = {},
            onRequestNotificationPermission = {},
            onSwitchKeyboard = {},
            onSettingsChange = {},
            initialTab = ConfigTab.RACIOCINIO,
        )
    }

    /** O motor da galeria: nunca é chamado (ninguém toca no Testar) — só faz o botão aparecer ligado. */
    private object GalleryReasoningEngine : ReasoningEngine {
        override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = emptyFlow()
    }

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
            transcriptionOpenAiAuth = GalleryAuth(ConnectionState.Disconnected, storedKey = FakeSettings.OPENAI_API_KEY),
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
        val bannerTab = tab == ConfigTab.DOCS || tab == ConfigTab.OVERLAY || tab == ConfigTab.TECLADO
        val context = LocalContext.current
        val dark = LocalKeyFlowPalette.current.isDark
        val galleryAdsRuntime = remember(dark) { GalleryAdsRuntime(dark) }
        val adsController = remember(tab, dark) {
            if (bannerTab) AdsController(context.applicationContext, galleryAdsRuntime, GalleryConsentRequester) else null
        }
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
            onRequestOverlayPermission = {},
            onRequestKeyboardPermission = {},
            onRequestMicPermission = {},
            onRequestNotificationPermission = {},
            onSwitchKeyboard = {},
            onSettingsChange = {},
            initialTab = tab,
            initialUnlockOpen = unlockOpen,
            initialTabHelpOpen = tabHelpOpen,
            adsController = adsController,
        )
    }

    /** Banner determinístico, sem rede/SDK real, apenas nas capturas de configuração da galeria. */
    private class GalleryAdsRuntime(private val dark: Boolean) : AdsRuntime {
        override fun initialize(context: Context, onComplete: () -> Unit) = onComplete()
        override fun createBanner(context: Context, adUnitId: String): View = TextView(context).apply {
            val gray = if (dark) 58 else 238
            setBackgroundColor(android.graphics.Color.rgb(gray, gray, gray))
            text = ""
        }
        override fun loadInterstitial(context: Context, adUnitId: String, onReady: () -> Unit, onFailed: () -> Unit) = onFailed()
        override fun showInterstitial(activity: Activity): Boolean = false
        override fun destroy(view: View) = Unit
    }

    private object GalleryConsentRequester : ConsentRequester {
        override fun request(activity: Activity, onReady: () -> Unit) = onReady()
    }

    /** Hospeda o [KeyFlowKeyboardView] (View) numa rota Compose, ancorado ao rodapé (KBD screenshots). */
    private fun keyboardRoute(
        key: String,
        expanded: Boolean,
        recording: Boolean,
        skin: KeyboardSkin = KeyboardSkin.DEFAULT,
    ): Route = Route(key) {
        val dark = LocalKeyFlowPalette.current.isDark
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // O vidro do Aero é translúcido de verdade: atrás dele, um "app" de mentira.
            if (skin == KeyboardSkin.AERO) BehindKeyboardScene()
            AndroidView(
                modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom),
                factory = { ctx ->
                    KeyFlowKeyboardView(
                        context = ctx,
                        onKey = {},
                        onMic = {},
                        onHide = {},
                        dark = dark,
                        skin = skin,
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
    private fun keyboardCanvasCandidatesRoute(
        key: String,
        skin: KeyboardSkin = KeyboardSkin.DEFAULT,
        motionTimeMs: Float? = null,
    ): Route = Route(key) {
        val dark = LocalKeyFlowPalette.current.isDark
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (skin == KeyboardSkin.AERO) BehindKeyboardScene()
            AndroidView(
                modifier = Modifier.fillMaxSize().wrapContentHeight(Alignment.Bottom),
                factory = { ctx ->
                    KeyFlowKeyboardView(
                        context = ctx,
                        onKey = {},
                        onMic = {},
                        onHide = {},
                        dark = dark,
                        skin = skin,
                        frozenSeconds = 12,
                        motionTimeMs = motionTimeMs,
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

    private fun orbRoute(
        key: String,
        state: OrbState,
        center: OrbCenter,
        style: OrbStyle = OrbStyle.ATELIE,
        timeMs: Float? = null,
    ): Route = Route(key) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalKeyFlowPalette.current.bg),
            contentAlignment = Alignment.Center,
        ) {
            // O vidro do Aero é transparente: atrás dele, um "app" de mentira (texto e uma faixa colorida)
            // para a captura mostrar o que atravessa a orbe.
            if (style == OrbStyle.AERO) BehindOrbScene()
            VoiceOrb(
                state = state,
                modifier = Modifier.size(160.dp),
                center = center,
                // O Glitch congela no pico de uma rajada (senão 1234 ms cairia fora dela e não haveria falha).
                timeProvider = { timeMs ?: if (style == OrbStyle.GLITCH) glitchBurstMidMs(state) else GALLERY_ORB_TIME_MS },
                style = style,
            )
        }
    }

    /**
     * O instante em que o mascote é congelado nas rotas: o relógio fixo de 1234 ms cairia no meio de uma piscada (e de uma
     * tremida de orelha), e o quadro parado mostraria o olho fechado. Estes instantes têm o olho aberto e as orelhas em
     * repouso (conferidos no [com.keyflow.ui.orb.MascotTimeline]); em repouso e pensando, o olhar já está de lado.
     */
    private fun mascotStillMs(state: OrbState): Float = when (state) {
        OrbState.Idle -> 10_200f
        OrbState.Listening -> 1_850f
        OrbState.Paused -> 1_050f
        OrbState.Transcribing -> 2_225f
    }

    /** Cada estado e cada coisa que o app escreve no centro, no tamanho padrão da orbe, sobre branco (esquerda) e preto (direita). */
    private fun mascotBoardRoute(key: String): Route = Route(key) {
        val side = with(androidx.compose.ui.platform.LocalDensity.current) { 156.toDp() }
        val rows = listOf(
            OrbState.Idle to OrbCenter.None,
            OrbState.Listening to OrbCenter.Time(12),
            OrbState.Paused to OrbCenter.PauseGlyph(12),
            OrbState.Transcribing to OrbCenter.None,
            OrbState.Paused to OrbCenter.CancelGlyph(12),
            OrbState.Idle to OrbCenter.ErrorGlyph,
        )
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            for (bg in listOf(Color.White, Color.Black)) {
                Column(
                    Modifier.weight(1f).fillMaxSize().background(bg),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    for ((state, center) in rows) {
                        VoiceOrb(state, Modifier.requiredSize(side), center = center, timeProvider = { mascotStillMs(state) }, style = OrbStyle.MASCOT)
                    }
                }
            }
        }
    }

    /** O mascote nos tamanhos que o slider da aba Sobrepor dá (110 a 280 px), em repouso e gravando, sobre branco e sobre preto. */
    private fun mascotSizesRoute(key: String): Route = Route(key) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val sizes = listOf(110, 156, 195, 280)
        Column(Modifier.fillMaxSize()) {
            for (bg in listOf(Color.White, Color.Black)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().background(bg),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    for (withTime in listOf(false, true)) {
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            for (px in sizes) {
                                VoiceOrb(
                                    if (withTime) OrbState.Listening else OrbState.Idle,
                                    Modifier.requiredSize(with(density) { px.toDp() }),
                                    center = if (withTime) OrbCenter.Time(12) else OrbCenter.None,
                                    timeProvider = { mascotStillMs(if (withTime) OrbState.Listening else OrbState.Idle) },
                                    style = OrbStyle.MASCOT,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A seção Skins de uma aba, sozinha num cartão: [orb] = a da Sobrepor (orbe), senão a do Teclado. Com [imported], os pacotes de mentira
     * ([GallerySkinPacks]) estão instalados e o primeiro, selecionado; com [confirming], o cartão dele mostra a pergunta de remoção.
     */
    @Composable
    private fun SkinsRoute(orb: Boolean, selectedOrb: String = "aero", imported: Boolean = false, confirming: Boolean = false) {
        val context = LocalContext.current
        val density = LocalDensity.current.density
        val packs = remember(imported) {
            if (imported) GallerySkinPacks.install(context) to loadSkinPackCards(GallerySkinPacks.store(context), density) else null
        }
        val selectedPack = packs?.first
        Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg).padding(16.dp)) {
            SettingsGroup(null) {
                if (orb) {
                    SkinsSection(
                        selectedOrbSkin = selectedPack ?: selectedOrb,
                        onOrbSkinSelected = {},
                        onUnlock = {},
                        unlockTag = "gallery-unlock",
                        skinsUnlocked = selectedOrb == "mascot",
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                } else {
                    SkinsSection(
                        selectedSkin = selectedPack ?: "aero",
                        onSkinSelected = {},
                        onUnlock = {},
                        unlockTag = "gallery-unlock",
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                }
                SkinPacksContent(
                    surface = if (orb) SkinPackSurface.ORB else SkinPackSurface.KEYBOARD,
                    cards = packs?.second.orEmpty(),
                    selectedSkinId = selectedPack.orEmpty(),
                    importing = false,
                    confirmingId = if (confirming) SkinIds.packId(selectedPack) else null,
                    onImport = {},
                    onSelect = {},
                    onRemoveRequest = {},
                    onRemoveCancel = {},
                    onRemoveConfirm = {},
                )
            }
        }
    }

    /** O diálogo do relatório do import (SKP-UI-02): [errors] = o import recusado; senão o instalado com avisos. */
    @Composable
    private fun SkinReportRoute(errors: Boolean) {
        val context = LocalContext.current
        val outcome = remember(errors) { if (errors) GallerySkinPacks.errorOutcome(context) else GallerySkinPacks.warningOutcome(context) }
        Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg))
        SkinImportReportDialog(outcome, onDismiss = {})
    }

    /**
     * Fundo de mentira atrás do teclado de vidro (que é translúcido de verdade): uma paisagem de cores vivas
     * com linhas de texto por cima — o pior caso para a leitura das letras. A galeria não desfoca (o desfoque
     * é do compositor, numa janela à parte): é o que o A32, que não desfoca janelas, mostra.
     */
    @Composable
    private fun BehindKeyboardScene() {
        val palette = LocalKeyFlowPalette.current
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFFF2994A), Color(0xFFD63A7B), Color(0xFF3B4FBF), Color(0xFF12A594)))),
        ) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 120.dp).size(150.dp).background(Color(0x66FFFFFF), RoundedCornerShape(75.dp)))
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 40.dp).size(110.dp).background(Color(0x55FFE9A8), RoundedCornerShape(55.dp)))
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(28) { i ->
                    Box(Modifier.fillMaxWidth(listOf(0.9f, 0.62f, 0.8f, 0.45f, 0.95f, 0.7f)[i % 6]).height(9.dp).background(palette.fg.copy(alpha = 0.5f)))
                }
            }
        }
    }

    /** Fundo de mentira atrás da orbe de vidro: linhas de texto e uma faixa de cor por baixo do centro. */
    @Composable
    private fun BehindOrbScene() {
        val palette = LocalKeyFlowPalette.current
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0xFFE8743B), Color(0xFFC13584), Color(0xFF3B5BDB)))),
            )
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(0.9f, 0.7f, 0.95f, 0.5f, 0.85f, 0.6f, 0.92f, 0.75f, 0.4f, 0.88f, 0.66f, 0.8f, 0.55f, 0.9f, 0.7f, 0.85f).forEach { w ->
                    Box(Modifier.fillMaxWidth(w).height(9.dp).background(palette.fg.copy(alpha = 0.55f)))
                }
            }
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
