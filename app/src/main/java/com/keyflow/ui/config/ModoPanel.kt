package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val ACTIVATE_OVERLAY_TAG = "activate-overlay"
const val ACTIVATE_KEYBOARD_TAG = "activate-keyboard"

/**
 * Aba Modo (CFG-06 / REC-07): permissão geral do microfone + cards Overlay/Keyboard. Cada card liga/
 * desliga o modo (afetando o gating das abas), mostra o chip da permissão específica (com "Ativar"
 * quando pendente), um placeholder de GIF estático e a dica de pausa contextual ao modo de gravação.
 */
@Composable
fun ModoPanel(
    settings: Settings,
    permissions: PermissionStatus,
    onOverlayModeChange: (Boolean) -> Unit,
    onKeyboardModeChange: (Boolean) -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestKeyboardPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 2.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // Microfone — permissão geral.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StrokeIcon(MIC_PATHS, palette.muted, Modifier.size(14.dp))
            Row(Modifier.weight(1f)) {
                Text("Microfone", color = palette.fg2, fontFamily = SpaceGrotesk, fontSize = 12.5.sp)
                Text(" · permissão geral", color = palette.faint, fontFamily = SpaceGrotesk, fontSize = 12.5.sp)
            }
            PermissionChip(text = if (permissions.microphoneGranted) "CONCEDIDO" else "PENDENTE", granted = permissions.microphoneGranted)
        }

        ModeCard(
            title = "Modo Overlay",
            subtitle = "Orbe flutuante sobre qualquer app.",
            checked = settings.overlayModeEnabled,
            onCheckedChange = onOverlayModeChange,
            toggleTag = TOGGLE_OVERLAY_MODE_TAG,
            permissionLabel = "SOBREPOSIÇÃO",
            permissionGranted = permissions.overlayGranted,
            onActivate = onRequestOverlayPermission,
            activateTag = ACTIVATE_OVERLAY_TAG,
            pauseHint = pauseHintFor(settings.overlayRecordingMode),
            gifHint = "Arraste o orbe e fale (mock).",
        )

        ModeCard(
            title = "Modo Keyboard",
            subtitle = "Teclado do sistema com voz.",
            checked = settings.keyboardModeEnabled,
            onCheckedChange = onKeyboardModeChange,
            toggleTag = TOGGLE_KEYBOARD_MODE_TAG,
            permissionLabel = "TECLADO",
            permissionGranted = permissions.keyboardEnabled,
            onActivate = onRequestKeyboardPermission,
            activateTag = ACTIVATE_KEYBOARD_TAG,
            pauseHint = pauseHintFor(settings.keyboardRecordingMode),
            gifHint = "Toque em Gravar e fale (mock).",
        )
    }
}

/** Dica de pausa contextual ao modo de gravação (REC-07). Reusada pela aba Docs (T15). */
fun pauseHintFor(mode: RecordingMode): String = when (mode) {
    RecordingMode.TOGGLE -> "Toque para gravar ou parar · segure para pausar e retomar."
    RecordingMode.PUSH_TO_TALK -> "Segure para gravar · solte para enviar · toque após soltar pausa."
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleTag: String,
    permissionLabel: String,
    permissionGranted: Boolean,
    onActivate: () -> Unit,
    activateTag: String,
    pauseHint: String,
    gifHint: String,
) {
    val palette = LocalKeyFlowPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, palette.line, RoundedCornerShape(6.dp))
            .background(palette.card)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                Text(subtitle, color = palette.muted, fontFamily = SpaceGrotesk, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            PillToggle(checked = checked, onCheckedChange = onCheckedChange, tag = toggleTag)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            PermissionChip(text = "$permissionLabel · ${if (permissionGranted) "CONCEDIDO" else "PENDENTE"}", granted = permissionGranted)
            if (!permissionGranted) {
                Text(
                    text = "Ativar",
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontSize = 11.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onActivate() }.testTag(activateTag),
                )
            }
        }
        Text(pauseHint, color = palette.faint, fontFamily = SpaceMono, fontSize = 8.5.sp)
        GifPlaceholder(hint = gifHint)
    }
}

@Composable
private fun GifPlaceholder(hint: String) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, palette.line, RoundedCornerShape(4.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .border(1.dp, palette.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FillIcon(PLAY_PATH, palette.faint, Modifier.size(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("GIF · COMO USAR", color = palette.faint, fontFamily = SpaceMono, fontSize = 8.5.sp)
            Text(hint, color = palette.faint, fontFamily = SpaceGrotesk, fontSize = 10.5.sp, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

@Composable
private fun PermissionChip(text: String, granted: Boolean) {
    val palette = LocalKeyFlowPalette.current
    val accent = if (granted) palette.teal else palette.recordSoft
    Text(
        text = text,
        color = accent,
        fontFamily = SpaceMono,
        fontSize = 8.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private val MIC_PATHS = listOf(
    "M12 2a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z",
    "M19 10v1a7 7 0 0 1-14 0v-1M12 18v4",
)
private const val PLAY_PATH = "M8 5v14l11-7z"
