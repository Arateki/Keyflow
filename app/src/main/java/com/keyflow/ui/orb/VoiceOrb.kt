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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.Text
import com.keyflow.skins.OrbPackState
import com.keyflow.skins.PackOrb
import com.keyflow.skins.SkinIds
import com.keyflow.ui.theme.SpaceMono
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
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
 * mas como uma bolha de vidro transparente — o que está atrás dela aparece pelo miolo, com aro, brilho e
 * luz na base, no espírito do Windows Vista/7. Escolhido na aba Sobrepor ([orbStyleFor]), à parte do teclado.
 */
enum class OrbStyle(
    val id: String,
    /**
     * Skin paga. A orbe padrão (Ateliê) é a gratuita; a Aero é paga (Yan, 2026-09-29: o Aero é pago). Por
     * enquanto liberada para teste; bloqueia junto com as skins do teclado quando a monetização entrar
     * (Milestone 7, UNL-04 / MZ-T9).
     */
    val paid: Boolean,
) {
    ATELIE("atelie", paid = false),
    AERO("aero", paid = true),

    /**
     * Falha gráfica em preto, branco, vermelho e azul, como a logo do TikTok (Yan, 2026-09-29): a orbe é preta, com o
     * aro branco e as franjas ciano e vermelha deslocadas (somam em branco onde se cruzam); de tempos em tempos
     * uma rajada de falha desloca faixas horizontais — em repouso e nos outros estados. Paga, como o Aero.
     */
    GLITCH("glitch", paid = true),

    /**
     * Rose Gold (Yan, 2026-09-29): uma esfera metálica rosé, com faixa de cetim que varre devagar, um halo rosado e faíscas
     * que cintilam. Feminina e elegante; paga, como as outras. A tinta do centro é vinho.
     */
    ROSE_GOLD("rosegold", paid = true),

    /**
     * O Ouvi (Yan, 2026-10-02): um mascote vivo e orgânico no lugar dos globos — uma criatura redonda, de orelhas enormes,
     * que respira, pisca, mexe as orelhas e muda de ânimo conforme o estado ([drawMascotOrb]). Paga, como as outras; a
     * tinta do centro é o marrom do Ateliê, sobre a barriga creme.
     */
    MASCOT("mascot", paid = true),

    /**
     * Uma skin **de pacote** do usuário (SKP-ORB): os frames SVG por estado, desenhados por [PackOrb] — o pacote vem de [LocalOrbPack]. O
     * id guardado nos ajustes não é este `"pack"`, e sim `pack:<id>` ([SkinIds]); [orbStyleFor] devolve este estilo para todos eles. Sem
     * pacote em [LocalOrbPack] (sumiu, quebrou, não tem orbe) a orbe cai no Ateliê. Gratuita e nunca bloqueada: a skin é do usuário (D4).
     */
    PACK("pack", paid = false),
}

/** Estilo do orbe para a árvore abaixo — a overlay e as prévias das abas seguem a skin escolhida. */
val LocalOrbStyle = staticCompositionLocalOf { OrbStyle.ATELIE }

/** O pacote de skin que [OrbStyle.PACK] desenha; `null` = nenhum (a orbe cai no Ateliê). Quem provê abre o pacote (OverlayService, ConfigScreen). */
val LocalOrbPack = staticCompositionLocalOf<PackOrb?> { null }

/**
 * Id de skin da orbe ([com.keyflow.data.Settings.orbSkin]) → estilo; `pack:<id>` válido é [OrbStyle.PACK]; um id que não existe (ou um
 * `pack:` inseguro) é o Ateliê.
 */
fun orbStyleFor(orbSkinId: String): OrbStyle =
    if (SkinIds.isPack(orbSkinId)) OrbStyle.PACK else OrbStyle.entries.firstOrNull { it.id == orbSkinId } ?: OrbStyle.ATELIE

/** Resolve a skin paga salva sem apagar a escolha: se o direito voltar, ela reaparece automaticamente. */
fun orbStyleFor(orbSkinId: String, skinsUnlocked: Boolean): OrbStyle {
    val style = orbStyleFor(orbSkinId)
    return if (style.paid && !skinsUnlocked) OrbStyle.ATELIE else style
}

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

/** Cor do halo do tempo (null = sem halo; só o vidro do Aero usa). */
private val LocalCenterGlow = staticCompositionLocalOf<Color?> { null }
private val ErrorRed = Color(0xFFD9694B)

