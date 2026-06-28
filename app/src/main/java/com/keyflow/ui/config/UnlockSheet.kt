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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val UNLOCK_SHEET_TAG = "unlock-sheet"
const val UNLOCK_CLOSE_TAG = "unlock-close"
const val UNLOCK_RECHARGE_TAG = "unlock-recharge"
const val UNLOCK_ONCE_TAG = "unlock-once"

/**
 * Bottom-sheet "Desbloquear skins" (SKIN-02/03), **inerte**: duas opções (recarga R$20 que vira saldo +
 * skins; R$15 só skins) e "Pagamento via Google Play". Nada compra nesta fase; fechar pelo scrim ou X.
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
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(palette.bg)
                .clickable(interactionSource = noRipple, indication = null) { /* consome o clique */ }
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 26.dp)
                .testTag(UNLOCK_SHEET_TAG),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Desbloquear skins", color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(
                        "Personalize o teclado e o overlay com temas. Escolha como liberar — para sempre.",
                        color = palette.muted,
                        fontFamily = SpaceGrotesk,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Box(
                    Modifier
                        .size(26.dp)
                        .clickable(interactionSource = noRipple, indication = null) { onDismiss() }
                        .testTag(UNLOCK_CLOSE_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    StrokeIcon(listOf(CLOSE_PATH), palette.faint, Modifier.size(16.dp), strokeWidth = 1.8f)
                }
            }

            // Opção recomendada — recarga que vira saldo + skins (inerte).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(palette.fg)
                    .clickable(interactionSource = noRipple, indication = null) { /* inerte */ }
                    .testTag(UNLOCK_RECHARGE_TAG)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Recarregar R$ 20 em créditos", color = palette.onFill, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "Vira saldo de transcrição e libera todas as skins para sempre.",
                        color = palette.onFill.copy(alpha = 0.7f),
                        fontFamily = SpaceGrotesk,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Text(
                    "MELHOR",
                    color = Color.White,
                    fontFamily = SpaceMono,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.teal)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // Opção única — só skins (inerte).
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, palette.line, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = noRipple, indication = null) { /* inerte */ }
                    .testTag(UNLOCK_ONCE_TAG)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Text("Pagar R$ 15 — uma vez", color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Pagamento único, sem virar crédito. Libera apenas as skins, para sempre.",
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }

            Text(
                "Pagamento via Google Play".uppercase(),
                color = palette.faint,
                fontFamily = SpaceMono,
                fontSize = 8.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
        }
    }
}

private const val CLOSE_PATH = "M18 6 6 18M6 6l12 12"
