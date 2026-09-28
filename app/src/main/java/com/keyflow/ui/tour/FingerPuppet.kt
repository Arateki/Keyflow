package com.keyflow.ui.tour

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette

/**
 * Clock global opcional do tour, no mesmo padrão do `LocalOrbClock` (TOUR-05): a galeria fornece um
 * valor constante e congela **qualquer** tour da árvore, tornando os frames determinísticos sem
 * enfiar um `timeProvider` por todas as telas. `null` = tempo real.
 */
val LocalTourClock = staticCompositionLocalOf<(() -> Float)?> { null }

/** Período do pulso da seta de avançar. Calmo de propósito: é um convite, não um alerta. */
const val TOUR_ARROW_PULSE_PERIOD_MS = 1800

/** Piso do pulso. Não desce mais que isto para a seta nunca parecer desabilitada. */
const val TOUR_ARROW_PULSE_FLOOR = 0.55f

/**
 * Intensidade do pulso da seta de avançar (0,45..1) no instante [timeMs] — função pura, e não uma
 * animação própria: uma `rememberInfiniteTransition` deixaria a tela eternamente não-ociosa (quebrando
 * todo teste Compose que a renderiza) e tornaria o golden não-determinístico. Vindo do relógio do tour,
 * congela junto com ele.
 *
 * Existe porque o tour deixou de avançar sozinho (2026-09-09): a seta virou o único caminho adiante e
 * precisa se anunciar.
 */
fun tourArrowPulse(timeMs: Float): Float {
    val phase = (timeMs % TOUR_ARROW_PULSE_PERIOD_MS) / TOUR_ARROW_PULSE_PERIOD_MS
    // Meio cosseno: sobe e desce suave, sem canto no reinício do ciclo.
    val wave = (1f - kotlin.math.cos(phase * 2f * Math.PI.toFloat())) / 2f
    return TOUR_ARROW_PULSE_FLOOR + (1f - TOUR_ARROW_PULSE_FLOOR) * wave
}

/** Tempo corrente do tour: o injetado (galeria/teste) ou o relógio real. */
@Composable
fun rememberTourTimeMs(): Float {
    val provided = LocalTourClock.current
    val animated = rememberTourClock(enabled = provided == null)
    return provided?.invoke() ?: animated
}

/** Milissegundos desde a primeira composição, avançando por quadro. Desligado, fica parado em 0. */
@Composable
fun rememberTourClock(enabled: Boolean): Float {
    var timeMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> timeMs = (now - start) / 1_000_000f }
        }
    }
    return timeMs
}

/**
 * O dedo do tour (TOUR-02): uma mão apontando, desenhada em vetor ([KfIcons.Hand]) — nunca um asset,
 * para reskin do app não deixar o tour para trás, e claro/escuro saem de graça da paleta. Antes era uma
 * bolinha abstrata; a mão lê na hora como "toque aqui" (pedido do Yan, 2026-09-28).
 *
 * A PONTA do indicador fica exatamente na posição do frame: ondas de toque, anel de segurar e trilha
 * saem dali, e a mão pende para baixo e para a direita, como a de quem segura o celular. A silhueta é
 * de papel quase opaco com contorno de tinta — a mão fica por cima da cena, mas o ponto tocado (o que
 * o tour veio ensinar) segue à vista em volta da ponta.
 *
 * Não anima por conta própria: recebe o [frame] já interpolado por [tourFrameAt] e só desenha. É o que
 * mantém a animação inteira determinística sob clock congelado.
 *
 * As coordenadas do frame são relativas (0..1), então este Composable deve ocupar exatamente a mesma
 * área da cena que ele acompanha.
 */