// Paleta Aero: bolha de vidro. Nada aqui é opaco além dos fios do aro — o miolo deixa o app de trás passar.
// Os degradês mantêm o mesmo matiz de ponta a ponta (só o alfa muda), para não sujar de cinza ao sumir.
private val AeroGlowInner = Color(0x3345C8F5)
private val AeroGlowOuter = Color(0x0045C8F5)
private val AeroRing = Color(0xFF45C8F5)
private val AeroRim = Color(0x99063A78)
private val AeroInk = Color(0xFF0B2B4C)
/** Halo de texto sobre o vidro: a letra marinho ganha uma luz branca ao redor, legível em fundo claro ou escuro. */
private val AeroInkGlow = Color(0xF2FFFFFF)
// Paleta Glitch: preto, branco e os dois canais deslocados (ciano e vermelho, como a logo do TikTok).
private val GlitchCyan = Color(0xFF25F4EE)
private val GlitchRed = Color(0xFFFE2C55)

// Paleta Rose Gold: champanhe no brilho, rosé no corpo, rosé fundo na borda; tinta vinho no centro.
private val RoseInk = Color(0xFF5B2A35)
private val RoseHighlight = Color(0xFFFFF1E6)
private val RoseMid = Color(0xFFE8ADA3)
private val RoseEdge = Color(0xFFB56E6E)
private val RoseHaloInner = Color(0x40F4B8B8)
private val RoseHaloOuter = Color(0x00F4B8B8)
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
    // A skin de pacote desenha só se há um pacote com orbe; sem ele, o estilo PACK é o Ateliê.
    val pack = if (style == OrbStyle.PACK) LocalOrbPack.current else null

    // Tempo e glifos do centro na tinta do estilo (marrom no Ateliê, marinho no vidro do Aero).
    androidx.compose.runtime.CompositionLocalProvider(
        LocalCenterInk provides when (style) {
            OrbStyle.AERO -> AeroInk
            OrbStyle.GLITCH -> Color.White
            OrbStyle.ROSE_GOLD -> RoseInk
            OrbStyle.MASCOT -> MascotInk
            OrbStyle.ATELIE -> CenterInk
            OrbStyle.PACK -> pack?.let { Color(it.centerInk) } ?: CenterInk
        },
        LocalCenterGlow provides if (style == OrbStyle.AERO) AeroInkGlow else null,
    ) {
        BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            // Lado da orbe em px: o conteúdo central (tempo/glifos) escala com o tamanho (slider Sobrepor).
            val sidePx = minOf(constraints.maxWidth, constraints.maxHeight)
                .takeIf { it in 1..(Int.MAX_VALUE / 2) } ?: DEFAULT_ORB_SIDE_PX

            // Com algo escrito no centro, o vidro ganha um miolo leitoso por baixo (tempo e glifos leem sobre qualquer fundo).
            val hasCenter = center != OrbCenter.None || dragHint?.centerKind != null
            // O Glitch soma as franjas (BlendMode.Plus): numa camada própria elas somam só entre si, não com o que está atrás
            // (sobre um fundo claro, ciano e vermelho viravam branco).
            val layer = if (style == OrbStyle.GLITCH) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen) else Modifier
            Canvas(Modifier.matchParentSize().then(layer)) {
                if (pack != null) drawPackOrb(timeMs, state, pack, deterministic = provided != null) else drawOrb(timeMs, state, style, hasCenter)
            }
            // Centro do estado. No modo-arrasto ele some conforme o alvo aparece (crossfade), pra não
            // sobrepor — os glifos têm o mesmo tamanho/área, então só o ativo da vez fica visível.
            val baseAlpha = dragHint?.let { (1f - it.centerAlpha).coerceIn(0f, 1f) } ?: 1f
            // O Glitch escreve o centro em três camadas (ciano, vermelho e a tinta), deslocadas como as franjas da orbe.
            val glitchSplit = if (style == OrbStyle.GLITCH) (sidePx * (0.012f + 0.03f * glitchIntensity(timeMs, state))).roundToInt() else 0
            Box(Modifier.matchParentSize().alpha(baseAlpha), contentAlignment = Alignment.Center) {
                GlitchLayers(glitchSplit) {
                    when (center) {
                        is OrbCenter.Time -> OrbTimeText(center.seconds, sidePx, density)
                        is OrbCenter.PauseGlyph -> GlyphWithTime(center.seconds, sidePx, density) { PauseGlyph(it) }
                        OrbCenter.ErrorGlyph -> ErrorGlyph(with(density) { (sidePx * GLYPH_FRACTION).toDp() })
                        is OrbCenter.CancelGlyph -> GlyphWithTime(center.seconds, sidePx, density) { CancelGlyph(it) }
                        OrbCenter.None -> Unit
                    }
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
                    GlitchLayers(glitchSplit) {
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
}

/**
 * O conteúdo do centro no Glitch: três cópias empilhadas — ciano deslocado para a esquerda, vermelho para a direita
 * e a tinta normal (branca) por cima —, a aberração cromática da logo do TikTok. Com [offsetPx] zero (os outros
 * estilos) só desenha o conteúdo, uma vez. Os glifos que têm cor própria (lixeira, X de erro) mantêm a deles.
 */
@Composable
private fun GlitchLayers(offsetPx: Int, content: @Composable () -> Unit) {
    if (offsetPx == 0) {
        content()
        return
    }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.offset { IntOffset(-offsetPx, 0) }) {
            androidx.compose.runtime.CompositionLocalProvider(LocalCenterInk provides GlitchCyan) { content() }
        }
        Box(Modifier.offset { IntOffset(offsetPx, 0) }) {
            androidx.compose.runtime.CompositionLocalProvider(LocalCenterInk provides GlitchRed) { content() }
        }
        content()
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
        style = centerGlowStyle(sidePx),
    )
}

