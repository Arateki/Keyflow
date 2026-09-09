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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.keyflow.ui.theme.LocalKeyFlowPalette

/**
 * Clock global opcional do tour, no mesmo padrão do `LocalOrbClock` (TOUR-05): a galeria fornece um
 * valor constante e congela **qualquer** tour da árvore, tornando os frames determinísticos sem
 * enfiar um `timeProvider` por todas as telas. `null` = tempo real.
 */
val LocalTourClock = staticCompositionLocalOf<(() -> Float)?> { null }

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
 * O dedo do tour (TOUR-02): um vetor desenhado, nunca um asset — reskin do app não deixa o tour para
 * trás, e claro/escuro saem de graça da paleta.
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
    val accent = palette.teal

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

        // Ponta do dedo: TRANSLÚCIDA de propósito. Opaca, ela cobria a orbe inteira durante os toques
        // e o tour escondia justamente o que veio ensinar (visto na tira de frames de 2026-09-09).
        // O contorno é que dá a leitura da posição; o miolo só insinua o contato, como o "mostrar
        // toques" do Android.
        drawCircle(color = ink.copy(alpha = 0.10f), radius = tip * 1.85f, center = center)
        drawCircle(color = ink.copy(alpha = 0.30f), radius = tip, center = center)
        drawCircle(
            color = ink.copy(alpha = 0.72f),
            radius = tip,
            center = center,
            style = Stroke(width = tip * 0.16f),
        )
    }
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
