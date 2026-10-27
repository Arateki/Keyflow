package com.keyflow.ui.config

import com.keyflow.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.OrbShortcutAction
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val SHORTCUT_SHEET_TAG = "shortcut-action-sheet"

/** Rótulo da ação de atalho (lista da aba Sobrepor e bottom sheet). */
fun shortcutActionLabel(action: OrbShortcutAction): String = when (action) {
    OrbShortcutAction.NONE -> tr("Nenhuma")
    OrbShortcutAction.TOGGLE_KEYBOARD -> tr("Retrair / expandir teclado")
    OrbShortcutAction.SWITCH_IME -> tr("Trocar teclado do sistema")
    OrbShortcutAction.VISION_MODE -> tr("Capturar a tela (Visão)")
}

/** Explicação da ação de atalho — reusada pelo tour de gestos (`ui/tour`) como descrição do roteiro. */
fun shortcutActionSub(action: OrbShortcutAction): String = when (action) {
    OrbShortcutAction.NONE -> tr("Sem ação para este gesto.")
    OrbShortcutAction.TOGGLE_KEYBOARD -> tr("Encolhe ou expande o teclado KeyFlow na tela.")
    OrbShortcutAction.SWITCH_IME -> tr("Abre o seletor de teclados do sistema.")
    OrbShortcutAction.VISION_MODE ->
        tr("Seleciona uma área da tela e copia a imagem, o texto ou a tradução. Vale também durante a gravação: ela pausa e volta como estava.")
}

/**
 * Bottom sheet de escolha da ação de um atalho (2 ou 3 toques) — estilo [UnlockSheet]. As ações
 * indisponíveis no momento (ex.: retrair/expandir sem o KeyFlow ativo) seguem o fluxo de menus
 * bloqueados: esmaecidas e, ao toque, disparam [onBlockedKeyboard] (toast + redirecionamento).
 */
@Composable
fun ShortcutActionSheet(
    taps: Int?,
    current: OrbShortcutAction,
    keyboardAvailable: Boolean,
    onSelect: (OrbShortcutAction) -> Unit,
    onBlockedKeyboard: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (taps == null) return
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0x751C1813))
            .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(palette.bg)
                .border(1.dp, palette.line2, RoundedCornerShape(24.dp))
                .clickable(interactionSource = noRipple, indication = null) { /* consome o clique */ }
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 22.dp)
                .testTag(SHORTCUT_SHEET_TAG),
        ) {
            Text(
                if (taps == 1) tr("1 toque") else tr("%d toques", taps),
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                letterSpacing = (-0.4).sp,
            )
            Text(
                if (taps == 1) {
                    tr("Escolha a ação ao tocar 1 vez na orbe (apenas no modo Push-to-talk, onde o toque é livre).")
                } else {
                    tr("Escolha a ação ao tocar %d vezes na orbe (em repouso ou pausada).", taps)
                },
                color = palette.muted,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 11.5.sp,
                lineHeight = 16.5.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )

            listOf(
                OrbShortcutAction.NONE,
                OrbShortcutAction.TOGGLE_KEYBOARD,
                OrbShortcutAction.SWITCH_IME,
                OrbShortcutAction.VISION_MODE,
            ).forEach { action ->
                val blocked = action == OrbShortcutAction.TOGGLE_KEYBOARD && !keyboardAvailable
                ActionRow(
                    action = action,
                    selected = action == current,
                    blocked = blocked,
                    onClick = {
                        if (blocked) {
                            onBlockedKeyboard()
                            onDismiss()
                        } else {
                            onSelect(action)
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    action: OrbShortcutAction,
    selected: Boolean,
    blocked: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val noRipple = remember { MutableInteractionSource() }
    // Selecionada: aro teal e o radio cheio — o bloco preto invertido pesava mais que a escolha.
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(raisedFill(palette)).border(1.dp, palette.teal, shape)
                else Modifier.border(1.dp, palette.line, shape),
            )
            .clickable(interactionSource = noRipple, indication = null) { onClick() }
            .testTag("shortcut-action-${action.name.lowercase()}")
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .then(if (blocked) Modifier.alpha(0.45f) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KfRadio(selected, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    shortcutActionLabel(action),
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (blocked) Eyebrow(tr("Indisponível"))
            }
            Text(
                shortcutActionSub(action),
                color = palette.muted,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
