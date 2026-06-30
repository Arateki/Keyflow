package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.keyflow.overlay.Haptics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.OrbShortcutAction
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.common.SaveToast
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

/** testTag do painel renderizado para a aba [tab] (usado nos testes de navegação). */
fun panelTag(tab: ConfigTab): String = "panel-${tab.name.lowercase()}"

const val LANG_TOGGLE_TAG = "lang-toggle"
const val THEME_TOGGLE_TAG = "theme-toggle"
const val TOGGLE_OVERLAY_MODE_TAG = "toggle-overlay-mode"
const val TOGGLE_KEYBOARD_MODE_TAG = "toggle-keyboard-mode"

/**
 * Scaffold da configuração em abas (CFG-01..05): header (orbe, idioma inerte,
 * tema) + [TabBar] com gating + painel da aba ativa + [SaveToast]. A aba ativa é estado interno;
 * se o modo que libera a aba ativa for desligado, volta para [ConfigTab.MODO] (CFG-02).
 *
 * Aba Modo: [ModoPanel] (T11). Demais abas: placeholders até T12–T16.
 */
@Composable
fun ConfigScreen(
    settings: Settings,
    permissions: PermissionStatus,
    saveController: SaveController,
    onOverlayModeChange: (Boolean) -> Unit,
    onKeyboardModeChange: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onLanguageClick: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestKeyboardPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ConfigTab = ConfigTab.MODO,
    initialUnlockOpen: Boolean = false,
    requestedTab: ConfigTab? = null,
    requestedTabKey: Int = 0,
) {
    val palette = LocalKeyFlowPalette.current
    var activeTab by rememberSaveable { mutableStateOf(initialTab) }
    var unlockOpen by rememberSaveable { mutableStateOf(initialUnlockOpen) }
    // Atalho da orbe com o bottom sheet aberto (2 ou 3 toques); null = fechado.
    var shortcutSheetTaps by remember { mutableStateOf<Int?>(null) }

    // Navegação externa (clique no erro → Voz). A chave dispara mesmo repetindo a mesma aba.
    LaunchedEffect(requestedTabKey) {
        if (requestedTabKey > 0) requestedTab?.let { activeTab = it }
    }

    // A aba de um modo só fica "liberada" com o modo ligado E tudo pronto: permissões concedidas (mic +
    // a específica) e, para o Teclado, o KeyFlow sendo o teclado ativo. Sem isso a aba abre, mas em
    // modo somente-leitura (camada por cima) — ver o scrim mais abaixo.
    val enabled: (ConfigTab) -> Boolean = { tab ->
        when (tab) {
            // O Modo Teclado espelha o teclado ativo (keyboardActive), então gateamos por ele direto.
            ConfigTab.TECLADO -> permissions.microphoneGranted &&
                permissions.keyboardEnabled && permissions.keyboardActive
            ConfigTab.OVERLAY -> settings.overlayModeEnabled &&
                permissions.microphoneGranted && permissions.overlayGranted
            else -> true
        }
    }
    val activeEnabled = enabled(activeTab)

    // Toast discreto em Compose com duração configurável (aba travada = 1,5 s; aviso de modo = 2 s).
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastDuration by remember { mutableStateOf(1500L) }
    var toastGeneration by remember { mutableStateOf(0) }
    // Ação opcional do toast: torna a mensagem clicável e leva à opção que resolve o aviso.
    var toastAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Pulso de destaque discreto nos cards da aba Modo (insight visual ao navegar do aviso).
    var overlayHighlightKey by remember { mutableStateOf(0) }
    var keyboardHighlightKey by remember { mutableStateOf(0) }
    // Realce do seletor de Modo de gravação (aviso do atalho de 1 toque → leva à subárea responsável).
    var recordingModeHighlightKey by remember { mutableStateOf(0) }
    val hapticContext = LocalContext.current
    LaunchedEffect(toastGeneration) {
        if (toastMessage != null) {
            kotlinx.coroutines.delay(toastDuration)
            toastMessage = null
            toastAction = null
        }
    }
    fun showToast(text: String, durationMs: Long, action: (() -> Unit)? = null) {
        toastMessage = text
        toastDuration = durationMs
        toastAction = action
        toastGeneration++
    }

    // Navegação do aviso → aba Modo, destacando o card do modo que resolve o bloqueio. Null = sem atalho.
    fun navActionFor(tab: ConfigTab): (() -> Unit)? = when (tab) {
        ConfigTab.OVERLAY -> {
            { activeTab = ConfigTab.MODO; overlayHighlightKey++ }
        }
        ConfigTab.TECLADO -> {
            { activeTab = ConfigTab.MODO; keyboardHighlightKey++ }
        }
        else -> null
    }

    // Aviso de opção que depende do Modo Sobrepor: clicar leva à aba Modo e destaca o card do modo.
    val onOverlayRequired: () -> Unit = {
        showToast("Disponível só com o Modo Sobrepor ligado.", 2500, action = navActionFor(ConfigTab.OVERLAY))
    }
    fun blockReason(tab: ConfigTab): String? = when (tab) {
        ConfigTab.TECLADO -> when {
            !permissions.microphoneGranted || !permissions.keyboardEnabled ->
                "Conceda as permissões do Modo Teclado para editar esta aba."
            !permissions.keyboardActive -> "Ative o KeyFlow como teclado (ligue o Modo Teclado) para editar esta aba."
            else -> null
        }
        ConfigTab.OVERLAY -> when {
            !settings.overlayModeEnabled -> "Ligue o Modo Sobrepor para editar esta aba."
            else -> "Conceda as permissões do Modo Sobrepor para editar esta aba."
        }
        else -> null
    }

    // Ativar um modo com permissão pendente: avisa no mesmo estilo de toast (2 s). A flag ainda persiste.
    val handleOverlayMode: (Boolean) -> Unit = { on ->
        onOverlayModeChange(on)
        if (on && (!permissions.microphoneGranted || !permissions.overlayGranted)) {
            showToast("Permissões pendentes — conceda para usar o Modo Sobrepor.", 2000)
        }
    }
    val handleKeyboardMode: (Boolean) -> Unit = { on ->
        onKeyboardModeChange(on)
        if (on && (!permissions.microphoneGranted || !permissions.keyboardEnabled)) {
            showToast("Permissões pendentes — conceda para usar o Modo Teclado.", 2000)
        }
    }

    Box(modifier.fillMaxSize().background(palette.bg)) {
        // Activity é edge-to-edge: o bg cobre a tela toda, mas o conteúdo respeita as barras do
        // sistema (status no topo, navegação embaixo) para não ser cortado/ocultado.
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Header(
                dark = palette.isDark,
                onLanguageClick = onLanguageClick,
                onThemeClick = { onThemeChange(!settings.themeDark) },
            )
            TabBar(
                active = activeTab,
                enabled = enabled,
                onSelect = { tab ->
                    activeTab = tab
                    if (!enabled(tab)) blockReason(tab)?.let { showToast(it, 2500, navActionFor(tab)) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        // Aba travada: conteúdo esmaecido (somente-leitura), sob a camada incolor abaixo.
                        .then(if (activeEnabled) Modifier else Modifier.alpha(0.45f))
                        // Mesma largura que as abas ocupam (12dp), ganhando espaço horizontal no corpo.
                        .padding(horizontal = 12.dp)
                        .testTag(panelTag(activeTab)),
                ) {
                    when (activeTab) {
                        ConfigTab.MODO -> ModoPanel(
                            settings = settings,
                            permissions = permissions,
                            onOverlayModeChange = handleOverlayMode,
                            onKeyboardModeChange = handleKeyboardMode,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onRequestKeyboardPermission = onRequestKeyboardPermission,
                            onRequestMicPermission = onRequestMicPermission,
                            onSwitchKeyboard = onSwitchKeyboard,
                            overlayHighlightKey = overlayHighlightKey,
                            keyboardHighlightKey = keyboardHighlightKey,
                        )
                        ConfigTab.VOZ -> VozPanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                        )
                        ConfigTab.TECLADO -> TecladoPanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onLanguageClick = onLanguageClick,
                            onUnlockSkins = { unlockOpen = true },
                            onOverlayRequired = onOverlayRequired,
                        )
                        ConfigTab.OVERLAY -> OverlayPanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onUnlockSkins = { unlockOpen = true },
                            onOpenShortcutSheet = { shortcutSheetTaps = it },
                            onShortcut1Blocked = {
                                showToast(
                                    "O atalho de 1 toque vale apenas no modo Push-to-talk.",
                                    2500,
                                    action = { recordingModeHighlightKey++ },
                                )
                            },
                            recordingModeHighlightKey = recordingModeHighlightKey,
                            keyboardShortcutAvailable = permissions.keyboardActive,
                        )
                        ConfigTab.DOCS -> DocsPanel()
                    }
                }
                if (!activeEnabled) {
                    // Camada incolor sobre a aba travada: intercepta os toques (somente-leitura) e
                    // reexibe o motivo no toast a cada toque.
                    Box(
                        Modifier
                            .matchParentSize()
                            .testTag(READONLY_SCRIM_TAG)
                            .pointerInput(activeTab) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Press) {
                                            blockReason(activeTab)?.let { showToast(it, 2500, navActionFor(activeTab)) }
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            },
                    )
                }
            }
        }
        SaveToast(saveController, Modifier.fillMaxSize().systemBarsPadding())
        MessageToast(
            message = toastMessage,
            durationMs = toastDuration,
            generation = toastGeneration,
            onClick = toastAction?.let {
                {
                    Haptics.tick(hapticContext, settings.vibrationIntensity)
                    it()
                    toastMessage = null
                    toastAction = null
                }
            },
            modifier = Modifier.fillMaxSize().systemBarsPadding(),
        )
        UnlockSheet(visible = unlockOpen, onDismiss = { unlockOpen = false })
        ShortcutActionSheet(
            taps = shortcutSheetTaps,
            current = shortcutSheetTaps?.let { settings.shortcutForTaps(it) } ?: OrbShortcutAction.NONE,
            keyboardAvailable = permissions.keyboardActive,
            onSelect = { action ->
                when (shortcutSheetTaps) {
                    1 -> onSettingsChange(settings.copy(shortcut1Tap = action))
                    2 -> onSettingsChange(settings.copy(shortcut2Tap = action))
                    3 -> onSettingsChange(settings.copy(shortcut3Tap = action))
                }
            },
            onBlockedKeyboard = {
                showToast("Ative o Modo Teclado para usar este atalho.", 2500, navActionFor(ConfigTab.TECLADO))
            },
            onDismiss = { shortcutSheetTaps = null },
        )
    }
}

