package com.keyflow.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val ONBOARDING_GRANT_TAG = "onboarding-grant"
const val ONBOARDING_SKIP_TAG = "onboarding-skip"

/**
 * Tela de boas-vindas (ONB-01..03): orbe `Listening`, headline e um checklist que reflete o
 * [PermissionStatus]. "Conceder permissões" encaminha (host pede as permissões); "Pular por agora"
 * segue para a config. A persistência de `onboardingDone` e a decisão de rota inicial são da T27.
 */
@Composable
fun OnboardingScreen(
    permissions: PermissionStatus,
    onGrant: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    Column(
        modifier
            .fillMaxSize()
            .background(palette.bg)
            .systemBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        VoiceOrb(state = OrbState.Listening, modifier = Modifier.size(98.dp).align(Alignment.CenterHorizontally))

        Text(
            "Bem-vindo ao KeyFlow".uppercase(),
            color = palette.faint,
            fontFamily = SpaceMono,
            fontSize = 9.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        )
        Text(
            "Sua voz vira texto em qualquer campo.",
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 30.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Text(
            "Fale, e o KeyFlow cola a transcrição direto no app que você estiver usando. Para isso, ele precisa de algumas permissões.",
            color = palette.muted,
            fontFamily = SpaceGrotesk,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
            ChecklistRow("Microfone", permissions.microphoneGranted)
            ChecklistRow("Sobreposição (overlay)", permissions.overlayGranted)
            ChecklistRow("Teclado KeyFlow", permissions.keyboardEnabled)
            ChecklistRow("Notificações", permissions.notificationsGranted, last = true)
        }

        Spacer(Modifier.weight(1f))

        Text(
            "Conceder permissões",
            color = palette.onFill,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(palette.fg)
                .clickable { onGrant() }
                .testTag(ONBOARDING_GRANT_TAG)
                .padding(vertical = 14.dp),
        )
        Text(
            "Pular por agora",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSkip() }
                .testTag(ONBOARDING_SKIP_TAG)
                .padding(top = 12.dp, bottom = 18.dp),
        )
    }
}

@Composable
private fun ChecklistRow(label: String, granted: Boolean, last: Boolean = false) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (granted) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(palette.teal), contentAlignment = Alignment.Center) {
                Text("✓", color = palette.onFill, fontFamily = SpaceGrotesk, fontSize = 11.sp)
            }
        } else {
            Box(Modifier.size(18.dp).clip(CircleShape).border(1.5.dp, palette.record, CircleShape))
        }
        Text(label, color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Text(
            if (granted) "OK" else "FALTA",
            color = if (granted) palette.faint else palette.recordSoft,
            fontFamily = SpaceMono,
            fontSize = 9.sp,
        )
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
}