/** Halo do tempo no vidro do Aero (o Ateliê não usa): sombra sem deslocamento, do tamanho da orbe. */
@Composable
private fun centerGlowStyle(sidePx: Int): TextStyle =
    LocalCenterGlow.current?.let { TextStyle(shadow = Shadow(it, Offset.Zero, sidePx * 0.07f)) } ?: TextStyle.Default

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
            style = centerGlowStyle(sidePx),
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

/**
 * A orbe de um pacote de skin (SKP-ORB-01): o frame SVG do estado no quadrado inteiro da janela da orbe. O tempo e os glifos do centro
 * não fazem parte do desenho — o Compose os põe por cima, na `orb.centerInk`. Só copia um bitmap já pronto ([PackOrb.frameFor]): nunca
 * espera render, a não ser que o relógio seja injetado ([deterministic]: prévias, galeria, testes), onde o pixel precisa ser sempre o mesmo.
 */
private fun DrawScope.drawPackOrb(t: Float, state: OrbState, pack: PackOrb, deterministic: Boolean) {
    // Ler a versão no desenho faz o Compose redesenhar quando um frame novo chega do fundo (com o relógio parado, ninguém mais pediria).
    pack.version
    val side = min(size.width, size.height).roundToInt()
    if (side <= 0) return
    val bitmap = pack.frameFor(state.toPackState(), t, side, deterministic) ?: return
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmap(bitmap, ((size.width - side) / 2f).roundToInt().toFloat(), ((size.height - side) / 2f).roundToInt().toFloat(), null)
    }
}

/** O estado do app → o estado de um pacote de skin (os mesmos quatro, com os mesmos nomes). */
internal fun OrbState.toPackState(): OrbPackState = when (this) {
    OrbState.Idle -> OrbPackState.IDLE
    OrbState.Listening -> OrbPackState.LISTENING
    OrbState.Paused -> OrbPackState.PAUSED
    OrbState.Transcribing -> OrbPackState.TRANSCRIBING
}

