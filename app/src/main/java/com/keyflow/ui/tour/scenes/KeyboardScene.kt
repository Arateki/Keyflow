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

/**
 * Cena do teclado (TOUR-03): o [KeyFlowKeyboardView] **real** em miniatura, dirigido pelo passo.
 *
 * Mesma razão da cena da orbe: usar a superfície de verdade é o que impede o tour de envelhecer. A demo
 * antiga da aba Modo mostrava a grade PRÉ-Canvas e ficou mentindo por meses — é literalmente o defeito
 * que originou esta feature.
 *
 * A orbe do teclado já é desenhada pela própria view; o gesto de segurar e arrastar é só o dedo do roteiro.
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
                        reservarFaixaDaOrbe = false,
                    )
                },
                update = { view ->
                    if (view.expandedForTest != expanded) view.setExpanded(expanded)
                    view.updateMicState(micState)
                },
            )
        }
    }
}

/** Segundos fixos no cronômetro do mic — determinismo, não um valor com significado. */
private const val DEMO_SECONDS = 12

/** Miniatura: o teclado inteiro proporcionalmente menor, como a demo da aba Modo já fazia. */
private const val MINIATURE_SCALE = 0.62f
