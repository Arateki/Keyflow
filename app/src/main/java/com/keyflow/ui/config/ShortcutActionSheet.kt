package com.keyflow.ui.config

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
import com.keyflow.ui.theme.SpaceMono

const val SHORTCUT_SHEET_TAG = "shortcut-action-sheet"

/** Rótulo da ação de atalho (lista da aba Sobrepor e bottom sheet). */
fun shortcutActionLabel(action: OrbShortcutAction): String = when (action) {
    OrbShortcutAction.NONE -> "Nenhuma"
    OrbShortcutAction.TOGGLE_KEYBOARD -> "Retrair / expandir teclado"
    OrbShortcutAction.SWITCH_IME -> "Trocar teclado do sistema"
}

/** Explicação da ação de atalho — reusada pelo tour de gestos (`ui/tour`) como descrição do roteiro. */
fun shortcutActionSub(action: OrbShortcutAction): String = when (action) {
    OrbShortcutAction.NONE -> "Sem ação para este gesto."
    OrbShortcutAction.TOGGLE_KEYBOARD -> "Encolhe ou expande o teclado KeyFlow na tela."
    OrbShortcutAction.SWITCH_IME -> "Abre o seletor de teclados do sistema."
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
                .clip(RoundedCornerShape(20.dp))
                .background(palette.bg)
                .clickable(interactionSource = noRipple, indication = null) { /* consome o clique */ }
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 22.dp)
                .testTag(SHORTCUT_SHEET_TAG),
        ) {
            Text(
                if (taps == 1) "1 toque" else "$taps toques",
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Text(
                if (taps == 1) {
                    "Escolha a ação ao tocar 1 vez na orbe (apenas no modo Push-to-talk, onde o toque é livre)."
                } else {
                    "Escolha a ação ao tocar $taps vezes na orbe (em repouso ou pausada)."
                },
                color = palette.muted,
                fontFamily = SpaceGrotesk,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            listOf(
                OrbShortcutAction.NONE,
                OrbShortcutAction.TOGGLE_KEYBOARD,
                OrbShortcutAction.SWITCH_IME,
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(4.dp))
            .then(
                if (selected) Modifier.background(palette.fg)
                else Modifier.border(1.dp, palette.line, RoundedCornerShape(4.dp)),
            )
            .clickable(interactionSource = noRipple, indication = null) { onClick() }
            .testTag("shortcut-action-${action.name.lowercase()}")
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .then(if (blocked) Modifier.alpha(0.45f) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                shortcutActionLabel(action),
                color = if (selected) palette.onFill else palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
            if (blocked) {
                Text(
                    "INDISPONÍVEL",
                    color = palette.faint,
                    fontFamily = SpaceMono,
                    fontSize = 8.sp,
                )
            }
        }
        Text(
            shortcutActionSub(action),
            color = if (selected) palette.onFill.copy(alpha = 0.7f) else palette.muted,
            fontFamily = SpaceGrotesk,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