private fun DrawScope.drawOrb(t: Float, state: OrbState, style: OrbStyle = OrbStyle.ATELIE, hasCenter: Boolean = false) {
    if (style == OrbStyle.AERO) {
        drawAeroOrb(t, state, hasCenter)
        return
    }
    if (style == OrbStyle.GLITCH) {
        drawGlitchOrb(t, state)
        return
    }
    if (style == OrbStyle.ROSE_GOLD) {
        drawRoseGoldOrb(t, state)
        return
    }
    if (style == OrbStyle.MASCOT) {
        drawMascotOrb(t, state, hasCenter)
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
 * Orbe Aero: o mesmo blob vivo de [drawOrb] (níveis e velocidades por estado), mas como uma **bolha de vidro
 * transparente com água dentro** ([drawAeroWater]), não uma bola azul. Acima da água o vidro é limpo — o app por
 * trás aparece —, e o corpo do vidro vive na borda: tinta azul mais densa perto do aro (o "Fresnel" do vidro), fio marinho por fora (segura a
 * bolha sobre fundo claro) e fio branco por dentro (sobre fundo escuro), brilho de gel na metade de cima e
 * a luz que atravessa o vidro e se junta na base. Tudo que é claro é recortado pela forma da bolha, que
 * ondula. [frosted] (há tempo ou glifo no centro) acrescenta um miolo leitoso para a leitura.
 */
private fun DrawScope.drawAeroOrb(t: Float, state: OrbState, frosted: Boolean) {
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
    val inner = blobPath(cx, cy, r * 0.94f, amp * lvl, t, sp, 2.0f)

    // Sombra macia ao redor: separa a bolha de um fundo claro sem pintar o miolo.
    drawPath(body, Color(0x0D0A2A55), style = Stroke(width = r * 0.34f))
    drawPath(body, Color(0x140A2A55), style = Stroke(width = r * 0.16f))

    // Corpo do vidro: quase limpo no meio, azul mais denso na borda.
    drawPath(
        body,
        Brush.radialGradient(
            0f to Color(0x2256C8F8),
            0.55f to Color(0x3052B4F0),
            0.82f to Color(0x5C2E86E0),
            1f to Color(0x99165EC0),
            center = Offset(cx, cy - r * 0.05f),
            radius = r * 1.08f,
        ),
    )

    // A água dentro do vidro: fica dentro do fio branco do aro.
    clipPath(inner) { drawAeroWater(cx, cy, r, t, amp, sp, lvl) }

    clipPath(body) {
        if (frosted) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0x59FFFFFF), Color(0x00FFFFFF)), center = Offset(cx, cy), radius = r * 0.8f),
                radius = r * 0.8f,
                center = Offset(cx, cy),
            )
        }
        // A luz que atravessa o vidro e se junta na base (círculo cujo degradê zera na própria borda).
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xB3D2F7FF), Color(0x00D2F7FF)),
                center = Offset(cx, cy + r * 0.78f),
                radius = r * 0.62f,
            ),
            radius = r * 0.62f,
            center = Offset(cx, cy + r * 0.78f),
        )
        // Fio de luz junto ao aro, mais forte embaixo.
        drawPath(
            inner,
            Brush.verticalGradient(
                0f to Color(0x00E6FBFF),
                0.5f to Color(0x00E6FBFF),
                1f to Color(0xB3E6FBFF),
                startY = cy - r,
                endY = cy + r,
            ),
            style = Stroke(width = r * 0.10f),
        )
        // Brilho de gel na metade de cima, e o clarão pequeno do canto.
        drawOval(
            Brush.verticalGradient(
                listOf(Color(0xCCFFFFFF), Color(0x0FFFFFFF)),
                startY = cy - r * 0.94f,
                endY = cy - r * 0.32f,
            ),
            topLeft = Offset(cx - r * 0.60f, cy - r * 0.94f),
            size = Size(r * 1.20f, r * 0.62f),
        )
        drawOval(
            Color(0xF2FFFFFF),
            topLeft = Offset(cx - r * 0.50f, cy - r * 0.76f),
            size = Size(r * 0.20f, r * 0.11f),
        )
    }
    // Fio branco por dentro do aro (some do fundo claro, brilha no escuro) e fio marinho por fora.
    drawPath(
        inner,
        Brush.verticalGradient(listOf(Color(0xF2FFFFFF), Color(0x59FFFFFF)), startY = cy - r, endY = cy + r),
        style = Stroke(width = maxOf(1.2f, r * 0.035f)),
    )
    drawPath(body, AeroRim, style = Stroke(width = maxOf(1.2f, r * 0.028f)))
}

private const val WATER_STEPS = 28

// Água em dois tons, como o miolo da orbe do Ateliê tem dois: ciano claro na superfície, azul fundo embaixo. Ambos
// translúcidos — o app de trás se vê através da água, só que tingido.
private val WaterTop = Color(0x8C5BE0F2)
private val WaterBottom = Color(0xB81660D8)

/**
 * A água dentro do vidro (o chamador recorta pela forma interna da bolha). O nível sobe com a voz ([lvl]) e a
 * superfície ondula: amplitude e velocidade vêm do estado ([amp]/[sp], as mesmas do blob), com uma inclinação
 * lenta que faz a água rolar de um lado ao outro. Por baixo da superfície, um fio de luz (o menisco), cáusticas
 * que ondulam devagar e bolhinhas que sobem — tudo função do tempo [t], então um relógio parado dá um quadro fixo.
 */
