package com.keyflow.ui.config

import androidx.compose.foundation.background
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.RecordingMode
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val DOCS_PAUSE_TAG = "docs-pausa"
const val DOCS_SHORTCUTS_TAG = "docs-atalhos"
const val DOCS_CANCEL_TAG = "docs-cancelar"
const val DOCS_NOTIFICATION_TAG = "docs-notificacao"

/**
 * Aba Docs (MON-01 / REC-07): "Como funciona" (3 passos), e seções Modos, Privacidade, Créditos e
 * Pausa (explica a pausa nos dois modos de gravação). Conteúdo estático — verificado por screenshot.
 */
@Composable
fun DocsPanel(modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 6.dp, bottom = 24.dp),
    ) {
        Text("Como funciona".uppercase(), color = palette.faint, fontFamily = SpaceMono, fontSize = 8.5.sp)
        Column(Modifier.padding(top = 11.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Step("01", "Fale", "Toque em Gravar no teclado, ou no orbe flutuante.")
            Step("02", "Transcreve", "O áudio vai à origem de voz escolhida e volta como texto.")
            Step("03", "Cola", "O texto entra direto no campo ativo do app.")
        }

        Section(
            "Modos",
            "Sobrepor flutua sobre qualquer app, com o teclado do sistema. Teclado é o teclado KeyFlow. Use um ou ambos.",
        )
        Section(
            "Onde o texto aparece",
            "No modo Sobrepor com o teclado do sistema, o texto transcrito vai para a área de transferência e você é avisado — é só colar. A cola automática só acontece com o teclado KeyFlow ativo (configurável na aba Teclado).",
        )
        Section(
            "Atalhos por toques",
            "Tocar a orbe em rajada aciona atalhos que você escolhe na aba Sobrepor. De fábrica, 2 toques retraem ou expandem o teclado e 3 trocam o teclado do sistema. O atalho de 1 toque só existe no modo Push-to-talk — no modo Alternar o toque já é a própria gravação. Toques além do máximo caem no atalho ativo de menos toques mais próximo, ou não fazem nada, conforme sua escolha na mesma aba.",
            tag = DOCS_SHORTCUTS_TAG,
        )
        Section(
            "Cancelar e desfazer",
            "Arraste a orbe até o alvo para cancelar a gravação. O áudio não é descartado na hora: fica guardado por 3 segundos, com um banner de Desfazer na tela — desfazer devolve a gravação pausada, de onde parou. Sem ação nesses 3 segundos, o áudio é descartado. Pela notificação, o mesmo cancelamento tem os botões de concluir agora ou desfazer.",
            tag = DOCS_CANCEL_TAG,
        )
        Section(
            "Notificação de gravação",
            "Enquanto o Modo Sobrepor grava, a notificação traz três controles: pausar e retomar, enviar (encerra e entrega o texto) e cancelar. Durante os 3 segundos do cancelamento, eles viram concluir o descarte e desfazer.",
            tag = DOCS_NOTIFICATION_TAG,
        )
        Section(
            "Privacidade",
            "O áudio só é enviado à origem configurada em Voz. Tokens ficam apenas no aparelho.",
        )
        Section(
            "Créditos",
            "Sem provedor próprio? Use Créditos e pague por minuto via Google Play.",
        )
        Section(
            "Pausa",
            null,
            tag = DOCS_PAUSE_TAG,
        ) {
            PauseLine("Alternar", pauseHintFor(RecordingMode.TOGGLE))
            PauseLine("Push-to-talk", pauseHintFor(RecordingMode.PUSH_TO_TALK))
        }
    }
}

@Composable
private fun Step(number: String, title: String, body: String) {
    val palette = LocalKeyFlowPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(number, color = palette.teal, fontFamily = SpaceMono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Column {
            Text(title, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(body, color = palette.muted, fontFamily = SpaceGrotesk, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun Section(
    title: String,
    body: String?,
    tag: String? = null,
    content: @Composable (() -> Unit)? = null,
) {
    val palette = LocalKeyFlowPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 13.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.line2))
        Text(
            title,
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 13.dp),
        )
        if (body != null) {
            Text(body, color = palette.muted, fontFamily = SpaceGrotesk, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
        content?.invoke()
    }
}

@Composable
private fun PauseLine(mode: String, hint: String) {
    val palette = LocalKeyFlowPalette.current
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$mode —", color = palette.fg2, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
        Text(hint, color = palette.muted, fontFamily = SpaceGrotesk, fontSize = 11.5.sp)
    }
}
