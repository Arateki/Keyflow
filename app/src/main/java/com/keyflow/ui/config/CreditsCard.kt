package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.i18n.tr
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

/**
 * Cartão de saldo e recarga de créditos KeyFlow, unificado para todas as capacidades
 * (Transcrição, Raciocínio, Visão e Fala).
 */
@Composable
fun CreditsCard(
    modifier: Modifier = Modifier,
    tag: String = "credits-card",
    title: String = tr("Saldo de créditos"),
    balance: String = "128",
    totalBalance: String = "500",
    unit: String = tr("créditos"),
    usageFraction: Float = 0.26f,
    usedText: String = tr("≈ 42 min usados"),
    totalText: String = tr("165 min no total"),
    rechargeTag: String = "$tag-recharge",
    onRecharge: () -> Unit = {},
) {
    val palette = LocalKeyFlowPalette.current
    Column(modifier.padding(top = 20.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.bg)
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag(tag),
        ) {
            Eyebrow(title)
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        balance,
                        color = palette.fg,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Light,
                        fontSize = 26.sp,
                        letterSpacing = (-0.6).sp,
                    )
                    Text(
                        " / $totalBalance",
                        color = palette.faint,
                        fontFamily = SpaceGrotesk,
                        fontWeight = FontWeight.Light,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    unit,
                    color = palette.muted,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Light,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }

            // Barra de progresso de uso de créditos
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.line2),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(usageFraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.teal),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(usedText, color = palette.muted, fontFamily = SpaceMono, fontWeight = FontWeight.Light, fontSize = 9.5.sp)
                Text(totalText, color = palette.muted, fontFamily = SpaceMono, fontWeight = FontWeight.Light, fontSize = 9.5.sp)
            }
        }

        PrimaryButton(
            tr("Recarregar créditos"),
            tag = rechargeTag,
            onClick = onRecharge,
            modifier = Modifier.padding(top = 14.dp),
            icon = KfIcons.Plus,
        )
        SettingSub(
            tr("Pagamento via Google Play — créditos nunca expiram."),
            Modifier.padding(top = 8.dp),
        )
    }
}