private fun DrawScope.drawAeroWater(cx: Float, cy: Float, r: Float, t: Float, amp: Float, sp: Float, lvl: Float) {
    val fill = aeroWaterFill(lvl)
    val surface = cy + r * 0.94f * (1f - 2f * fill)
    val wave = r * aeroWaveHeight(amp, lvl)
    val phase = t * 0.003f * sp
    val tilt = r * 0.10f * sin(phase * 0.55f) * (0.4f + amp * 3f)
    fun surfaceAt(x: Float): Float {
        val u = (x - cx) / r
        return surface + tilt * u + wave * (sin(2.3f * u + phase) + 0.45f * sin(3.9f * u - 1.3f * phase + 1.7f))
    }

    val left = cx - r * 1.2f
    val right = cx + r * 1.2f
    val line = Path()
    for (i in 0..WATER_STEPS) {
        val x = left + (right - left) * i / WATER_STEPS
        if (i == 0) line.moveTo(x, surfaceAt(x)) else line.lineTo(x, surfaceAt(x))
    }
    val water = Path().apply {
        addPath(line)
        lineTo(right, cy + r * 1.2f)
        lineTo(left, cy + r * 1.2f)
        close()
    }
    drawPath(water, Brush.verticalGradient(0f to WaterTop, 1f to WaterBottom, startY = surface - wave, endY = cy + r * 0.94f))

    // Cáusticas: fios de luz que atravessam a água e ondulam devagar.
    for (k in 0 until 3) {
        val y0 = surface + r * (0.20f + 0.20f * k)
        if (y0 > cy + r * 0.8f) continue
        val caustic = Path()
        for (i in 0..16) {
            val x = cx - r * 0.85f + r * 1.7f * i / 16f
            val y = y0 + r * 0.035f * sin(x / r * 4f + phase * (0.8f + 0.3f * k) + k * 2f)
            if (i == 0) caustic.moveTo(x, y) else caustic.lineTo(x, y)
        }
        drawPath(caustic, Color(0x38FFFFFF), style = Stroke(width = r * 0.04f, cap = StrokeCap.Round))
    }

    // Bolhinhas subindo até a superfície (poucas e lentas em repouso).
    val bubbles = if (sp < 0.8f) 2 else 5
    val bottom = cy + r * 0.8f
    for (k in 0 until bubbles) {
        val bx = cx + r * (-0.5f + 0.25f * k) + r * 0.04f * sin(t * 0.002f + k * 1.7f)
        val rise = (t * 0.00012f * (0.6f + 0.5f * sp) * (0.7f + 0.15f * k) + k * 0.23f) % 1f
        val top = surfaceAt(bx)
        val br = r * (0.03f + 0.012f * (k % 3))
        val by = bottom - rise * (bottom - top)
        if (by < top + br) continue
        drawCircle(Color(0x33FFFFFF), br, Offset(bx, by))
        drawCircle(Color(0xB3FFFFFF), br, Offset(bx, by), style = Stroke(width = maxOf(1f, r * 0.012f)))
        drawCircle(Color(0xF2FFFFFF), br * 0.28f, Offset(bx - br * 0.35f, by - br * 0.35f))
    }

    // O menisco: fio claro na superfície, com um halo mais largo e fraco por baixo.
    drawPath(line, Color(0x40FFFFFF), style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
    drawPath(line, Color(0xD9FFFFFF), style = Stroke(width = maxOf(1.2f, r * 0.03f), cap = StrokeCap.Round))
}

/**
 * Orbe Glitch: o mesmo blob vivo, mas como a logo do TikTok com defeito. Um miolo preto de aro branco e, por baixo,
 * o mesmo formato em ciano (deslocado para a esquerda) e em vermelho (para a direita), somados (BlendMode.Plus): só
 * as franjas aparecem, e onde se cruzam viram branco. Scanlines e um anel interno dão a textura de tela de tubo.
 * De tempos em tempos ([glitchIntensity]) vem uma rajada: as franjas se afastam e algumas faixas horizontais da
 * orbe deslizam de lado — em repouso e nos outros estados, cada um no seu ritmo. Transcrevendo, uma faixa de luz
 * varre o miolo de cima para baixo.
 */
private fun DrawScope.drawGlitchOrb(t: Float, state: OrbState) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val (amp, sp) = orbBaseParams(state)
    val lvl = orbLevel(state, t)
    val r = min(size.width, size.height) * 0.30f * (0.84f + 0.20f * lvl)
    val g = glitchIntensity(t, state)
    val split = r * (0.045f + 0.13f * g)

    if (state == OrbState.Listening || state == OrbState.Paused) {
        for (k in 0 until 3) {
            val fr = ((t * 0.0006f) + (k / 3f)) % 1f
            drawCircle(
                color = if (k % 2 == 0) GlitchCyan else GlitchRed,
                radius = r * (1.05f + fr * 0.95f),
                center = Offset(cx, cy),
                alpha = 0.40f * (1f - fr),
                style = Stroke(width = 1.6f),
            )
        }
    }

    val body = blobPath(cx, cy, r, amp * lvl, t, sp, 2.0f)
    val core = blobPath(cx, cy, r * 0.90f, amp * lvl, t, sp, 2.0f)
    val inner = blobPath(cx, cy, r * 0.52f, amp * 0.7f, t, sp * 1.35f, 9.0f)

    val bands = glitchBands(t, state, g, cy, r)
    if (bands.isEmpty()) {
        drawGlitchStack(cx, cy, r, body, core, inner, split, t, state)
        return
    }
    // Com faixas: a orbe inteira é desenhada em fatias horizontais; as das faixas deslizam de lado.
    fun segment(top: Float, bottom: Float, dx: Float) {
        clipRect(0f, top, size.width, bottom) {
            translate(dx, 0f) { drawGlitchStack(cx, cy, r, body, core, inner, split, t, state) }
        }
    }
    var y = 0f
    for (band in bands) {
        if (band.y0 > y) segment(y, band.y0, 0f)
        segment(band.y0, band.y1, band.dx)
        y = band.y1
    }
    segment(y, size.height, 0f)
}

