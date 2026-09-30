package com.keyflow.ui.config

import com.keyflow.i18n.tr
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.overlay.Haptics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.common.SaveController
import com.keyflow.ui.common.SaveToast
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

/** testTag do painel renderizado para a aba [tab] (usado nos testes de navegação). */
fun panelTag(tab: ConfigTab): String = "panel-${tab.name.lowercase()}"

const val LANG_TOGGLE_TAG = "lang-toggle"

/** Opção do menu de idioma do cabeçalho. */
fun languageOptionTag(language: AppLanguage) = "lang-option-${language.code}"
const val THEME_TOGGLE_TAG = "theme-toggle"
const val HEADER_TAB_TITLE_TAG = "header-tab-title"
const val HEADER_TAB_HELP_TAG = "header-tab-help"
const val TAB_HELP_TAG = "tab-help"
const val TAB_HELP_CLOSE_TAG = "tab-help-close"
const val TAB_HELP_DOCS_TAG = "tab-help-docs"
const val TOGGLE_OVERLAY_MODE_TAG = "toggle-overlay-mode"
const val TOGGLE_KEYBOARD_MODE_TAG = "toggle-keyboard-mode"

// Atraso entre o toast de "permissões pendentes" subir e a tela/diálogo da permissão abrir sozinha —
// só o suficiente pro usuário ler o aviso antes da navegação tomar a tela. `internal`: o teste avança o
// relógio do Compose exatamente por ele, em vez de esperar em tempo real.
internal const val AUTO_PERMISSION_DELAY_MS = 400L

/**
 * Scaffold da configuração em abas (CFG-01..05): header (orbe, idioma inerte,
 * tema) + [NavRail] lateral + painel da aba ativa + [SaveToast]. A aba ativa é estado interno.
 *
 * Teclado e Sobrepor abrem com o cartão do próprio modo no topo ([KeyboardModeCard]/[OverlayModeCard] —
 * a aba Modo saiu, Yan, 2026-09-28); sem o modo pronto, o resto da aba fica só para leitura
 * ([LockedSection]) e o toque explica por quê.
 */
