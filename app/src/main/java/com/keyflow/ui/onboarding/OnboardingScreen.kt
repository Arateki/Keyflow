package com.keyflow.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.config.PrimaryButton
import com.keyflow.ui.config.PaperWarm
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val ONBOARDING_GRANT_TAG = "onboarding-grant"
const val ONBOARDING_SKIP_TAG = "onboarding-skip"
const val ONBOARDING_ITEM_MIC_TAG = "onboarding-item-mic"
const val ONBOARDING_ITEM_OVERLAY_TAG = "onboarding-item-overlay"
const val ONBOARDING_ITEM_KEYBOARD_TAG = "onboarding-item-keyboard"
const val ONBOARDING_ITEM_NOTIFICATIONS_TAG = "onboarding-item-notifications"

/**
 * As 4 permissões do checklist, com o porquê de cada uma (PERM-03). Os textos seguem a Privacidade da
 * Docs e o `PLAYSTORE_CHECKLIST.md`: o áudio só sai quando a pessoa grava e só para a origem dela;
 * nenhum texto digitado é transmitido.
 */
enum class OnboardingPermission(val key: String, val label: String, val why: String) {
    MIC(
        "mic",
        "Microfone",
        "Para gravar sua voz e transcrever. O microfone só é usado enquanto você grava, e o áudio vai apenas para a origem de voz configurada na aba Voz. Sem ele, não há ditado.",
    ),
    OVERLAY(
        "overlay",
        "Sobreposição (overlay)",
        "Para desenhar a orbe flutuante por cima dos outros apps. Sem ela, não existe o modo Sobrepor — só o teclado KeyFlow.",
    ),
    KEYBOARD(
        "keyboard",
        "Teclado KeyFlow",
        "Para o texto transcrito entrar direto no campo em que você está digitando. Sem ele, o texto vai para a área de transferência e você cola. Nenhum texto digitado é transmitido.",
    ),
    NOTIFICATIONS(
        "notifications",
        "Notificações",
        "Para mostrar os controles da gravação — pausar, enviar, cancelar — com o app em segundo plano. Sem elas a gravação funciona, mas sem esses controles fora do app.",
    ),
}

/** Tag do "?" da permissão (PERM-01). */
fun onboardingWhyTag(permission: OnboardingPermission): String = "onboarding-why-${permission.key}"

/** Tag da explicação expandida sob a linha (PERM-02). */
fun onboardingWhyTextTag(permission: OnboardingPermission): String = "onboarding-why-${permission.key}-text"

/**
 * Tela de boas-vindas (ONB-01..03): orbe `Listening`, headline e um checklist que reflete o
 * [PermissionStatus]. Cada item do checklist é clicável e pede/abre só AQUELA permissão — dá pra
 * conceder uma a uma, voltando pra esta tela entre uma e outra (Yan, 2026-07-05). "Conceder
 * permissões" ainda pede tudo que falta de uma vez; "Pular por agora" segue para a config. A
 * persistência de `onboardingDone` e a decisão de rota inicial são da T27.
 *
 * O "?" de cada linha (PERM-01..04) abre o porquê daquela permissão logo abaixo dela; no máximo uma
 * explicação aberta por vez. [initiallyExplained] existe para a rota de galeria do estado expandido.
 */