/** Uma vez a orbe Glitch: as duas franjas, o miolo preto com textura e o aro branco. */
private fun DrawScope.drawGlitchStack(
    cx: Float, cy: Float, r: Float, body: Path, core: Path, inner: Path, split: Float, t: Float, state: OrbState,
) {
    translate(-split, split * 0.25f) { drawPath(body, GlitchCyan, alpha = 0.95f, blendMode = BlendMode.Plus) }
    translate(split, -split * 0.25f) { drawPath(body, GlitchRed, alpha = 0.95f, blendMode = BlendMode.Plus) }
    drawPath(core, Color(0xF2000000))
    clipPath(core) {
        // Scanlines: uma linha clara fina a cada poucos px, como numa tela de tubo.
        val step = r * 0.09f
        val lineH = maxOf(1f, r * 0.012f)
        var y = cy - r
        while (y < cy + r) {
            drawRect(Color(0x14FFFFFF), Offset(cx - r, y), Size(r * 2f, lineH))
            y += step
        }
        drawPath(inner, Color(0x66FFFFFF), style = Stroke(width = maxOf(1f, r * 0.02f)))
        if (state == OrbState.Transcribing) {
            // A faixa de luz que varre o miolo enquanto transcreve.
            val sy = cy - r + ((t * 0.0009f) % 1f) * 2f * r
            drawRect(Color(0x40FFFFFF), Offset(cx - r, sy), Size(r * 2f, r * 0.08f))
            drawRect(GlitchCyan, Offset(cx - r, sy + r * 0.08f), Size(r * 2f, maxOf(1f, r * 0.015f)), alpha = 0.6f)
        }
    }
    drawPath(core, Color.White, style = Stroke(width = maxOf(1.5f, r * 0.035f)))
}

/** Uma faixa horizontal da orbe que desliza [dx] px de lado durante uma rajada de falha. */
private class GlitchBand(val y0: Float, val y1: Float, val dx: Float)

/**
 * As faixas de uma rajada (nenhuma fora dela): de 1 a 4 conforme a intensidade [g], em posições e larguras que vêm do
 * número da rajada — o mesmo tempo dá sempre as mesmas faixas —, sem se sobrepor e de cima para baixo.
 */
private fun glitchBands(t: Float, state: OrbState, g: Float, cy: Float, r: Float): List<GlitchBand> {
    if (g <= 0.02f) return emptyList()
    val burst = floor(t / glitchPeriodMs(state)).toInt()
    val raw = (0 until 1 + (g * 3f).toInt()).map { i ->
        val mid = cy + r * (glitchRandom(burst, i) * 2f - 1f) * 0.95f
        val half = r * (0.03f + 0.07f * glitchRandom(burst, i + 7))
        val dx = r * (0.10f + 0.28f * g) * (if (glitchRandom(burst, i + 13) > 0.5f) 1f else -1f)
        GlitchBand(mid - half, mid + half, dx)
    }.sortedBy { it.y0 }
    val out = ArrayList<GlitchBand>(raw.size)
    var edge = -Float.MAX_VALUE
    for (band in raw) {
        val y0 = maxOf(band.y0, edge)
        if (band.y1 > y0) {
            out.add(GlitchBand(y0, band.y1, band.dx))
            edge = band.y1
        }
    }
    return out
}