@Composable
fun ConfigScreen(
    settings: Settings,
    permissions: PermissionStatus,
    saveController: SaveController,
    onOverlayModeChange: (Boolean) -> Unit,
    onKeyboardModeChange: (Boolean) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    // Idioma da interface escolhido no cabeçalho (PT/EN/ES). Vale na hora: ver AppLocale.
    onAppLanguageChange: (AppLanguage) -> Unit = {},
    onRequestOverlayPermission: () -> Unit,
    onRequestKeyboardPermission: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSettingsChange: (Settings) -> Unit,
    barsConfig: BarsConfig = BarsConfig.DEFAULT,
    onBarsConfigChange: (BarsConfig) -> Unit = {},
    shortcutGrids: List<ShortcutGrid> = emptyList(),
    onShortcutGridsChange: (List<ShortcutGrid>) -> Unit = {},
    modifier: Modifier = Modifier,
    initialTab: ConfigTab = ConfigTab.VOZ,
    initialUnlockOpen: Boolean = false,
    initialTabHelpOpen: Boolean = false,
    initialRailExpanded: Boolean = false,
    requestedTab: ConfigTab? = null,
    requestedTabKey: Int = 0,
    localModels: LocalModelsUi = LocalModelsUi.EMPTY,
    // Os modelos de TEXTO da aba Raciocínio (a lista de cima é a dos de fala, da Transcrição).
    reasoningModels: LocalModelsUi = LocalModelsUi.EMPTY_TEXT,
    localActions: LocalModelActions = LocalModelActions.None,
) {
    val palette = LocalKeyFlowPalette.current
    var activeTab by rememberSaveable { mutableStateOf(initialTab) }
    var unlockOpen by rememberSaveable { mutableStateOf(initialUnlockOpen) }
    // Barra lateral: recolhida (só ícones) é a posição normal; a setinha abre com os nomes.
    var railExpanded by rememberSaveable { mutableStateOf(initialRailExpanded) }
    // "?" do cabeçalho: a seção da Docs da aba aberta num modal; "Ver na Docs" rola até ela na aba Docs.
    var tabHelpOpen by rememberSaveable { mutableStateOf(initialTabHelpOpen) }
    var docsFocusSection by remember { mutableStateOf<String?>(null) }
    var docsFocusKey by remember { mutableStateOf(0) }
    // Atalho da orbe com o bottom sheet aberto (2 ou 3 toques); null = fechado.
    var shortcutSheetTaps by remember { mutableStateOf<Int?>(null) }

    // A aba de um modo só fica "liberada" com o modo ligado E tudo pronto: permissões concedidas (mic +
    // a específica) e, para o Teclado, o KeyFlow sendo o teclado ativo. Sem isso o cartão do modo segue
    // tocável no topo e o resto da aba fica só para leitura (LockedSection).
    val enabled: (ConfigTab) -> Boolean = { tab ->
        when (tab) {
            // O Modo Teclado espelha o teclado ativo (keyboardActive), então gateamos por ele direto.
            ConfigTab.TECLADO -> permissions.microphoneGranted &&
                permissions.keyboardEnabled && permissions.keyboardActive
            ConfigTab.OVERLAY -> settings.overlayModeEnabled &&
                permissions.microphoneGranted && permissions.overlayGranted && permissions.notificationsGranted
            else -> true
        }
    }

    // Toast discreto em Compose com duração configurável (aba travada = 1,5 s; aviso de modo = 2 s).
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastDuration by remember { mutableStateOf(1500L) }
    var toastGeneration by remember { mutableStateOf(0) }
    // Ação opcional do toast: torna a mensagem clicável e leva à opção que resolve o aviso.
    var toastAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Pulso de destaque discreto no cartão do modo (insight visual ao chegar de um aviso); a aba também
    // sobe até ele.
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

    // Permissão pendente resolvida sozinha, pouco depois do toast subir (mesmo padrão dos dois cards:
    // Sobrepor e Teclado): o usuário nem precisa achar o chip — a tela/diálogo do sistema já aparece.
    var pendingPermissionRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingPermissionGeneration by remember { mutableStateOf(0) }
    LaunchedEffect(pendingPermissionGeneration) {
        val request = pendingPermissionRequest ?: return@LaunchedEffect
        kotlinx.coroutines.delay(AUTO_PERMISSION_DELAY_MS)
        pendingPermissionRequest = null
        request()
    }
    fun requestPendingPermissionSoon(request: () -> Unit) {
        pendingPermissionRequest = request
        pendingPermissionGeneration++
    }

    // Navegação do aviso → aba da interface, no topo, destacando o cartão do modo que resolve o bloqueio.
    // Null = sem atalho.
    fun navActionFor(tab: ConfigTab): (() -> Unit)? = when (tab) {
        ConfigTab.OVERLAY -> {
            { activeTab = ConfigTab.OVERLAY; overlayHighlightKey++ }
        }
        ConfigTab.TECLADO -> {
            { activeTab = ConfigTab.TECLADO; keyboardHighlightKey++ }
        }
        else -> null
    }

    // Navegação externa (clique no erro → Transcrição, ou falta de microfone → aba da interface que avisou,
    // já no cartão do modo). A chave dispara mesmo repetindo a mesma aba.
    LaunchedEffect(requestedTabKey) {
        if (requestedTabKey > 0) requestedTab?.let { tab -> navActionFor(tab)?.invoke() ?: run { activeTab = tab } }
    }

    // Aviso de opção que depende do Modo Sobrepor: clicar leva à aba Sobrepor e destaca o cartão do modo.
    val onOverlayRequired: () -> Unit = {
        showToast(tr("Disponível só com o Modo Sobrepor ligado."), 2500, action = navActionFor(ConfigTab.OVERLAY))
    }
    fun blockReason(tab: ConfigTab): String? = when (tab) {
        ConfigTab.TECLADO -> when {
            !permissions.microphoneGranted || !permissions.keyboardEnabled ->
                tr("Conceda as permissões do Modo Teclado, no topo, para editar o resto da aba.")
            !permissions.keyboardActive -> tr("Ative o KeyFlow como teclado (ligue o Modo Teclado, no topo) para editar o resto da aba.")
            else -> null
        }
        ConfigTab.OVERLAY -> when {
            !settings.overlayModeEnabled -> tr("Ligue o Modo Sobrepor, no topo, para editar o resto da aba.")
            else -> tr("Conceda as permissões do Modo Sobrepor, no topo, para editar o resto da aba.")
        }
        else -> null
    }

    // Ativar um modo com permissão pendente: avisa (2 s) e, pouco depois, abre a tela/diálogo da
    // permissão que falta sozinho — o usuário não precisa procurar o chip. O microfone NUNCA bloqueia
    // (mic é só pro botão de gravar; a orbe/teclado seguem úteis com atalhos/digitação sem ele) — falta
    // permissão de mic só pulsa o chip correspondente (ver StatusChip.pulsing), nos dois cards.
    val handleOverlayMode: (Boolean) -> Unit = { on ->
        onOverlayModeChange(on)
        if (on && (!permissions.overlayGranted || !permissions.notificationsGranted)) {
            showToast(tr("Permissões pendentes — conceda para usar o Modo Sobrepor."), 2000)
            requestPendingPermissionSoon {
                if (!permissions.overlayGranted) onRequestOverlayPermission() else onRequestNotificationPermission()
            }
        }
    }
    val handleKeyboardMode: (Boolean) -> Unit = { on ->
        onKeyboardModeChange(on)
        if (on && !permissions.keyboardEnabled) {
            showToast(tr("Permissões pendentes — conceda para usar o Modo Teclado."), 2000)
            requestPendingPermissionSoon { onRequestKeyboardPermission() }
        }
    }

    Box(modifier.fillMaxSize().background(palette.bg)) {
        // Activity é edge-to-edge: o bg cobre a tela toda, mas o conteúdo respeita as barras do
        // sistema (status no topo, navegação embaixo) para não ser cortado/ocultado.
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Header(
                tabTitle = activeTab.label,
                onTabHelp = if (docsSectionFor(activeTab) != null) ({ tabHelpOpen = true }) else null,
                dark = palette.isDark,
                language = AppLocale.current,
                onLanguageChange = onAppLanguageChange,
                onThemeClick = { onThemeChange(!settings.themeDark) },
            )
            Box(Modifier.fillMaxSize()) {
            // A barra lateral recolhida ocupa uma faixa fixa à esquerda; aberta, desenha por cima do
            // painel (mais abaixo, fora desta Row) sem mudar a largura dele.
            Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(NAV_RAIL_COLLAPSED_WIDTH).fillMaxHeight()) {
                // Fio vertical separando a navegação do conteúdo.
                Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(palette.line2))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // As prévias das abas (tour da orbe nos cartões) seguem a skin da orbe escolhida na aba
                // Sobrepor, como a orbe de verdade; o orbe do cabeçalho é a marca do app e fica como está.
                androidx.compose.runtime.CompositionLocalProvider(
                    com.keyflow.ui.orb.LocalOrbStyle provides com.keyflow.ui.orb.orbStyleFor(settings.orbSkin),
                ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp, end = 16.dp)
                        .testTag(panelTag(activeTab)),
                ) {
                    // Toque no que está travado: explica e leva ao cartão do modo, no topo da mesma aba.
                    fun lockedTap(tab: ConfigTab): () -> Unit = {
                        blockReason(tab)?.let { showToast(it, 2500, navActionFor(tab)) }
                    }
                    when (activeTab) {
                        ConfigTab.VOZ -> VozPanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            localModels = localModels,
                            localActions = localActions,
                        )
                        ConfigTab.TECLADO -> TecladoPanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onUnlockSkins = { unlockOpen = true },
                            onOverlayRequired = onOverlayRequired,
                            barsConfig = barsConfig,
                            onBarsConfigChange = onBarsConfigChange,
                            shortcutGrids = shortcutGrids,
                            onShortcutGridsChange = onShortcutGridsChange,
                            modeCard = {
                                KeyboardModeCard(
                                    settings = settings,
                                    permissions = permissions,
                                    onKeyboardModeChange = handleKeyboardMode,
                                    onRequestKeyboardPermission = onRequestKeyboardPermission,
                                    onRequestMicPermission = onRequestMicPermission,
                                    onSwitchKeyboard = onSwitchKeyboard,
                                    highlightKey = keyboardHighlightKey,
                                )
                            },
                            locked = !enabled(ConfigTab.TECLADO),
                            onLockedTap = lockedTap(ConfigTab.TECLADO),
                            modeCardFocusKey = keyboardHighlightKey,
                        )
                        ConfigTab.OVERLAY -> OverlayPanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            onUnlockSkins = { unlockOpen = true },
                            onOpenShortcutSheet = { shortcutSheetTaps = it },
                            onShortcut1Blocked = {
                                showToast(
                                    tr("O atalho de 1 toque vale apenas no modo Push-to-talk."),
                                    2500,
                                    action = { recordingModeHighlightKey++ },
                                )
                            },
                            recordingModeHighlightKey = recordingModeHighlightKey,
                            keyboardShortcutAvailable = permissions.keyboardActive,
                            modeCard = {
                                OverlayModeCard(
                                    settings = settings,
                                    permissions = permissions,
                                    onOverlayModeChange = handleOverlayMode,
                                    onRequestOverlayPermission = onRequestOverlayPermission,
                                    onRequestMicPermission = onRequestMicPermission,
                                    onRequestNotificationPermission = onRequestNotificationPermission,
                                    highlightKey = overlayHighlightKey,
                                )
                            },
                            locked = !enabled(ConfigTab.OVERLAY),
                            onLockedTap = lockedTap(ConfigTab.OVERLAY),
                            modeCardFocusKey = overlayHighlightKey,
                        )
                        ConfigTab.RACIOCINIO -> ReasoningPanel(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            models = reasoningModels,
                            actions = localActions,
                        )
                        // Ainda sem conteúdo: o painel fica vazio (o título da aba já aparece no cabeçalho).
                        ConfigTab.NOTIFICACAO, ConfigTab.VISAO, ConfigTab.FALA -> Unit
                        ConfigTab.DOCS -> DocsPanel(focusSectionId = docsFocusSection, focusKey = docsFocusKey)
                    }
                }
                }
            }
            }
            if (railExpanded) NavRailScrim(onDismiss = { railExpanded = false }, modifier = Modifier.matchParentSize())
            NavRail(
                active = activeTab,
                // Nenhuma aba fica esmaecida no menu: a de uma interface desligada é justamente onde ligá-la.
                enabled = { true },
                onSelect = { tab -> activeTab = tab },
                expanded = railExpanded,
                onExpandedChange = { railExpanded = it },
            )
            }
        }
        val helpSection = docsSectionFor(activeTab)
        if (tabHelpOpen && helpSection != null) {
            ConfigModal(
                title = helpSection.title,
                onDismiss = { tabHelpOpen = false },
                closeTag = TAB_HELP_CLOSE_TAG,
                cardTag = TAB_HELP_TAG,
            ) {
                DocBlocks(helpSection.blocks)
                SecondaryButton(
                    tr("Ver na Docs"),
                    tag = TAB_HELP_DOCS_TAG,
                    onClick = {
                        tabHelpOpen = false
                        activeTab = ConfigTab.DOCS
                        docsFocusSection = helpSection.id
                        docsFocusKey++
                    },
                    modifier = Modifier.padding(top = 18.dp),
                )
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
                showToast(tr("Ative o Modo Teclado para usar este atalho."), 2500, navActionFor(ConfigTab.TECLADO))
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
                    .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.fg)
                    .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                    .testTag(MESSAGE_TOAST_TAG),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = message,
                    color = palette.onFill,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Normal,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 13.dp, bottom = 4.dp),
                )
                // Barrinha de progresso discreta colada no contorno inferior: conta o tempo até sumir.
                val progress = remember(generation) { Animatable(1f) }
                LaunchedEffect(generation) {
                    progress.snapTo(1f)
                    progress.animateTo(0f, animationSpec = tween(durationMs.toInt(), easing = LinearEasing))
                }
                Box(
                    Modifier
                        .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp)
                        .fillMaxWidth()
                        .height(2.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.value)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(palette.onFill.copy(alpha = 0.35f)),
                    )
                }
            }
        }
    }
}

