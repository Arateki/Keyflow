package com.keyflow.ui.config

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.keyflow.overlay.Haptics
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.OverlaySide
import com.keyflow.core.ShortcutOverflow
import kotlin.math.roundToInt
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val OVERLAY_UNLOCK_TAG = "overlay-unlock-skins"
const val ORB_SIZE_SLIDER_TAG = "overlay-size-slider"
const val TAP_BURST_SLIDER_TAG = "overlay-tap-burst-slider"
const val VIBRATION_SLIDER_TAG = "overlay-vibration-slider"

// Offset da segurada sobre o "Tempo de reação" (espelha OrbButtonView.HOLD_OFFSET_MS) — só p/ exibição.
private const val HOLD_OFFSET_MS = 150

fun overlaySideTag(side: OverlaySide): String = "overlay-side-${side.name.lowercase()}"
fun overlayModeTag(mode: RecordingMode): String = "overlay-mode-${mode.name.lowercase()}"

/**
 * Aba Sobrepor reorganizada em 3 áreas principais (divisórias grossas) com subseções (divisórias finas):
 * - **Usabilidade**: Mão (canhoto/destro), Tamanho da orbe, Modo de gravação, Tempo de reação, Retorno tátil.
 * - **Skins**: seção bloqueada (SKIN-01).
 * - **Atalhos**: ações por toque (2/3) e política de overflow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onUnlockSkins: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenShortcutSheet: (Int) -> Unit = {},
    onShortcut1Blocked: () -> Unit = {},
    recordingModeHighlightKey: Int = 0,
    keyboardShortcutAvailable: Boolean = true,
    // Cartão do Modo Sobrepor, sempre no topo e sempre tocável (a aba Modo saiu — Yan, 2026-09-28).
    modeCard: (@Composable () -> Unit)? = null,
    locked: Boolean = false,
    onLockedTap: () -> Unit = {},
    // Muda ao chegar de um aviso: a aba sobe até o cartão do modo (que pisca por conta própria).
    modeCardFocusKey: Int = 0,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    LaunchedEffect(modeCardFocusKey) { if (modeCardFocusKey > 0) scroll.animateScrollTo(0) }
    val context = LocalContext.current
    // Preview tátil do slider de Vibração: última intensidade já "tocada" ao arrastar (evita floodar).
    val lastVibValue = remember { mutableStateOf(-100) }
    // Realce/scroll do seletor de Modo de gravação ao chegar do aviso do atalho de 1 toque.
    val modeHighlight = remember { Animatable(0f) }
    val modeBringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(recordingModeHighlightKey) {
        if (recordingModeHighlightKey > 0) {
            modeBringIntoView.bringIntoView()
            modeHighlight.snapTo(1f)
            modeHighlight.animateTo(0f, animationSpec = tween(durationMillis = 1700))
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        modeCard?.invoke()
        // Sem o modo pronto (ligado e com as permissões), o resto da aba só se lê.
        LockedSection(locked = locked, onLockedTap = onLockedTap) {
            // ── ÁREA 1 · USABILIDADE ───────────────────────────────────────────
            SettingsGroup("Usabilidade") {
                GroupItem(
                    "Mão preferencial",
                    "Encosta a orbe no seu lado e define a direção do arrasto de cancelar, sempre para o centro, longe da borda.",
                ) {
                    Segmented(
                        options = OverlaySide.entries,
                        selected = settings.overlaySide,
                        label = ::overlaySideLabel,
                        tag = ::overlaySideTag,
                        onSelect = { onSettingsChange(settings.copy(overlaySide = it)) },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
                GroupDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(modeBringIntoView)
                        .clip(RoundedCornerShape(12.dp))
                        // Pulso teal discreto que esvai ao chegar do aviso (não desloca o layout).
                        .background(palette.teal.copy(alpha = modeHighlight.value * 0.14f)),
                ) {
                    // Descrição acima do seletor (como as outras), mas dinâmica: segue o modo selecionado.
                    GroupItem("Modo de gravação", pauseHintFor(settings.overlayRecordingMode)) {
                        Segmented(
                            options = RecordingMode.entries,
                            selected = settings.overlayRecordingMode,
                            label = ::recordingModeLabel,
                            tag = ::overlayModeTag,
                            onSelect = { onSettingsChange(settings.copy(overlayRecordingMode = it)) },
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
                GroupDivider()
                // Slider contínuo (px) — a orbe na tela redimensiona em tempo real (OVL-05).
                GroupItem("Tamanho da orbe") {
                    KfSlider(
                        value = settings.orbSizePx.toFloat(),
                        onValueChange = { onSettingsChange(settings.copy(orbSizePx = it.roundToInt())) },
                        valueRange = 110f..280f,
                        tag = ORB_SIZE_SLIDER_TAG,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                GroupDivider()
                GroupItem(
                    "Tempo de reação",
                    "Janela de reação da orbe: tempo entre toques para os atalhos e atraso até iniciar a captação.",
                ) {
                    // Slider em ms (passos de 10) — define a janela de rajada de toques (atalhos + delay de start).
                    KfSlider(
                        value = settings.tapBurstWindowMs.toFloat(),
                        onValueChange = {
                            onSettingsChange(settings.copy(tapBurstWindowMs = (it / 10f).roundToInt() * 10))
                        },
                        valueRange = 100f..700f,
                        tag = TAP_BURST_SLIDER_TAG,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    // Dois limiares derivados: toque (valor) e segurar (valor + 150ms, espelha HOLD_OFFSET_MS).
                    Text(
                        "toque ${settings.tapBurstWindowMs} ms  ·  segurar ${settings.tapBurstWindowMs + HOLD_OFFSET_MS} ms",
                        color = palette.fg2,
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Light,
                        fontSize = 10.5.sp,
                    )
                    Text(
                        "ms = milissegundos",
                        color = palette.faint,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Light,
                        fontSize = 8.5.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                GroupDivider()
                GroupItem("Vibração", "Intensidade da vibração ao interagir com a orbe. No zero, sem vibração.") {
                    KfSlider(
                        value = settings.vibrationIntensity.toFloat(),
                        onValueChange = {
                            val v = it.roundToInt()
                            onSettingsChange(settings.copy(vibrationIntensity = v))
                            // Vibra ao arrastar pra dar pra sentir a intensidade — a cada ~5 passos, na força atual.
                            if (v > 0 && abs(v - lastVibValue.value) >= 5) {
                                Haptics.tick(context, v)
                                lastVibValue.value = v
                            } else if (v == 0) {
                                lastVibValue.value = 0
                            }
                        },
                        valueRange = 0f..100f,
                        tag = VIBRATION_SLIDER_TAG,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            // ── ÁREA 2 · SKINS ─────────────────────────────────────────────────
            SettingsGroup(null) {
                SkinsSection(onUnlock = onUnlockSkins, unlockTag = OVERLAY_UNLOCK_TAG, modifier = Modifier.padding(bottom = 16.dp))
            }

            // ── ÁREA 3 · ATALHOS ───────────────────────────────────────────────
            SettingsGroup("Atalhos") {
                GroupItem(
                    "Ações por toque",
                    "Toque a orbe para acionar. 2 e 3 toques valem nos dois modos; 1 toque apenas no modo Push-to-talk (onde o toque é livre — a gravação começa ao segurar).",
                ) {
                    val pttMode = settings.overlayRecordingMode == RecordingMode.PUSH_TO_TALK
                    Column(Modifier.padding(top = 8.dp)) {
                        ShortcutRow(
                            taps = 1,
                            action = settings.shortcut1Tap,
                            keyboardAvailable = keyboardShortcutAvailable,
                            enabled = pttMode,
                            disabledNote = "Disponível apenas no modo Push-to-talk",
                        ) { if (pttMode) onOpenShortcutSheet(1) else onShortcut1Blocked() }
                        ShortcutRow(2, settings.shortcut2Tap, keyboardShortcutAvailable) { onOpenShortcutSheet(2) }
                        ShortcutRow(3, settings.shortcut3Tap, keyboardShortcutAvailable) { onOpenShortcutSheet(3) }
                    }
                }
                GroupDivider()
                GroupItem("Ao tocar mais de 3 vezes") {
                    OverflowOption(
                        selected = settings.shortcutOverflow == ShortcutOverflow.NEAREST_DOWN,
                        title = "Usar o atalho anterior",
                        sub = "Cai no atalho ativo de menos toques mais próximo (ex.: 4 toques aciona o de 3).",
                        onSelect = { onSettingsChange(settings.copy(shortcutOverflow = ShortcutOverflow.NEAREST_DOWN)) },
                    )
                    OverflowOption(
                        selected = settings.shortcutOverflow == ShortcutOverflow.CANCEL,
                        title = "Não fazer nada",
                        sub = "Ignora os toques extras; o gesto é cancelado.",
                        onSelect = { onSettingsChange(settings.copy(shortcutOverflow = ShortcutOverflow.CANCEL)) },
                    )
                }
            }
        }
    }
}

/** Opção de radio (estilo Ateliê) para a política de overflow de toques: título + descrição curta. */
@Composable
private fun OverflowOption(selected: Boolean, title: String, sub: String, onSelect: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect() }
            .semantics { this.selected = selected }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        KfRadio(selected, Modifier.padding(top = 1.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 11.5.sp)
            SettingSub(sub, Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Card de um atalho (estilo botão/select com borda): rótulo "N toques" em mono + a ação atual em
 * destaque. Se a ação é retrair/expandir e o teclado KeyFlow não está ativo, reflete "/ indisponível".
 */
@Composable
private fun ShortcutRow(
    taps: Int,
    action: OrbShortcutAction,
    keyboardAvailable: Boolean,
    enabled: Boolean = true,
    disabledNote: String? = null,
    onClick: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val unavailable = enabled && action == OrbShortcutAction.TOGGLE_KEYBOARD && !keyboardAvailable
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .then(if (!enabled) Modifier.alpha(0.5f) else Modifier) // desabilitado (ex.: 1 toque fora do PTT)
            .clip(RoundedCornerShape(14.dp))
            // Dentro do cartão do grupo: bloco recuado (papel do fundo), não outra caixa de contorno.
            .background(palette.bg)
            .clickable { onClick() }
            .testTag("overlay-shortcut-$taps")
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(if (taps == 1) "1 toque" else "$taps toques", color = palette.muted)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                Text(
                    if (enabled) shortcutActionLabel(action) else (disabledNote ?: shortcutActionLabel(action)),
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontSize = 12.sp,
                )
                if (unavailable) {
                    Text(
                        " / indisponível",
                        color = palette.terracotta,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Light,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        KfIcon(KfIcons.ChevronRight, palette.faint, Modifier.size(16.dp))
    }
}

private fun overlaySideLabel(side: OverlaySide): String = when (side) {
    OverlaySide.LEFT -> "Canhoto"
    OverlaySide.RIGHT -> "Destro"
}