const val MESSAGE_TOAST_TAG = "message-toast"
const val READONLY_SCRIM_TAG = "readonly-scrim"

/**
 * Toast discreto (aba travada, avisos de modo). Visível enquanto [message] não for nulo. Quando
 * [onClick] é fornecido, a mensagem vira um atalho tocável que leva à opção que resolve o aviso.
 */
@Composable
private fun MessageToast(
    message: String?,
    durationMs: Long,
    generation: Int,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        if (message != null) {
            Column(
                modifier = Modifier
                    .padding(bottom = 22.dp, start = 22.dp, end = 22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.fg)
                    .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                    .testTag(MESSAGE_TOAST_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = message,
                    color = palette.onFill,
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
                // Barrinha de progresso discreta colada no contorno inferior: conta o tempo até sumir.
                val progress = remember(generation) { Animatable(1f) }
                LaunchedEffect(generation) {
                    progress.snapTo(1f)
                    progress.animateTo(0f, animationSpec = tween(durationMs.toInt(), easing = LinearEasing))
                }
                Box(
                    Modifier
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .height(2.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.value)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(palette.onFill.copy(alpha = 0.45f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    dark: Boolean,
    onLanguageClick: () -> Unit,
    onThemeClick: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        VoiceOrb(state = OrbState.Idle, modifier = Modifier.size(30.dp))
        Text(
            text = "KeyFlow",
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 19.sp,
            modifier = Modifier.weight(1f),
        )
        // Idioma — inerte nesta fase (toca → "em breve"); I18N-02.
        Row(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.line, RoundedCornerShape(6.dp))
                .clickable { onLanguageClick() }
                .testTag(LANG_TOGGLE_TAG)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            StrokeIcon(GLOBE_PATHS, palette.fg2, Modifier.size(13.dp))
            Text("PT", color = palette.fg2, fontFamily = SpaceMono, fontSize = 10.sp)
        }
        // Tema sol/lua — persiste themeDark (FND-04). Mesma altura do botão de idioma.
        Box(
            Modifier
                .size(width = 32.dp, height = 30.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, palette.line, RoundedCornerShape(6.dp))
                .clickable { onThemeClick() }
                .testTag(THEME_TOGGLE_TAG),
            contentAlignment = Alignment.Center,
        ) {
            StrokeIcon(if (dark) SUN_PATHS else MOON_PATHS, palette.fg2, Modifier.size(14.dp))
        }
    }
}

private val GLOBE_PATHS = listOf(
    "M12 12 m-9 0 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0",
    "M3 12h18",
    "M12 3c3 4 3 14 0 18",
    "M12 3c-3 4-3 14 0 18",
)
private val SUN_PATHS = listOf(
    "M12 12 m-4 0 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",
    "M12 2v2M12 20v2M4 12H2M22 12h-2M5.6 5.6 4.2 4.2M19.8 19.8l-1.4-1.4M18.4 5.6l1.4-1.4M4.2 19.8l1.4-1.4",
)
private val MOON_PATHS = listOf("M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z")
