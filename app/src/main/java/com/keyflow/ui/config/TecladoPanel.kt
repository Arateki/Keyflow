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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.Settings
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val TECLADO_AUTO_PASTE_IN_FIELD_TAG = "teclado-auto-paste-in-field"
const val TECLADO_AUTO_PASTE_ON_FOCUS_TAG = "teclado-auto-paste-on-focus"
const val TECLADO_LANGUAGE_TAG = "teclado-language"
const val TECLADO_UNLOCK_TAG = "teclado-unlock-skins"

/**
 * Aba Teclado (KBD config / SKIN-01): colar automaticamente, idioma do teclado (inerte nesta
 * fase) e a seção de Skins bloqueada. O teclado não tem modo de gravação (toque + seletor por hold no mic).
 */
@Composable
fun TecladoPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    onLanguageClick: () -> Unit,
    onUnlockSkins: () -> Unit,
    onOverlayRequired: () -> Unit = {},
    modifier: Modifier = Modifier,
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
        Text("Colar transcrição automaticamente", color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.5.sp)
        Text(
            "Quando você precisa que sua fala preencha os campos sem sua ação manual.",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
        AutoPasteRow(
            label = "Ao gravar com um campo de texto aberto",
            sub = "Cola direto quando a transcrição volta.",
            checked = settings.autoPasteInField,
            onCheckedChange = { onSettingsChange(settings.copy(autoPasteInField = it)) },
            tag = TECLADO_AUTO_PASTE_IN_FIELD_TAG,
        )
        AutoPasteRow(
            // "Gravar fora" (sem campo focado) só acontece pelo Modo Sobrepor; sem ele a opção não faz
            // sentido e fica inerte (mesmo estilo de abas/menus inativos), explicando o porquê no toque.
            label = "Ao tocar num campo depois de gravar fora",
            sub = "Guarda o texto e cola quando você foca um campo.",
            checked = settings.autoPasteOnFocus,
            onCheckedChange = { onSettingsChange(settings.copy(autoPasteOnFocus = it)) },
            tag = TECLADO_AUTO_PASTE_ON_FOCUS_TAG,
            enabled = settings.overlayModeEnabled,
            onDisabledClick = onOverlayRequired,
        )
        Box(Modifier.fillMaxWidth().padding(top = 13.dp).height(1.dp).background(palette.line2))

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

        Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(palette.line2))
        SkinsSection(onUnlock = onUnlockSkins, unlockTag = TECLADO_UNLOCK_TAG)
    }
}

/**
 * Linha de um cenário de cola automática: rótulo + descrição curta à esquerda, [PillToggle] à direita.
 * Quando [enabled] é falso a linha fica esmaecida (somente-leitura) e o toque chama [onDisabledClick]
 * em vez de alternar — mesmo padrão das abas/menus inativos.
 */
@Composable
private fun AutoPasteRow(
    label: String,
    sub: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
    onDisabledClick: () -> Unit = {},
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier else Modifier.clickable { onDisabledClick() })
            .padding(top = 12.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = palette.fg2, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.5.sp)
            Text(sub, color = palette.faint, fontFamily = SpaceGrotesk, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
        }
        PillToggle(checked = checked, onCheckedChange = onCheckedChange, tag = tag, enabled = enabled)
    }
}
