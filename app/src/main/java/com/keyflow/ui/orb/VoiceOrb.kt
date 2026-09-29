package com.keyflow.ui.orb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/**
 * Estilo do orbe: [ATELIE] (o blob teal de sempre) ou [AERO] (protótipo, 2026-09-28): a mesma forma viva,
 * mas como uma esfera de vidro azul — brilho na metade de cima, luz na base e aro fino, no espírito do
 * Windows Vista/7. Escolhido na aba Sobrepor ([orbStyleFor]), à parte da skin do teclado.
 */
enum class OrbStyle(val id: String) {
    ATELIE("atelie"),
    AERO("aero"),
}

/** Estilo do orbe para a árvore abaixo — a overlay e as prévias das abas seguem a skin escolhida. */
val LocalOrbStyle = staticCompositionLocalOf { OrbStyle.ATELIE }

/** Id de skin da orbe ([com.keyflow.data.Settings.orbSkin]) → estilo; o que não for "aero" é o Ateliê. */
fun orbStyleFor(orbSkinId: String): OrbStyle = if (orbSkinId == OrbStyle.AERO.id) OrbStyle.AERO else OrbStyle.ATELIE

/** Conteúdo desenhado no centro do orbe (ORB-04). */
sealed interface OrbCenter {
    data object None : OrbCenter
    data class Time(val seconds: Int) : OrbCenter
    /** Pausa: glifo de pause + tempo congelado miúdo abaixo (REC-05). */
    data class PauseGlyph(val seconds: Int) : OrbCenter
    data object ErrorGlyph : OrbCenter
    /** Descarte do áudio (cancelar): glifo de lixeira + tempo congelado miúdo abaixo (Fase 2). */
    data class CancelGlyph(val seconds: Int) : OrbCenter
}

/** Alvo do modo-arrasto (orbe travada): glifo central conforme a direção/estado. */
enum class OrbDragKind { PAUSE, CANCEL, RESUME, CONFIG }

/**
 * Dica de arrasto (orbe travada): o glifo central [centerKind] aparece ganhando opacidade por
 * proximidade ([centerAlpha] 0→1), enquanto as setas ↑/← começam opacas e somem conforme o dedo
 * se direciona a elas ([upAlpha]/[leftAlpha] 1→0).
 */
data class OrbDragHint(
    val centerKind: OrbDragKind?,
    val centerAlpha: Float,
    val seconds: Int, // tempo mostrado abaixo/no glifo do alvo (igual pause/cancelamento)
)

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

/** Tinta do tempo e dos glifos no centro — a do estilo do orbe ([VoiceOrb] define). */
private val LocalCenterInk = staticCompositionLocalOf { CenterInk }
private val ErrorRed = Color(0xFFD9694B)

// Paleta Aero: vidro azul com miolo de luz; tinta marinho no centro.
private val AeroGlowInner = Color(0x7345C8F5)
private val AeroGlowOuter = Color(0x0045C8F5)
private val AeroMainInner = Color(0xFFBFF0FF)
private val AeroMainOuter = Color(0xFF1C6FCF)
private val AeroCoreInner = Color(0xFFF4FDFF)
private val AeroCoreOuter = Color(0xFF7AD6FF)
private val AeroRing = Color(0xFF45C8F5)
private val AeroRim = Color(0x80062A5C)
private val AeroInk = Color(0xFF0B2B4C)
private val CancelAmber = Color(0xFFC9772F) // descarte: terracota/âmbar, distinto do X de erro

