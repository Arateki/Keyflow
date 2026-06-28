package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val TECLADO_AUTO_PASTE_TAG = "teclado-auto-paste"
const val TECLADO_LANGUAGE_TAG = "teclado-language"
const val TECLADO_UNLOCK_TAG = "teclado-unlock-skins"

fun tecladoModeTag(mode: RecordingMode): String = "teclado-mode-${mode.name.lowercase()}"

/**
 * Aba Teclado (KBD config / REC modo / SKIN-01): colar automaticamente, idioma do teclado (inerte nesta
 * fase), modo de gravação (Alternar/Push-to-talk) com dica de pausa, e a seção de Skins bloqueada.
 */
@Composable
fun TecladoPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onLanguageClick: () -> Unit,
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
        Text("Teclado".uppercase(), color = palette.faint, fontFamily = SpaceMono, fontSize = 8.5.sp)

        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Colar automaticamente", color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.5.sp)
            PillToggle(
                checked = settings.keyboardAutoPaste,
                onCheckedChange = { onSettingsChange(settings.copy(keyboardAutoPaste = it)) },
                tag = TECLADO_AUTO_PASTE_TAG,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line2))

        // Idioma do teclado — inerte nesta fase (i18n real = futuro).
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onLanguageClick() }
                .testTag(TECLADO_LANGUAGE_TAG)
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Idioma do teclado", color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.5.sp)
            Text("pt-BR  ›", color = palette.muted, fontFamily = SpaceMono, fontSize = 12.sp)
        }

        Text(
            "Modo de gravação".uppercase(),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Segmented(
            options = RecordingMode.entries,
            selected = settings.keyboardRecordingMode,
            label = ::recordingModeLabel,
            tag = ::tecladoModeTag,
            onSelect = { onSettingsChange(settings.copy(keyboardRecordingMode = it)) },
            modifier = Modifier.padding(top = 9.dp),
        )
        Text(
            pauseHintFor(settings.keyboardRecordingMode),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 8.5.sp,
            modifier = Modifier.padding(top = 8.dp),
        )

        Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(palette.line2))
        SkinsSection(onUnlock = onUnlockSkins, unlockTag = TECLADO_UNLOCK_TAG)
    }
}