/**
 * Cabeçalho fixo: orbe + "KeyFlow" e a cápsula idioma/tema; na linha de baixo, centralizado e menor, o
 * nome da aba aberta (pedido do Yan, 2026-09-28 — com o menu recolhido só em ícones, era o único jeito de
 * saber onde se está sem abrir o menu). Fica fora da rolagem, como o resto do cabeçalho.
 */
@Composable
private fun Header(
    tabTitle: String,
    onTabHelp: (() -> Unit)?,
    dark: Boolean,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeClick: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    // Compacto de propósito: a linha do nome da aba custaria ~25dp no A32 (a fonte do app e a do sistema
    // também a aumentam) — as folgas da linha de cima devolvem parte disso.
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceOrb(state = OrbState.Idle, modifier = Modifier.size(28.dp))
        Text(
            text = "KeyFlow",
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 19.sp,
            letterSpacing = (-0.3).sp,
            modifier = Modifier.padding(start = 9.dp).weight(1f),
        )
        // Idioma e tema numa só cápsula de linha fina, separados por um fio — menos caixas no topo.
        val capsule = RoundedCornerShape(50)
        Row(
            Modifier
                .height(34.dp)
                .clip(capsule)
                .border(1.dp, palette.line, capsule),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Idioma da interface: o toque abre os três (cada um no próprio idioma) e a troca vale na hora.
            var languageMenu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier
                        .fillMaxHeight()
                        .clickable { languageMenu = true }
                        .testTag(LANG_TOGGLE_TAG)
                        .padding(start = 12.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KfIcon(KfIcons.Globe, palette.fg2, Modifier.size(15.dp))
                    Text(
                        language.short,
                        color = palette.fg2,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Medium,
                        fontSize = 9.5.sp,
                        letterSpacing = 1.sp,
                    )
                }
                DropdownMenu(
                    expanded = languageMenu,
                    onDismissRequest = { languageMenu = false },
                    containerColor = palette.card,
                ) {
                    AppLanguage.entries.forEach { option ->
                        val isActive = option == language
                        DropdownMenuItem(
                            text = {
                                Text(
                                    option.nativeName,
                                    color = if (isActive) palette.fg else palette.muted,
                                    fontFamily = SpaceGrotesk,
                                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                                    fontSize = 13.sp,
                                )
                            },
                            onClick = {
                                languageMenu = false
                                if (!isActive) onLanguageChange(option)
                            },
                            modifier = Modifier.testTag(languageOptionTag(option)),
                        )
                    }
                }
            }
            Box(Modifier.width(1.dp).height(16.dp).background(palette.line))
            // Tema sol/lua — persiste themeDark (FND-04).
            Box(
                Modifier
                    .fillMaxHeight()
                    .clickable { onThemeClick() }
                    .testTag(THEME_TOGGLE_TAG)
                    .padding(start = 10.dp, end = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                KfIcon(if (dark) KfIcons.Sun else KfIcons.Moon, palette.fg2, Modifier.size(15.dp))
            }
        }
    }
    // Nome da aba centralizado; o "?" logo à direita abre a seção da Docs dela (Yan, 2026-09-28).
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Contrapeso do "?" à esquerda: sem ele, o nome sairia do centro.
            if (onTabHelp != null) Spacer(Modifier.width(HEADER_HELP_SIZE))
            Text(
                text = tabTitle,
                color = palette.fg2,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp,
                lineHeight = 13.sp,
                letterSpacing = 0.2.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.testTag(HEADER_TAB_TITLE_TAG),
            )
            if (onTabHelp != null) {
                HelpButton(tr("Sobre a aba %s", tabTitle), HEADER_TAB_HELP_TAG, onTabHelp, Modifier.size(HEADER_HELP_SIZE))
            }
        }
    }
    }
}

/** Alvo do "?" ao lado do nome da aba — menor que os 28dp de sempre, para a linha não crescer muito. */
private val HEADER_HELP_SIZE = 24.dp
