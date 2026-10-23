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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val UNLOCK_SHEET_TAG = "unlock-sheet"
const val UNLOCK_CLOSE_TAG = "unlock-close"
const val UNLOCK_ONCE_TAG = "unlock-once"

/**
 * Bottom-sheet "Desbloquear skins" (SKIN-02/03), com a compra única de skins que entra na v1.
 * A opção de créditos não faz parte desta versão; fechar pelo scrim ou X.
 * O tema ativo (Ateliê claro/escuro) é herdado do [com.keyflow.ui.theme.KeyFlowTheme] em volta.
 */
@Composable
fun UnlockSheet(visible: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (!visible) return
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
                .padding(start = 22.dp, end = 18.dp, top = 20.dp, bottom = 22.dp)
                .testTag(UNLOCK_SHEET_TAG),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Desbloquear skins"),
                        color = palette.fg,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Normal,
                        fontSize = 18.sp,
                        letterSpacing = (-0.4).sp,
                    )
                    Text(
                        tr("Personalize o teclado e o overlay com temas. Libere as skins para sempre."),
                        color = palette.muted,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Light,
                        fontSize = 11.5.sp,
                        lineHeight = 16.5.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.dp, palette.line, CircleShape)
                        .clickable(interactionSource = noRipple, indication = null) { onDismiss() }
                        .testTag(UNLOCK_CLOSE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    KfIcon(KfIcons.Close, palette.fg2, Modifier.size(15.dp), strokeWidth = 1.8f)
                }
            }

            // Opção única — só skins. A fiação da compra entra na tarefa MZ-T9.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, palette.line, RoundedCornerShape(16.dp))
                    .clickable(interactionSource = noRipple, indication = null) { /* inerte */ }
                    .testTag(UNLOCK_ONCE_TAG)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
            ) {
                Text(tr("Pagar R$ 15 — uma vez"), color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
                Text(
                    tr("Pagamento único, sem virar crédito. Libera apenas as skins, para sempre."),
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Light,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }

            Eyebrow(
                tr("Pagamento via Google Play"),
                Modifier.fillMaxWidth().padding(top = 16.dp).wrapContentWidth(Alignment.CenterHorizontally),
            )
        }
    }
}