@Composable
fun VoiceOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    center: OrbCenter = OrbCenter.None,
    dragHint: OrbDragHint? = null,
    timeProvider: (() -> Float)? = null,
    style: OrbStyle = LocalOrbStyle.current,
) {
    val provided = timeProvider ?: LocalOrbClock.current
    val animatedMs = rememberOrbClock(enabled = provided == null)
    val timeMs = provided?.invoke() ?: animatedMs

    // Tempo e glifos do centro na tinta do estilo (marrom no Ateliê, marinho no vidro do Aero).
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCenterInk provides if (style == OrbStyle.AERO) AeroInk else CenterInk,
    ) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            // Lado da orbe em px: o conteúdo central (tempo/glifos) escala com o tamanho (slider Sobrepor).
            val sidePx = minOf(constraints.maxWidth, constraints.maxHeight)
                .takeIf { it in 1..(Int.MAX_VALUE / 2) } ?: DEFAULT_ORB_SIDE_PX

            Canvas(Modifier.matchParentSize()) { drawOrb(timeMs, state, style) }
            // Centro do estado. No modo-arrasto ele some conforme o alvo aparece (crossfade), pra não
            // sobrepor — os glifos têm o mesmo tamanho/área, então só o ativo da vez fica visível.
            val baseAlpha = dragHint?.let { (1f - it.centerAlpha).coerceIn(0f, 1f) } ?: 1f
            Box(Modifier.matchParentSize().alpha(baseAlpha), contentAlignment = Alignment.Center) {
                when (center) {
                    is OrbCenter.Time -> OrbTimeText(center.seconds, sidePx, density)
                    is OrbCenter.PauseGlyph -> GlyphWithTime(center.seconds, sidePx, density) { PauseGlyph(it) }
                    OrbCenter.ErrorGlyph -> ErrorGlyph(with(density) { (sidePx * GLYPH_FRACTION).toDp() })
                    is OrbCenter.CancelGlyph -> GlyphWithTime(center.seconds, sidePx, density) { CancelGlyph(it) }
                    OrbCenter.None -> Unit
                }
            }
            // Alvo do arrasto no centro (as setas ficam numa sobreposição separada, fora da janela da orbe).
            dragHint?.centerKind?.let { kind ->
                Box(
                    Modifier.matchParentSize().alpha(dragHint.centerAlpha.coerceIn(0f, 1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Retomar = "estado ativo" → tempo grande (uniforme com gravando↔pause).
                    // Pausar/cancelar = glifo + tempo miúdo abaixo (mesmo formato do pause/cancelamento).
                    when (kind) {
                        OrbDragKind.RESUME -> OrbTimeText(dragHint.seconds, sidePx, density)
                        OrbDragKind.PAUSE -> GlyphWithTime(dragHint.seconds, sidePx, density) { PauseGlyph(it) }
                        OrbDragKind.CANCEL -> GlyphWithTime(dragHint.seconds, sidePx, density) { CancelGlyph(it) }
                        OrbDragKind.CONFIG -> GlyphWithTime(dragHint.seconds, sidePx, density) { GearGlyph(it) }
                    }
                }
            }
        }
    }
}

// Calibrado para o tamanho padrão da orbe (≈156px): mantém o tempo/glifos no mesmo tamanho de antes
// e os faz crescer/encolher proporcionalmente fora dele.
private const val DEFAULT_ORB_SIDE_PX = 156
private const val TIME_FONT_FRACTION = 0.235f
private const val GLYPH_FRACTION = 0.30f
// Pausa/cancelamento: glifo encolhido + tempo congelado miúdo abaixo (cabe nos dois no centro da orbe).
private const val PAUSED_GLYPH_FRACTION = 0.22f
private const val SMALL_TIME_FONT_FRACTION = 0.135f

/** Tempo grande no centro (estado ativo de gravação). Reusado no centro e no alvo "retomar". */
@Composable
private fun OrbTimeText(seconds: Int, sidePx: Int, density: Density) {
    Text(
        text = formatOrbTime(seconds),
        color = LocalCenterInk.current,
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Bold,
        fontSize = with(density) { (sidePx * TIME_FONT_FRACTION).toSp() },
        textAlign = TextAlign.Center,
    )
}

