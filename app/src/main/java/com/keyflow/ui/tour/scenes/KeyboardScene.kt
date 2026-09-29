package com.keyflow.ui.tour.scenes

import com.keyflow.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.keyflow.core.ButtonState
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.tour.TourFrame
import com.keyflow.ui.tour.TourOverlayHint

const val TOUR_HINT_MIC_SELECTOR_TAG = "tour-hint-mic-selector"

/**
 * Cena do teclado (TOUR-03): o [KeyFlowKeyboardView] **real** em miniatura, dirigido pelo passo.
 *
 * Mesma razão da cena da orbe: usar a superfície de verdade é o que impede o tour de envelhecer. A demo
 * antiga da aba Modo mostrava a grade PRÉ-Canvas e ficou mentindo por meses — é literalmente o defeito
 * que originou esta feature.
 *
 * O seletor de ações do mic entra como representação leve: o real é montado pelo IME em cima da janela
 * do teclado, fora do alcance de um Composable.
 */
@Composable
fun KeyboardScene(frame: TourFrame, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val dark = palette.isDark
    val expanded = frame.reaction.keyboardExpanded ?: true
    val micState = frame.reaction.micState ?: ButtonState.IDLE_READY

    // Altura reservada e teclado ancorado no TOPO. As coordenadas do roteiro são relativas à cena, e a
    // barra (mic, seta de retrair) fica no topo do teclado — ancorando ali, retrair encolhe o teclado
    // para baixo sem mover o que o dedo mira. Ancorado embaixo, retrair puxaria a barra junto e o dedo
    // erraria o alvo; com a altura acompanhando o conteúdo, o card ainda saltava de tamanho.
    Box(modifier.height(TOUR_SCENE_HEIGHT), contentAlignment = Alignment.TopCenter) {
        key(dark) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top),
                factory = { ctx ->
                    KeyFlowKeyboardView(
                        context = ctx,
                        onKey = {},
                        onMic = {},
                        onHide = {},
                        dark = dark,
                        // Tempo congelado: o mic mostra um cronômetro, e sem isto cada frame do tour
                        // sairia com um valor diferente — adeus golden.
                        frozenSeconds = DEMO_SECONDS,
                        scale = MINIATURE_SCALE,
                    )
                },
                update = { view ->
                    if (view.expandedForTest != expanded) view.setExpanded(expanded)
                    view.updateMicState(micState)
                },
            )
        }

        if (frame.reaction.overlayHint == TourOverlayHint.MIC_ACTION_SELECTOR) {
            BoxWithConstraints(Modifier.matchParentSize()) {
                // Ancorado à DIREITA do mic: encostado nele, o chip "pausar" ficaria em cima do próprio
                // botão e o arrasto do roteiro não teria para onde ir. A fração casa com
                // `KBD_MIC_PAUSE_CHIP` do catálogo — se um mudar, o outro muda junto.
                MicActionSelector(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = maxWidth * SELECTOR_START_FRACTION, top = 6.dp)
                        .testTag(TOUR_HINT_MIC_SELECTOR_TAG),
                )
            }
        }
    }
}

/** Chips de pausar/cancelar, como o seletor que aparece ao segurar o mic. */
@Composable
private fun MicActionSelector(modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Row(
        modifier
            .background(palette.card, RoundedCornerShape(50))
            .border(1.dp, palette.line2, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(KfIcons.Pause, tr("pausar"))
        Chip(KfIcons.Close, tr("cancelar"))
    }
}

@Composable
private fun Chip(icon: KfIconSpec, label: String) {
    val palette = LocalKeyFlowPalette.current
    Row(
        Modifier
            .background(palette.panel, RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        KfIcon(icon, palette.fg2, Modifier.size(9.dp), strokeWidth = 2.2f)
        Text(
            label,
            color = palette.fg2,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 8.sp,
        )
    }
}

/**
 * Onde a fileira de chips começa, em fração da largura da cena. O roteiro aponta o arrasto para o chip
 * "pausar" usando este mesmo referencial (`KBD_MIC_PAUSE_CHIP`).
 */
internal const val SELECTOR_START_FRACTION = 0.20f

/** Segundos fixos no cronômetro do mic — determinismo, não um valor com significado. */
private const val DEMO_SECONDS = 12

/** Miniatura: o teclado inteiro proporcionalmente menor, como a demo da aba Modo já fazia. */
private const val MINIATURE_SCALE = 0.62f