/** Número pseudo-aleatório estável em 0..1 a partir de dois inteiros (o mesmo par dá sempre o mesmo valor). */
private fun glitchRandom(a: Int, b: Int): Float {
    val x = sin(a * 12.9898f + b * 78.233f) * 43758.547f
    return x - floor(x)
}

/**
 * Orbe Rose Gold: o mesmo blob vivo como uma esfera de metal rosé. O corpo é um degradê radial com o brilho de champanhe
 * deslocado para cima e à esquerda; por cima, uma faixa de cetim (clara, oblíqua) varre a esfera devagar, um oval
 * de brilho e a luz rosada refletida na base; o aro vai de champanhe em cima a rosé fundo embaixo (metal polido). Três
 * faíscas de quatro pontas cintilam em sequência ao redor. Tudo é função do tempo [t]: relógio parado, quadro fixo.
 */
private fun DrawScope.drawRoseGoldOrb(t: Float, state: OrbState) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val (amp, sp) = orbBaseParams(state)
    val lvl = orbLevel(state, t)
    val r = min(size.width, size.height) * 0.30f * (0.84f + 0.20f * lvl)

    if (state == OrbState.Listening || state == OrbState.Paused) {
        for (k in 0 until 3) {
            val fr = ((t * 0.0006f) + (k / 3f)) % 1f
            drawCircle(
                color = Color(0xFFE79AA6),
                radius = r * (1.05f + fr * 0.95f),
                center = Offset(cx, cy),
                alpha = 0.35f * (1f - fr),
                style = Stroke(width = 1.6f),
            )
        }
    }

    drawLayer(cx, cy, r * 1.14f, amp * 0.7f, t, sp * 0.8f, 5.0f, RoseHaloInner, RoseHaloOuter, 1f)
    val body = blobPath(cx, cy, r, amp * lvl, t, sp, 2.0f)
    drawPath(
        body,
        Brush.radialGradient(
            0f to RoseHighlight,
            0.45f to RoseMid,
            1f to RoseEdge,
            center = Offset(cx - r * 0.28f, cy - r * 0.34f),
            radius = r * 1.35f,
        ),
    )
    clipPath(body) {
        // A faixa de cetim: clara, oblíqua, varrendo a esfera de ponta a ponta (um ciclo a cada ~4,5 s).
        val sweep = (t * 0.00022f) % 1f
        val bandX = cx - r * 1.6f + sweep * r * 3.2f
        rotate(-25f, pivot = Offset(cx, cy)) {
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color(0x00FFF4EC), Color(0x59FFF4EC), Color(0x00FFF4EC)),
                    startX = bandX - r * 0.28f,
                    endX = bandX + r * 0.28f,
                ),
                topLeft = Offset(bandX - r * 0.28f, cy - r * 1.7f),
                size = Size(r * 0.56f, r * 3.4f),
            )
        }
        // O brilho: um oval suave em cima à esquerda.
        drawOval(
            Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x10FFFFFF)), startY = cy - r * 0.95f, endY = cy - r * 0.2f),
            topLeft = Offset(cx - r * 0.62f, cy - r * 0.92f),
            size = Size(r * 0.9f, r * 0.62f),
        )
        // A luz rosada que o metal reflete na base.
        drawCircle(
            Brush.radialGradient(listOf(Color(0x99FFC9C0), Color(0x00FFC9C0)), center = Offset(cx + r * 0.1f, cy + r * 0.75f), radius = r * 0.6f),
            radius = r * 0.6f,
            center = Offset(cx + r * 0.1f, cy + r * 0.75f),
        )
    }
    // O aro: metal polido, champanhe em cima e rosé fundo embaixo.
    drawPath(
        body,
        Brush.verticalGradient(listOf(Color(0xFFFFF7EE), Color(0xCCB56E6E)), startY = cy - r, endY = cy + r),
        style = Stroke(width = maxOf(1.4f, r * 0.04f)),
    )
    // As faíscas: cintilam uma de cada vez (o brilho de cada uma sobe e desce com o tempo).
    for (i in 0 until 3) {
        val angle = i * 2.1f + 0.7f
        val dist = r * (0.72f + 0.42f * (i % 2))
        val alpha = sin(t * 0.002f + i * 2.1f - 0.9f).coerceAtLeast(0f).let { it * it * it * it * it * it }
        if (alpha > 0.02f) drawSparkle(Offset(cx + cos(angle) * dist, cy + sin(angle) * dist), r * (0.11f + 0.04f * i), alpha)
    }
}