@Composable
fun FingerPuppet(frame: TourFrame, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val ink = palette.fg
    val paper = palette.card
    val accent = palette.teal
    val hand = remember { KfIcons.Hand.strokes.map { PathParser().parsePathString(it).toPath() } }

    Canvas(modifier) {
        if (frame.stepIndex < 0) return@Canvas

        val center = Offset(frame.position.x * size.width, frame.position.y * size.height)
        val tip = size.minDimension * 0.05f

        drawTrail(frame, accent, tip)

        when (frame.action) {
            FingerAction.TAP, FingerAction.MULTI_TAP ->
                drawRipples(center, tip, accent, frame.actionProgress, frame.tapCount)
            FingerAction.PRESS ->
                drawHoldRing(center, tip, accent, frame.actionProgress)
            FingerAction.RELEASE ->
                // Soltar é o inverso de segurar: o halo se abre e some, em vez de aparecer do nada.
                drawReleaseHalo(center, tip, accent, frame.actionProgress)
            else -> Unit
        }

        // A mão "afunda" um pouco enquanto encosta na tela (segurar, ou o instante de cada toque).
        val pressed = when (frame.action) {
            FingerAction.PRESS -> 1f
            FingerAction.TAP, FingerAction.MULTI_TAP -> 1f - tapPhase(frame.actionProgress, frame.tapCount)
            else -> 0f
        }
        val s = size.minDimension * HAND_SIZE_RATIO / 24f * (1f - HAND_PRESS_SHRINK * pressed)
        translate(center.x - KfIcons.HAND_TIP_X * s, center.y - KfIcons.HAND_TIP_Y * s) {
            scale(s, s, pivot = Offset.Zero) {
                val outline = Stroke(width = 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(hand.first(), paper.copy(alpha = 0.94f))
                drawPath(hand.first(), ink.copy(alpha = 0.85f), style = outline)
                hand.drop(1).forEach { drawPath(it, ink.copy(alpha = 0.55f), style = outline) }
            }
        }
    }
}

/** Lado do grid 24 da mão, em fração do menor lado da cena (a mão em si ocupa ~20 dos 24). */
private const val HAND_SIZE_RATIO = 0.46f

/** Quanto a mão encolhe no toque — sugere o "afundar" do dedo sem mudar de lugar a ponta. */
private const val HAND_PRESS_SHRINK = 0.07f

/** Fase (0..1) do pulso corrente de uma rajada de [tapCount] toques — mesma conta de [drawRipples]. */
private fun tapPhase(progress: Float, tapCount: Int): Float {
    val pulses = tapCount.coerceAtLeast(1)
    val phase = (progress * pulses).coerceIn(0f, pulses.toFloat())
    return (phase - phase.toInt().coerceAtMost(pulses - 1)).coerceIn(0f, 1f)
}

/** Trilha esmaecida do arrasto: mais apagada no começo do caminho, viva perto do dedo. */
private fun DrawScope.drawTrail(frame: TourFrame, accent: Color, tip: Float) {
    if (frame.trail.size < 2) return
    val points = frame.trail.map { Offset(it.x * size.width, it.y * size.height) }
    points.zipWithNext().forEachIndexed { i, (a, b) ->
        val nearness = (i + 1).toFloat() / (points.size - 1)
        drawLine(
            color = accent.copy(alpha = 0.10f + 0.35f * nearness),
            start = a,
            end = b,
            strokeWidth = tip * (0.45f + 0.35f * nearness),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Ripple do toque. Numa rajada, [tapCount] pulsos cabem dentro do passo — é assim que o tour mostra a
 * diferença entre 2 e 3 toques sem escrever o número na tela.
 */
private fun DrawScope.drawRipples(center: Offset, tip: Float, accent: Color, progress: Float, tapCount: Int) {
    val pulses = tapCount.coerceAtLeast(1)
    val phase = (progress * pulses).coerceIn(0f, pulses.toFloat())
    val current = phase - phase.toInt().coerceAtMost(pulses - 1)
    val grow = current.coerceIn(0f, 1f)
    drawCircle(
        color = accent.copy(alpha = 0.45f * (1f - grow)),
        radius = tip * (1.1f + 1.9f * grow),
        center = center,
        style = Stroke(width = tip * 0.28f * (1f - grow * 0.5f)),
    )
}

/** Segurar: anel que fecha em volta do dedo enquanto o toque dura. */
private fun DrawScope.drawHoldRing(center: Offset, tip: Float, accent: Color, progress: Float) {
    drawCircle(color = accent.copy(alpha = 0.18f), radius = tip * 2.3f, center = center)
    drawCircle(
        color = accent.copy(alpha = 0.75f),
        radius = tip * (2.3f - 1.0f * progress.coerceIn(0f, 1f)),
        center = center,
        style = Stroke(width = tip * 0.24f),
    )
}

private fun DrawScope.drawReleaseHalo(center: Offset, tip: Float, accent: Color, progress: Float) {
    val open = progress.coerceIn(0f, 1f)
    drawCircle(
        color = accent.copy(alpha = 0.5f * (1f - open)),
        radius = tip * (1.3f + 1.4f * open),
        center = center,
        style = Stroke(width = tip * 0.22f),
    )
}
