package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.OrbSize
import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val OVERLAY_UNLOCK_TAG = "overlay-unlock-skins"

fun overlaySideTag(side: OverlaySide): String = "overlay-side-${side.name.lowercase()}"
fun orbSizeTag(size: OrbSize): String = "overlay-size-${size.name.lowercase()}"
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
) {
    val palette = LocalKeyFlowPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 6.dp, bottom = 20.dp),
    ) {
        Text("Overlay".uppercase(), color = palette.faint, fontFamily = SpaceMono, fontSize = 8.5.sp)

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
        Segmented(
            options = OrbSize.entries,
            selected = settings.orbSize,
            label = ::orbSizeLabel,
            tag = ::orbSizeTag,
            onSelect = { onSettingsChange(settings.copy(orbSize = it)) },
            modifier = Modifier.padding(top = 7.dp),
        )

        Text(
            "Modo de gravação".uppercase(),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
            modifier = Modifier.padding(top = 16.dp),
        )
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
    }
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

private fun orbSizeLabel(size: OrbSize): String = when (size) {
    OrbSize.SMALL -> "P"
    OrbSize.MEDIUM -> "M"
    OrbSize.LARGE -> "G"
}