/** Glifo (pause/lixeira) encolhido com o tempo congelado miúdo logo abaixo, empilhados no centro. */
@Composable
private fun GlyphWithTime(seconds: Int, sidePx: Int, density: Density, glyph: @Composable (Dp) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(with(density) { (sidePx * 0.02f).toDp() }),
    ) {
        glyph(with(density) { (sidePx * PAUSED_GLYPH_FRACTION).toDp() })
        Text(
            text = formatOrbTime(seconds),
            color = LocalCenterInk.current,
            fontFamily = SpaceMono,
            fontWeight = FontWeight.Bold,
            fontSize = with(density) { (sidePx * SMALL_TIME_FONT_FRACTION).toSp() },
            textAlign = TextAlign.Center,
        )
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

/** Glifo de engrenagem (abrir config) — alvo ↓ do modo-arrasto, no estilo dos glifos da orbe. */
@Composable
private fun GearGlyph(glyphSize: Dp) {
    val ink = LocalCenterInk.current
    Canvas(Modifier.size(glyphSize)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val rOuter = size.minDimension * 0.34f
        val toothLen = size.minDimension * 0.13f
        val sw = size.minDimension * 0.11f
        drawCircle(ink, radius = rOuter, center = c, style = Stroke(width = sw))
        for (i in 0 until 8) {
            val ang = (TAU / 8f) * i
            val dir = Offset(cos(ang), sin(ang))
            drawLine(ink, c + dir * rOuter, c + dir * (rOuter + toothLen), strokeWidth = sw * 1.3f, cap = StrokeCap.Round)
        }
        drawCircle(ink, radius = size.minDimension * 0.12f, center = c)
    }
}

/** Glifo de pause (duas barras) desenhado no centro do orbe (ORB-02/REC-05). */
@Composable
private fun PauseGlyph(glyphSize: Dp) {
    val ink = LocalCenterInk.current
    Canvas(Modifier.size(glyphSize)) {
        // Folga vertical (0.16..0.84) para ocupar a mesma área de play/lixeira.
        val barW = size.width * 0.26f
        val gap = size.width * 0.18f
        val top = size.height * 0.16f
        val barH = size.height * 0.68f
        drawRect(ink, topLeft = Offset(size.width / 2f - gap / 2f - barW, top), size = Size(barW, barH))
        drawRect(ink, topLeft = Offset(size.width / 2f + gap / 2f, top), size = Size(barW, barH))
    }
}



/** Glifo de erro (X vermelho) — exibido brevemente quando a transcrição falha. */
@Composable
private fun ErrorGlyph(glyphSize: Dp) {
    Canvas(Modifier.size(glyphSize)) {
        val pad = size.width * 0.16f
        val sw = size.width * 0.26f // traço mais pesado (como as barras largas do pause), sem aumentar o X
        drawLine(ErrorRed, Offset(pad, pad), Offset(size.width - pad, size.height - pad), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(ErrorRed, Offset(size.width - pad, pad), Offset(pad, size.height - pad), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/** Glifo de lixeira (descarte do áudio) — exibido no estado de cancelamento (Fase 2). */
@Composable
private fun CancelGlyph(glyphSize: Dp) {
    Canvas(Modifier.size(glyphSize)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val left = w * 0.24f
        val right = w * 0.76f
        val lidY = h * 0.28f
        // Tampa + alça
        drawLine(CancelAmber, Offset(left - w * 0.04f, lidY), Offset(right + w * 0.04f, lidY), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(CancelAmber, Offset(w * 0.40f, lidY), Offset(w * 0.40f, h * 0.18f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(CancelAmber, Offset(w * 0.60f, lidY), Offset(w * 0.60f, h * 0.18f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(CancelAmber, Offset(w * 0.40f, h * 0.18f), Offset(w * 0.60f, h * 0.18f), strokeWidth = sw, cap = StrokeCap.Round)
        // Corpo (balde) — laterais ligeiramente afuniladas + base
        val bodyTop = lidY + h * 0.04f
        val bodyBottom = h * 0.80f
        drawLine(CancelAmber, Offset(left, bodyTop), Offset(left + w * 0.04f, bodyBottom), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(CancelAmber, Offset(right, bodyTop), Offset(right - w * 0.04f, bodyBottom), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(CancelAmber, Offset(left + w * 0.04f, bodyBottom), Offset(right - w * 0.04f, bodyBottom), strokeWidth = sw, cap = StrokeCap.Round)
        // Ranhuras verticais
        listOf(0.42f, 0.5f, 0.58f).forEach { fx ->
            drawLine(CancelAmber, Offset(w * fx, bodyTop + h * 0.07f), Offset(w * fx, bodyBottom - h * 0.07f), strokeWidth = sw * 0.7f, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawOrb(t: Float, state: OrbState, style: OrbStyle = OrbStyle.ATELIE) {
    if (style == OrbStyle.AERO) {
        drawAeroOrb(t, state)
        return
    }
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

/**
 * Orbe Aero: o mesmo blob vivo de [drawOrb] (níveis e velocidades por estado), pintado como vidro — halo
 * ciano, esfera azul com miolo claro, aro escuro fino, brilho elíptico na metade de cima e luz na base.
 */
private fun DrawScope.drawAeroOrb(t: Float, state: OrbState) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val (amp, sp) = orbBaseParams(state)
    val lvl = orbLevel(state, t)
    val r = min(size.width, size.height) * 0.30f * (0.84f + 0.20f * lvl)

    if (state == OrbState.Listening || state == OrbState.Paused) {
        for (k in 0 until 3) {
            val fr = ((t * 0.0006f) + (k / 3f)) % 1f
            drawCircle(
                color = AeroRing,
                radius = r * (1.05f + fr * 0.95f),
                center = Offset(cx, cy),
                alpha = 0.30f * (1f - fr),
                style = Stroke(width = 1.6f),
            )
        }
    }

    drawLayer(cx, cy, r * 1.14f, amp * 0.7f, t, sp * 0.8f, 5.0f, AeroGlowInner, AeroGlowOuter, 1f)
    val body = blobPath(cx, cy, r, amp * lvl, t, sp, 2.0f)
    drawPath(
        body,
        Brush.radialGradient(listOf(AeroMainInner, AeroMainOuter), center = Offset(cx - r * 0.2f, cy - r * 0.35f), radius = r * 1.3f),
    )
    drawLayer(cx, cy, r * 0.62f, amp * 0.7f, t, sp * 1.35f, 9.0f, AeroCoreInner, AeroCoreOuter, 0.85f)
    // Luz que atravessa o vidro e acende a base.
    drawOval(
        Brush.radialGradient(
            listOf(Color(0x80D8FBFF), Color(0x00D8FBFF)),
            center = Offset(cx, cy + r * 0.62f),
            radius = r * 0.7f,
        ),
        topLeft = Offset(cx - r * 0.62f, cy + r * 0.38f),
        size = Size(r * 1.24f, r * 0.48f),
    )
    drawPath(body, AeroRim, style = Stroke(width = maxOf(1.2f, r * 0.03f)))
    // O brilho do Aero: elipse clara na metade de cima, sumindo para baixo.
    drawOval(
        Brush.verticalGradient(
            listOf(Color(0xD9FFFFFF), Color(0x1AFFFFFF)),
            startY = cy - r * 0.92f,
            endY = cy - r * 0.05f,
        ),
        topLeft = Offset(cx - r * 0.66f, cy - r * 0.92f),
        size = Size(r * 1.32f, r * 0.84f),
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
    // Duas frequências fora de fase (em vez de uma onda única) — quebra o padrão monótono/óbvio de uma
    // senoide só, dando um movimento mais "vivo" sem ficar tão exagerado quanto o de Listening (pedido do
    // Yan, 2026-07-03: "transcrevendo" estava sutil demais e com padrão pouco variável).
    OrbState.Transcribing -> 0.78f + 0.05f * sin(t * 0.014f) + 0.04f * sin(t * 0.033f + 0.9f)
    OrbState.Listening, OrbState.Paused ->
        0.60f + 0.40f * abs(0.7f * sin(t * 0.0024f) + 0.3f * sin(t * 0.0061f + 1.3f))
}

/** Formata segundos como `m:ss` (Space Mono 700 no centro do orbe). */
internal fun formatOrbTime(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