/** Uma faísca de quatro pontas: um losango fino em cruz, branco, com um halo champanhe atrás. */
private fun DrawScope.drawSparkle(center: Offset, size: Float, alpha: Float) {
    drawCircle(Color(0xFFFFD9C7), radius = size * 0.75f, center = center, alpha = alpha * 0.5f)
    val star = Path().apply {
        moveTo(center.x, center.y - size)
        lineTo(center.x + size * 0.16f, center.y - size * 0.16f)
        lineTo(center.x + size, center.y)
        lineTo(center.x + size * 0.16f, center.y + size * 0.16f)
        lineTo(center.x, center.y + size)
        lineTo(center.x - size * 0.16f, center.y + size * 0.16f)
        lineTo(center.x - size, center.y)
        lineTo(center.x - size * 0.16f, center.y - size * 0.16f)
        close()
    }
    drawPath(star, Color.White, alpha = alpha)
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

/**
 * Fração da altura interna da bolha ocupada pela água: mais de um terço em repouso e até dois terços no pico da
 * voz ([lvl] é o nível de [orbLevel], 0,55 em repouso a 1,0 no pico). Nunca esvazia nem transborda.
 */
internal fun aeroWaterFill(lvl: Float): Float = 0.34f + 0.34f * ((lvl - 0.55f) / 0.45f).coerceIn(0f, 1f)

/** Altura das ondas da superfície, em fração do raio: uma marola em repouso, ondas altas quando o blob se agita. */
internal fun aeroWaveHeight(amp: Float, lvl: Float): Float = 0.03f + 0.45f * amp * lvl

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

/** De quanto em quanto tempo o Glitch tem uma rajada de falha: devagar em repouso, rápido transcrevendo. */
internal fun glitchPeriodMs(state: OrbState): Float = when (state) {
    OrbState.Idle -> 1900f
    OrbState.Transcribing -> 650f
    OrbState.Listening, OrbState.Paused -> 1100f
}

private fun glitchBurstStartMs(state: OrbState): Float = glitchPeriodMs(state) * when (state) {
    OrbState.Idle -> 0.60f
    OrbState.Transcribing -> 0.50f
    OrbState.Listening, OrbState.Paused -> 0.35f
}

private fun glitchBurstLengthMs(state: OrbState): Float = when (state) {
    OrbState.Idle -> 240f
    OrbState.Transcribing -> 200f
    OrbState.Listening, OrbState.Paused -> 220f
}

/**
 * Intensidade da falha do Glitch no instante [t] (ms), de 0 (nada) a 1 (o pico de uma rajada): cada período tem uma
 * rajada que sobe e desce em triângulo, e uma micro-rajada fraca (até 0,45) num ritmo à parte para a falha não parecer
 * metrônomo. Função pura do tempo: um relógio parado dá um quadro fixo.
 */
internal fun glitchIntensity(t: Float, state: OrbState): Float {
    val period = glitchPeriodMs(state)
    val phase = ((t % period) + period) % period
    val u = (phase - glitchBurstStartMs(state)) / glitchBurstLengthMs(state)
    val main = if (u in 0f..1f) 1f - abs(2f * u - 1f) else 0f
    val microPeriod = period * 1.37f
    val micro = (((t + 400f) % microPeriod) + microPeriod) % microPeriod
    val microG = if (micro < MICRO_BURST_MS) 0.45f * (1f - abs(2f * micro / MICRO_BURST_MS - 1f)) else 0f
    return maxOf(main, microG)
}

private const val MICRO_BURST_MS = 70f

/** Um instante no pico de uma rajada do estado [state] (2º ciclo): a galeria e as prévias congelam a orbe aqui. */
internal fun glitchBurstMidMs(state: OrbState): Float =
    glitchPeriodMs(state) + glitchBurstStartMs(state) + glitchBurstLengthMs(state) / 2f

/** Formata segundos como `m:ss` (Space Mono 700 no centro do orbe). */
internal fun formatOrbTime(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