@Composable
fun OnboardingScreen(
    permissions: PermissionStatus,
    onGrant: () -> Unit,
    onSkip: () -> Unit,
    onRequestMic: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestKeyboard: () -> Unit,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExplained: OnboardingPermission? = null,
) {
    val palette = LocalKeyFlowPalette.current
    // Salva só a chave: sobrevive à ida às telas do sistema (conceder) e volta com a mesma explicação.
    var explainedKey by rememberSaveable { mutableStateOf(initiallyExplained?.key) }
    val toggle: (OnboardingPermission) -> Unit = { p -> explainedKey = if (explainedKey == p.key) null else p.key }
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
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 8.5.sp,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
        )
        Text(
            "Sua voz vira texto em qualquer campo.",
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Normal,
            fontSize = 26.sp,
            lineHeight = 31.sp,
            letterSpacing = (-0.8).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Text(
            "Fale, e o KeyFlow cola a transcrição direto no app que você estiver usando. Para isso, ele precisa de algumas permissões.",
            color = palette.muted,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )
        Text(
            "Recomendamos conceder todas agora, para conhecer tudo que o app oferece — depois você pode rever cada uma para refletir o seu uso.",
            color = palette.faint,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Column(Modifier.fillMaxWidth().padding(top = 28.dp)) {
            ChecklistRow(
                OnboardingPermission.MIC,
                permissions.microphoneGranted,
                explained = explainedKey == OnboardingPermission.MIC.key,
                onClick = onRequestMic,
                onToggleWhy = toggle,
                tag = ONBOARDING_ITEM_MIC_TAG,
            )
            ChecklistRow(
                OnboardingPermission.OVERLAY,
                permissions.overlayGranted,
                explained = explainedKey == OnboardingPermission.OVERLAY.key,
                onClick = onRequestOverlay,
                onToggleWhy = toggle,
                tag = ONBOARDING_ITEM_OVERLAY_TAG,
            )
            ChecklistRow(
                OnboardingPermission.KEYBOARD,
                permissions.keyboardEnabled,
                explained = explainedKey == OnboardingPermission.KEYBOARD.key,
                onClick = onRequestKeyboard,
                onToggleWhy = toggle,
                tag = ONBOARDING_ITEM_KEYBOARD_TAG,
            )
            ChecklistRow(
                OnboardingPermission.NOTIFICATIONS,
                permissions.notificationsGranted,
                explained = explainedKey == OnboardingPermission.NOTIFICATIONS.key,
                onClick = onRequestNotifications,
                onToggleWhy = toggle,
                tag = ONBOARDING_ITEM_NOTIFICATIONS_TAG,
                last = true,
            )
        }

        Spacer(Modifier.weight(1f))

        PrimaryButton("Conceder permissões", tag = ONBOARDING_GRANT_TAG, onClick = onGrant)
        Text(
            "Pular por agora",
            color = palette.muted,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
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
private fun ChecklistRow(
    permission: OnboardingPermission,
    granted: Boolean,
    explained: Boolean,
    onClick: () -> Unit,
    onToggleWhy: (OnboardingPermission) -> Unit,
    tag: String,
    last: Boolean = false,
) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (granted) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(palette.teal), contentAlignment = Alignment.Center) {
                KfIcon(KfIcons.Check, PaperWarm, Modifier.size(12.dp), strokeWidth = 2.6f)
            }
        } else {
            Box(Modifier.size(20.dp).clip(CircleShape).border(1.dp, palette.record, CircleShape))
        }
        Text(permission.label, color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            if (granted) "OK" else "FALTA",
            color = if (granted) palette.faint else palette.recordSoft,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 8.sp,
            letterSpacing = 1.4.sp,
        )
        WhyButton(permission, explained, onClick = { onToggleWhy(permission) })
    }
    AnimatedVisibility(visible = explained) {
        Text(
            permission.why,
            color = palette.muted,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Light,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 34.dp, bottom = 13.dp)
                .testTag(onboardingWhyTextTag(permission)),
        )
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
}

/**
 * "?" da linha: alvo de toque próprio (o resto da linha pede a permissão). TalkBack anuncia "por que
 * pedimos <permissão>" e se a explicação está aberta.
 */
@Composable
private fun WhyButton(permission: OnboardingPermission, explained: Boolean, onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val accent = if (explained) palette.fg else palette.faint
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Por que pedimos ${permission.label}"
                stateDescription = if (explained) "explicação aberta" else "explicação fechada"
            }
            .testTag(onboardingWhyTag(permission)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).border(1.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("?", color = accent, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 9.5.sp)
        }
    }
}
