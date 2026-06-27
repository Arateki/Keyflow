package com.keyflow.ui.orb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.Text
import com.keyflow.ui.theme.SpaceMono
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Orbe reativo à voz do KeyFlow (direção Ateliê). Porta do algoritmo em `reference/orb-algorithm.js`
 * (paleta A), evoluído com pausa, clock injetável e conteúdo central (ORB-01..04, REC-05).
 *
 * - [OrbState.Paused] reusa o desenho de [OrbState.Listening] com o glifo de pause no centro.
 * - [timeProvider] permite frames determinísticos (mesmo `t` → mesmo pixel) na galeria de verificação.
 * - [center] desenha o tempo (`m:ss`, Space Mono 700) ou o glifo de pause por cima do Canvas.
 */
enum class OrbState { Idle, Listening, Paused, Transcribing }

/**
 * Clock global opcional para o orbe. A [com.keyflow.ui.gallery.GalleryActivity] provê um valor
 * constante para congelar **qualquer** orbe da árvore (header de config, onboarding, …) e tornar os
 * screenshots determinísticos sem threadar o `timeProvider` por todas as telas. `null` = tempo real.
 */
val LocalOrbClock = staticCompositionLocalOf<(() -> Float)?> { null }

/** Conteúdo desenhado no centro do orbe (ORB-04). */
sealed interface OrbCenter {
    data object None : OrbCenter
    data class Time(val seconds: Int) : OrbCenter
    data object PauseGlyph : OrbCenter
}

private const val TAU = (PI * 2).toFloat()

// Paleta Ateliê (paleta A do orbe)
private val GlowInner = Color(0x667EB8A8)
private val GlowOuter = Color(0x007EB8A8)
private val MainInner = Color(0xFF9AD0BF)
private val MainOuter = Color(0xFF4E9683)
private val CoreInner = Color(0xFFF4ECCF)
private val CoreOuter = Color(0xFFBFE0D4)
private val RingColor = Color(0xFF4E9683)
private val CenterInk = Color(0xFF2B2720)

@Composable
fun VoiceOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    center: OrbCenter = OrbCenter.None,
    timeProvider: (() -> Float)? = null,
) {
    val provided = timeProvider ?: LocalOrbClock.current
    val animatedMs = rememberOrbClock(enabled = provided == null)
    val timeMs = provided?.invoke() ?: animatedMs

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawOrb(timeMs, state) }
        when (center) {
            is OrbCenter.Time -> Text(
                text = formatOrbTime(center.seconds),
                color = CenterInk,
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
            )
            OrbCenter.PauseGlyph -> PauseGlyph()
            OrbCenter.None -> Unit
        }
    }
}

/** Relógio do orbe em ms. Anima via `withFrameNanos` só quando [enabled] (modo tempo real). */
@Composable
private fun rememberOrbClock(enabled: Boolean): Float {
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

/** Glifo de pause (duas barras) desenhado no centro do orbe (ORB-02/REC-05). */
@Composable
private fun PauseGlyph() {
    Canvas(Modifier.size(18.dp)) {
        val barW = size.width * 0.30f
        val barH = size.height
        val gap = size.width * 0.16f
        drawRect(CenterInk, topLeft = Offset(size.width / 2f - gap / 2f - barW, 0f), size = Size(barW, barH))
        drawRect(CenterInk, topLeft = Offset(size.width / 2f + gap / 2f, 0f), size = Size(barW, barH))
    }
}

private fun DrawScope.drawOrb(t: Float, state: OrbState) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f

    val (amp, sp) = orbBaseParams(state)
    val lvl = orbLevel(state, t)
    val r = min(w, h) * 0.30f * (0.84f + 0.20f * lvl)

    // anéis expandindo — listening e paused (visual de escuta)
    if (state == OrbState.Listening || state == OrbState.Paused) {
        for (k in 0 until 3) {
            val fr = ((t * 0.0006f) + (k / 3f)) % 1f
            drawCircle(
                color = RingColor,
                radius = r * (1.05f + fr * 0.95f),
                center = Offset(cx, cy),
                alpha = 0.18f * (1f - fr),
                style = Stroke(width = 1.3f),
            )
        }
    }

    drawLayer(cx, cy, r * 1.12f, amp * 0.7f, t, sp * 0.8f, 5.0f, GlowInner, GlowOuter, 1f)
    drawLayer(cx, cy, r, amp * lvl, t, sp, 2.0f, MainInner, MainOuter, 0.96f)
    drawLayer(cx, cy, r * 0.66f, amp * 0.7f, t, sp * 1.35f, 9.0f, CoreInner, CoreOuter, 0.95f)

    drawCircle(
        color = Color.White,
        radius = r * 0.16f,
        center = Offset(cx - r * 0.28f, cy - r * 0.32f),
        alpha = 0.30f,
    )
}

private fun DrawScope.drawLayer(
    cx: Float, cy: Float, r: Float, amp: Float, t: Float, sp: Float, seed: Float,
    c0: Color, c1: Color, alpha: Float,
) {
    val path = blobPath(cx, cy, r, amp, t, sp, seed)
    val brush = Brush.radialGradient(
        colors = listOf(c0, c1),
        center = Offset(cx - r * 0.25f, cy - r * 0.30f),
        radius = r * 1.25f,
    )
    drawPath(path = path, brush = brush, alpha = alpha)
}

/** Caminho do blob: 110 pontos, raio modulado por 3 senoides defasadas. */
private fun blobPath(cx: Float, cy: Float, r: Float, amp: Float, t: Float, sp: Float, seed: Float): Path {
    val path = Path()
    val n = 110
    for (i in 0..n) {
        val a = (i.toFloat() / n) * TAU
        val d = 1f + amp * (
            sin(a * 3f + t * 0.0016f * sp + seed) +
                0.5f * sin(a * 5f - t * 0.0021f * sp + seed * 1.7f) +
                0.3f * sin(a * 2f + t * 0.0027f * sp)
            )
        val rr = r * d
        val x = cx + cos(a) * rr
        val y = cy + sin(a) * rr
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

// --- Lógica pura (testável em unit) ---

/** Amplitude/velocidade base por estado. Paused herda o visual de Listening (REC-05). */
internal fun orbBaseParams(state: OrbState): Pair<Float, Float> = when (state) {
    OrbState.Idle -> 0.05f to 0.55f
    OrbState.Transcribing -> 0.11f to 1.15f
    OrbState.Listening, OrbState.Paused -> 0.20f to 1.7f
}

internal fun orbLevel(state: OrbState, t: Float): Float = when (state) {
    OrbState.Idle -> 0.55f + 0.08f * sin(t * 0.002f)
    OrbState.Transcribing -> 0.78f + 0.07f * sin(t * 0.018f)
    OrbState.Listening, OrbState.Paused ->
        0.60f + 0.40f * abs(0.7f * sin(t * 0.0024f) + 0.3f * sin(t * 0.0061f + 1.3f))
}

/** Formata segundos como `m:ss` (Space Mono 700 no centro do orbe). */
internal fun formatOrbTime(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
