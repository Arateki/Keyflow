package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val OVERLAY_UNLOCK_TAG = "overlay-unlock-skins"
const val ORB_SIZE_SLIDER_TAG = "overlay-size-slider"
const val TAP_BURST_SLIDER_TAG = "overlay-tap-burst-slider"

fun overlaySideTag(side: OverlaySide): String = "overlay-side-${side.name.lowercase()}"
fun overlayModeTag(mode: RecordingMode): String = "overlay-mode-${mode.name.lowercase()}"

/**
 * Aba Overlay (OVL-05 / REC modo / SKIN-01): posição inicial (Esq/Dir), tamanho da orbe (P/M/G), modo
 * de gravação (Alternar/Push-to-talk) com dica de pausa, e a seção de Skins bloqueada.
 */
@Composable
fun OverlayPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onUnlockSkins: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenShortcutSheet: (Int) -> Unit = {},
    keyboardShortcutAvailable: Boolean = true,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 6.dp, bottom = 20.dp),
    ) {
        SectionTitle("Sobrepor", top = 0.dp)

        FieldLabel("Posição inicial", top = 12.dp)
        Segmented(
            options = OverlaySide.entries,
            selected = settings.overlaySide,
            label = ::overlaySideLabel,
            tag = ::overlaySideTag,
            onSelect = { onSettingsChange(settings.copy(overlaySide = it)) },
            modifier = Modifier.padding(top = 7.dp),
        )

        FieldLabel("Tamanho da orbe", top = 16.dp)
        // Slider contínuo (px) — a orbe na tela redimensiona em tempo real (OVL-05).
        Slider(
            value = settings.orbSizePx.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(orbSizePx = it.roundToInt())) },
            valueRange = 110f..280f,
            colors = SliderDefaults.colors(
                thumbColor = palette.teal,
                activeTrackColor = palette.teal,
                inactiveTrackColor = palette.line,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag(ORB_SIZE_SLIDER_TAG),
        )

        SectionTitle("Modo de gravação", top = 16.dp)
        Segmented(
            options = RecordingMode.entries,
            selected = settings.overlayRecordingMode,
            label = ::recordingModeLabel,
            tag = ::overlayModeTag,
            onSelect = { onSettingsChange(settings.copy(overlayRecordingMode = it)) },
            modifier = Modifier.padding(top = 9.dp),
        )
        Text(
            pauseHintFor(settings.overlayRecordingMode),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        SkinsSection(onUnlock = onUnlockSkins, unlockTag = OVERLAY_UNLOCK_TAG, modifier = Modifier.padding(top = 18.dp))

        Box(Modifier.fillMaxWidth().padding(top = 18.dp).height(1.dp).background(palette.line2))
        SectionTitle("Atalhos", top = 16.dp)
        FieldLabel("Tempo de reação", top = 12.dp)
        Text(
            "Janela de reação da orbe: tempo entre toques para os atalhos e atraso até iniciar a captação. (No modelo final também separa toque de segurada.)",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        // Slider em ms (passos de 10) — define a janela de rajada de toques (atalhos + delay de start).
        Slider(
            value = settings.tapBurstWindowMs.toFloat(),
            onValueChange = {
                onSettingsChange(settings.copy(tapBurstWindowMs = (it / 10f).roundToInt() * 10))
            },
            valueRange = 100f..700f,
            colors = SliderDefaults.colors(
                thumbColor = palette.teal,
                activeTrackColor = palette.teal,
                inactiveTrackColor = palette.line,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).testTag(TAP_BURST_SLIDER_TAG),
        )
        Text(
            "${settings.tapBurstWindowMs} ms",
            color = palette.muted,
            fontFamily = SpaceMono,
            fontSize = 11.sp,
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Retorno tátil", color = palette.fg2, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.5.sp)
                Text(
                    "Vibra levemente ao interagir com a orbe.",
                    color = palette.faint,
                    fontFamily = SpaceGrotesk,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            PillToggle(
                checked = settings.hapticFeedback,
                onCheckedChange = { onSettingsChange(settings.copy(hapticFeedback = it)) },
                tag = "overlay-haptic-toggle",
            )
        }

        SectionTitle("Ações por toque", top = 16.dp)
        Text(
            "Em repouso ou pausada, toque a orbe para acionar.",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        ShortcutRow(2, settings.shortcut2Tap, keyboardShortcutAvailable) { onOpenShortcutSheet(2) }
        ShortcutRow(3, settings.shortcut3Tap, keyboardShortcutAvailable) { onOpenShortcutSheet(3) }

        SectionTitle("Ao tocar mais de 3 vezes", top = 16.dp)
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

/** Opção de radio (estilo Ateliê) para a política de overflow de toques: título + descrição curta. */
@Composable
private fun OverflowOption(selected: Boolean, title: String, sub: String, onSelect: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = palette.teal, unselectedColor = palette.muted),
            modifier = Modifier.size(28.dp),
        )
        Column(Modifier.padding(start = 8.dp, top = 3.dp)) {
            Text(title, color = palette.fg2, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.5.sp)
            Text(sub, color = palette.faint, fontFamily = SpaceGrotesk, fontSize = 9.5.sp, modifier = Modifier.padding(top = 1.dp))
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
    onClick: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val unavailable = action == OrbShortcutAction.TOGGLE_KEYBOARD && !keyboardAvailable
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, palette.line, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .testTag("overlay-shortcut-$taps")
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("$taps toques", color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text(shortcutActionLabel(action), color = palette.muted, fontFamily = SpaceGrotesk, fontSize = 10.5.sp)
            if (unavailable) {
                Text(
                    " / indisponível",
                    color = palette.terracotta,
                    fontFamily = SpaceMono,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** Título de seção no padrão intenso da aba Teclado (palette.fg / SpaceGrotesk / 13.5sp). */
@Composable
private fun SectionTitle(text: String, top: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        color = LocalKeyFlowPalette.current.fg,
        fontFamily = SpaceGrotesk,
        fontSize = 13.5.sp,
        modifier = Modifier.padding(top = top),
    )
}

@Composable
private fun FieldLabel(text: String, top: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        color = LocalKeyFlowPalette.current.faint,
        fontFamily = SpaceGrotesk,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = top),
    )
}

private fun overlaySideLabel(side: OverlaySide): String = when (side) {
    OverlaySide.LEFT -> "Esquerda"
    OverlaySide.RIGHT -> "Direita"
}
