package com.keyflow.ui.tour.scenes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.tour.FingerAction
import com.keyflow.ui.tour.LocalTourClock
import com.keyflow.ui.tour.TourFrame
import com.keyflow.ui.tour.TourOverlayHint

/** Tag do orbe da cena, com o estado no nome — só para os instrumentados afirmarem a sincronia. */
fun tourOrbTag(state: OrbState): String = "tour-orb-${state.name}"

const val TOUR_HINT_CANCEL_TARGET_TAG = "tour-hint-cancel-target"
const val TOUR_HINT_UNDO_BANNER_TAG = "tour-hint-undo-banner"

/**
 * Altura reservada para QUALQUER cena do tour. Fixa de propósito: com a altura acompanhando o conteúdo,
 * o card saltava de tamanho a cada troca de gesto — e o teclado, que encolhe ao retrair, saltava dentro
 * do próprio roteiro (relatado pelo Yan usando no A32, 2026-09-09).
 */
val TOUR_SCENE_HEIGHT = 132.dp

/** Onde a orbe mora quando nenhum passo diz o contrário — mesmo ponto de partida do catálogo. */
private val DEFAULT_ORB_AT = Offset(0.5f, 0.45f)

/**
 * Cena da orbe (TOUR-03): o [VoiceOrb] **real** dirigido pelo passo do roteiro.
 *
 * Usar a superfície de verdade em vez de um mock desenhado à parte é o que impede o tour de envelhecer:
 * reskin, mudança de glifo ou de animação da orbe aparecem aqui de graça. Foi exatamente o problema das
 * demos estáticas que esta feature veio substituir — elas mostravam a grade pré-Canvas.
 *
 * O alvo de cancelamento e o banner de desfazer são **representações leves**: as versões reais vivem no
 * `OverlayService`, fora de qualquer Composable, e não dá para instanciá-las aqui.
 */
@Composable
fun OrbScene(frame: TourFrame, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val clock = LocalTourClock.current
    val state = frame.reaction.orbState ?: OrbState.Idle

    // Arrastar a orbe move a orbe. Quando o dedo solta, ela fica onde o passo declarou — no lugar de
    // origem se foi um cancelamento, no lugar novo se foi um reposicionamento.
    val orbAt = if (frame.action == FingerAction.DRAG) {
        frame.position
    } else {
        frame.reaction.orbAt ?: DEFAULT_ORB_AT
    }

    BoxWithConstraints(modifier.height(TOUR_SCENE_HEIGHT)) {
        val orbSide = maxWidth * 0.30f

        when (frame.reaction.overlayHint) {
            TourOverlayHint.CANCEL_TARGET -> CancelTarget(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxHeight * 0.04f)
                    .size(orbSide * 0.72f)
                    .testTag(TOUR_HINT_CANCEL_TARGET_TAG),
            )
            TourOverlayHint.UNDO_BANNER -> UndoBanner(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxHeight * 0.06f)
                    .testTag(TOUR_HINT_UNDO_BANNER_TAG),
            )
            else -> Unit
        }

        VoiceOrb(
            state = state,
            modifier = Modifier
                .offset(
                    x = maxWidth * orbAt.x - orbSide / 2,
                    y = maxHeight * orbAt.y - orbSide / 2,
                )
                .size(orbSide)
                .testTag(tourOrbTag(state)),
            // Mesmo relógio do tour: congelado, a orbe congela junto e o frame inteiro é determinístico.
            timeProvider = clock,
        )
    }
}

/** Alvo de descarte: anel tracejado terracota, a mesma cor que o overlay usa para cancelar. */
@Composable
private fun CancelTarget(modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = palette.terracotta.copy(alpha = 0.12f), radius = r, center = c)
        drawCircle(
            color = palette.terracotta.copy(alpha = 0.8f),
            radius = r * 0.86f,
            center = c,
            style = Stroke(
                width = r * 0.12f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.28f, r * 0.22f)),
            ),
        )
    }
}

/** Banner de desfazer: pílula com a ação, no lugar onde o overlay real a mostra. */
@Composable
private fun UndoBanner(modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Box(
        modifier
            .background(palette.card, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            "Cancelado · Desfazer",
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 9.sp,
        )
    }
}
