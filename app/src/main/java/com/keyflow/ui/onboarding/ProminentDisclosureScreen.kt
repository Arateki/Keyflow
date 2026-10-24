package com.keyflow.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.i18n.tr
import com.keyflow.ui.config.PaperWarm
import com.keyflow.ui.config.PrimaryButton
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

/** URL deliberadamente centralizada até a URL pública ser definida na V1-5. */
const val PRIVACY_POLICY_URL = "https://example.invalid/privacy" // TODO(V1-5): Yan definir URL pública.

const val DISCLOSURE_ACCEPT_TAG = "disclosure-accept"
const val DISCLOSURE_DECLINE_TAG = "disclosure-decline"
const val DISCLOSURE_CONTINUE_TAG = "disclosure-continue-without-dictation"
const val DISCLOSURE_DECLINED_EXPLANATION_TAG = "disclosure-declined-explanation"

@Composable
fun ProminentDisclosureScreen(
    declined: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val context = LocalContext.current
    val declineLabel = if (declined) tr("Continuar sem ditado") else tr("Recusar")
    Column(
        modifier.fillMaxSize().background(palette.bg).systemBarsPadding().padding(horizontal = 26.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(0.08f))
        Text(
            tr("Privacidade do ditado"), color = palette.fg, fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium, fontSize = 25.sp, lineHeight = 31.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            tr("O microfone só grava quando você toca nele. O áudio é transcrito no aparelho, sem sair dele, ou enviado ao provedor que você configurar. O texto transcrito vai para o campo em que você digita (ou para a área de transferência se não houver campo ativo); o KeyFlow não envia o que você digita. O app de configuração mostra anúncios do AdMob, sujeitos ao consentimento do Google UMP."),
            color = palette.muted, fontFamily = SpaceGrotesk, fontSize = 14.sp, lineHeight = 21.sp,
            textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth().testTag("disclosure-copy"),
        )
        Text(
            tr("Aceite esta divulgação para ativar o ditado por voz. Sem aceite, o microfone não inicia."),
            color = palette.recordSoft, fontFamily = SpaceGrotesk, fontSize = 13.sp, lineHeight = 19.sp,
            modifier = Modifier.fillMaxWidth().testTag("disclosure-recording-gate-explanation"),
        )
        if (declined) {
            Text(
                tr("Sem aceitar, o ditado por voz fica indisponível. Você pode aceitar depois pelo item Microfone nas configurações."),
                color = palette.recordSoft, fontFamily = SpaceGrotesk, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().testTag(DISCLOSURE_DECLINED_EXPLANATION_TAG),
            )
        }
        Text(
            tr("Política de privacidade"), color = palette.teal, fontFamily = SpaceGrotesk, fontSize = 14.sp,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
            }.testTag("disclosure-privacy-link"),
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton(tr("Aceitar e continuar"), tag = DISCLOSURE_ACCEPT_TAG, onClick = onAccept)
        Text(
            declineLabel, color = palette.muted,
            fontFamily = SpaceGrotesk, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDecline)
                .testTag(if (declined) DISCLOSURE_CONTINUE_TAG else DISCLOSURE_DECLINE_TAG).padding(vertical = 12.dp),
        )
    }
}
