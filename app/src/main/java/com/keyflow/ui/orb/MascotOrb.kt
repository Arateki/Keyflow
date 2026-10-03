package com.keyflow.ui.orb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * O Ouvi: o mascote da orbe (skin [OrbStyle.MASCOT], Yan, 2026-10-02: "algum tipo de mascote que pareça mais vivo e
 * orgânico, em vez dos globos abstratos"). Uma criatura redonda e gorducha, verde-água como o KeyFlow, de barriga
 * creme, olhos grandes e **orelhas enormes** — o ouvido é a metáfora da voz virando texto. É original: nada dele vem
 * de um personagem existente.
 *
 * É tudo código: curvas de Bézier e degradês, desenhados como função pura de `(t, estado, hasCenter)` — a pose vem de
 * [MascotPose] e o calendário dos eventos raros de [MascotTimeline]. Relógio parado, pixel parado.
 *
 * ### O centro é do app
 * O tempo (`m:ss`, fonte de 0,235·lado: quase a largura da orbe) e os glifos são escritos por cima do centro
 * geométrico, em [MascotInk]. Por isso o rosto mora na metade de cima e a barriga é uma placa creme lisa no meio;
 * com [hasCenter] a placa **cresce** (cobre a linha do tempo de ponta a ponta), o rosto sobe um pouco e a boca some — ela
 * ficaria debaixo dos dígitos. Sem centro (repouso, transcrevendo) o rosto ocupa o espaço todo.
 *
 * ### Em qualquer fundo
 * A orbe flutua sobre qualquer app. O corpo tem um contorno verde-fundo e, por fora dele, um filete creme (como um
 * adesivo): sobre o branco manda o contorno; sobre o preto, o filete.
 *
 * ### Custo
 * Tudo é desenhado no espaço da unidade (`1 = lado da orbe`) por uma só transformação, então os degradês são constantes
 * (sem recriar shader por quadro), os `Stroke` são constantes e os caminhos e a pose são reaproveitados ([MascotScratch], um
 * por thread): o desenho não aloca nada por quadro. Parado, não custa nada — quem anima é o relógio da orbe.
 */
internal fun DrawScope.drawMascotOrb(t: Float, state: OrbState, hasCenter: Boolean) {
    val unit = min(size.width, size.height)
    val scratch = Scratch.get() ?: MascotScratch().also { Scratch.set(it) }
    val pose = scratch.pose
    pose.update(t, state)
    withTransform({
        translate(size.width / 2f, size.height / 2f)
        scale(unit, unit, Offset.Zero)
    }) {
        drawMascot(scratch, pose, state, hasCenter, t)
    }
}

/** Tinta do tempo e dos glifos no centro: o marrom do Ateliê, sobre a barriga creme (contraste ≈ 13:1). */
internal val MascotInk = Color(0xFF2B2720)

// --- Paleta (a do Ateliê: teal, creme, terracota, âmbar, tinta) ---
private val BodyLight = Color(0xFFB4DECF)
private val BodyMid = Color(0xFF7BBBA7)
private val BodyDeep = Color(0xFF3F8470)
private val Contour = Color(0xFF2B6455)
private val Rim = Color(0xFFF4ECCF)
/** As duas pontas do degradê da barriga (creme claro no alto, creme sombreado embaixo): a tinta do centro lê sobre as duas. */
internal val MascotBellyTop = Color(0xFFFFF9E6)
internal val MascotBellyBottom = Color(0xFFEEE0B6)
private val EarTip = Color(0xFF9FD1C0)
private val EarBase = Color(0xFF5BA38D)
private val InnerEarTop = Color(0xFFF0A58C)
private val InnerEarBottom = Color(0xFFD9694B)
private val Terracotta = Color(0xFFD9694B)
private val Amber = Color(0xFFC6873F)
private val Cream = Color(0xFFF4ECCF)
private val FootColor = Color(0xFF357A66)
private val Ink = MascotInk
private val IrisGlow = Color(0x66B58A5A)
private val SmallShine = Color(0xCCFFFFFF)

// --- Geometria (unidades: 1 = lado da orbe; origem no centro geométrico; y cresce para baixo) ---
private const val BODY_N = 12
private const val BODY_RX = 0.325f
private const val BODY_EXP = 2.5f
private const val BODY_WOBBLE = 0.007f
private const val TUFT_LEN = 0.125f
private const val TUFT_W = 0.072f
private const val TAIL_X = 0.27f
private const val TAIL_Y = 0.17f
private const val TAIL_ANGLE = 62f
private const val TAIL_LEN = 0.17f
private const val TAIL_W = 0.115f
private const val EYE_RX = 0.047f
private const val EYE_RY = 0.057f
private const val FEET_Y = 0.345f

/**
 * Como o mascote se acomoda no quadrado da orbe. Sem nada escrito no centro ([FULL]) ele usa o espaço todo: orelhas
 * grandes, olhos grandes e boca. Com o tempo ou um glifo no centro ([CENTER]) o corpo fica um pouco mais alto, as
 * orelhas mais curtas, o rosto sobe e se afasta (os olhos ficam acima da linha do tempo e dos lados do glifo de pausa) e
 * a barriga vira uma placa larga, que cobre o texto de ponta a ponta; a boca some, porque ficaria debaixo dos dígitos.
 */
private class MascotLayout(
    val bodyCy: Float, val bodyRy: Float,
    val earX: Float, val earY: Float, val earLen: Float, val earW: Float,
    val eyeY: Float, val eyeDx: Float, val faceScale: Float,
    val bellyCy: Float, val bellyRx: Float, val bellyRy: Float,
    val mouth: Boolean,
)

private val FULL = MascotLayout(
    bodyCy = 0.035f, bodyRy = 0.285f,
    earX = 0.160f, earY = -0.205f, earLen = 0.218f, earW = 0.150f,
    eyeY = -0.115f, eyeDx = 0.128f, faceScale = 1f,
    bellyCy = 0.150f, bellyRx = 0.205f, bellyRy = 0.140f,
    mouth = true,
)
private val CENTER = MascotLayout(
    bodyCy = 0.020f, bodyRy = 0.310f,
    earX = 0.160f, earY = -0.244f, earLen = 0.176f, earW = 0.140f,
    eyeY = -0.195f, eyeDx = 0.140f, faceScale = 0.90f,
    bellyCy = 0.078f, bellyRx = 0.300f, bellyRy = 0.195f,
    mouth = false,
)

// O contorno do corpo e o filete creme por fora dele.
private const val CONTOUR_W = 0.016f
private const val RIM_W = 0.050f

/** Os 12 pontos do corpo num quase-quadrado arredondado ("mochi"), unitários; mais largos embaixo (formato de pera). */
private val BODY_UX = FloatArray(BODY_N) { i ->
    val a = i * 2.0 * PI / BODY_N
    (sign(cos(a)) * abs(cos(a)).pow(2.0 / BODY_EXP)).toFloat()
}
private val BODY_UY = FloatArray(BODY_N) { i ->
    val a = i * 2.0 * PI / BODY_N
    (sign(sin(a)) * abs(sin(a)).pow(2.0 / BODY_EXP)).toFloat()
}
private val BODY_PEAR = FloatArray(BODY_N) { 1f + 0.06f * BODY_UY[it] }

/** Frequência e fase da ondulação de cada ponto do corpo: sem divisor comum, o contorno nunca repete. */
private val WOB_W = floatArrayOf(0.0011f, 0.0017f, 0.0023f, 0.0013f, 0.0029f, 0.0019f, 0.0031f, 0.0014f, 0.0021f, 0.0027f, 0.0016f, 0.0025f)
private val WOB_P = floatArrayOf(0.0f, 1.7f, 3.1f, 4.4f, 0.9f, 2.6f, 5.2f, 3.8f, 1.2f, 4.9f, 2.2f, 5.9f)

private val BodyBrush = Brush.radialGradient(
    0f to BodyLight,
    0.5f to BodyMid,
    1f to BodyDeep,
    center = Offset(-0.12f, -0.20f),
    radius = 0.66f,
)
private val BellyBrush = Brush.verticalGradient(0f to MascotBellyTop, 1f to MascotBellyBottom, startY = -0.14f, endY = 0.30f)
private val EarBrush = Brush.verticalGradient(0f to EarTip, 1f to EarBase, startY = -0.225f, endY = 0f)
private val InnerEarBrush = Brush.verticalGradient(0f to InnerEarTop, 1f to InnerEarBottom, startY = -0.18f, endY = 0f)
private val TailBrush = Brush.verticalGradient(0.30f to Amber, 0.85f to Cream, startY = 0f, endY = -TAIL_LEN)
private val TuftBrush = Brush.verticalGradient(0.15f to Amber, 0.9f to Cream, startY = 0f, endY = -TUFT_LEN)
private val CheekBrush = Brush.radialGradient(0f to Color(0xFFFF9C88), 1f to Color(0x00FF9C88), center = Offset.Zero, radius = 0.052f)
private val ShineBrush = Brush.radialGradient(0f to Color(0x66FFFFFF), 1f to Color(0x00FFFFFF), center = Offset(-0.15f, -0.20f), radius = 0.15f)
private val BounceBrush = Brush.radialGradient(0f to Color(0x55D2F3E6), 1f to Color(0x00D2F3E6), center = Offset(0.13f, 0.31f), radius = 0.20f)
private val ShadowBrush = Brush.radialGradient(0f to Color(0x38000000), 1f to Color(0x00000000), center = Offset.Zero, radius = 0.2f)

private val RimStroke = Stroke(width = RIM_W, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ContourStroke = Stroke(width = CONTOUR_W, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val MouthStroke = Stroke(width = 0.011f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ClosedEyeStroke = Stroke(width = 0.015f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ArcStroke = Stroke(width = 0.016f, cap = StrokeCap.Round)
private val ArcRimStroke = Stroke(width = 0.034f, cap = StrokeCap.Round)
private val ZOutline = Stroke(width = 0.036f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val ZStroke = Stroke(width = 0.018f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Os caminhos e vetores que o desenho reaproveita a cada quadro (o `DrawScope` roda a ~60 fps enquanto a orbe aparece). */
private class MascotScratch {
    val pose = MascotPose()
    val body = Path()
    val earL = Path()
    val earR = Path()
    val innerEarL = Path()
    val innerEarR = Path()
    val tail = Path()
    val tuft = Path()
    val lid = Path()
    val mouth = Path()
    val z = Path()
    val xs = FloatArray(BODY_N)
    val ys = FloatArray(BODY_N)
}

/** Um por thread: o desenho acontece na principal, mas nada impede duas janelas de desenhar uma orbe cada. */
private val Scratch: ThreadLocal<MascotScratch> = ThreadLocal.withInitial { MascotScratch() }

/**
 * Onde fica a ponta de uma orelha na janela (unidades; 0,0 = centro da orbe), já depois da respiração, do balanço e da
 * altura do corpo — o mesmo caminho das transformações do desenho. O teste de unidade usa para garantir que a ponta (com
 * o filete) nunca passa da borda: a janela da orbe é o quadrado `sizePx` e o que sai dela é cortado.
 */
internal fun mascotEarTip(p: MascotPose, right: Boolean, hasCenter: Boolean): Offset {
    val l = if (hasCenter) CENTER else FULL
    val angle = (if (right) p.earR else p.earL) * (PI.toFloat() / 180f)
    val bend = if (right) p.earBendR else p.earBendL
    val len = l.earLen * p.earLen
    // A ponta da folha, no espaço da orelha (base em 0,0, apontando para cima), girada e posta sobre a cabeça.
    val lx = bend
    val ly = -len
    val x = (if (right) l.earX else -l.earX) + lx * cos(angle) - ly * sin(angle)
    val y = l.earY + lx * sin(angle) + ly * cos(angle)
    return bodyToWindow(p, x, y, hasCenter)
}

/** A ponta do rabo na janela, como em [mascotEarTip]. */
internal fun mascotTailTip(p: MascotPose, hasCenter: Boolean): Offset {
    val angle = (TAIL_ANGLE + p.tail) * (PI.toFloat() / 180f)
    val bend = tailBendOf(p)
    val x = TAIL_X + bend * cos(angle) + TAIL_LEN * sin(angle)
    val y = TAIL_Y + bend * sin(angle) - TAIL_LEN * cos(angle)
    return bodyToWindow(p, x, y, hasCenter)
}

/** Um ponto do espaço do corpo → janela: escala em torno dos pés, gira em torno do pivô ([leanPivotY]) e sobe/desce com o balanço. */
private fun bodyToWindow(p: MascotPose, x: Float, y: Float, hasCenter: Boolean): Offset {
    val sx = x * p.scaleX
    val sy = FEET_Y + (y - FEET_Y) * p.scaleY
    val a = p.lean * (PI.toFloat() / 180f)
    val pivot = leanPivotY(hasCenter)
    val dy = sy - pivot
    return Offset(sx * cos(a) - dy * sin(a), pivot + sx * sin(a) + dy * cos(a) + p.bob)
}

/**
 * Em torno de que altura o corpo balança. Sem nada no centro, em torno dos pés (o peso no chão: a cabeça vai mais longe). Com
 * o tempo ou um glifo escrito no centro, em torno do centro da orbe: o app escreve num lugar fixo, e a cabeça não pode
 * varrer o rosto para cima dele quando o corpo inclina.
 */
private fun leanPivotY(hasCenter: Boolean): Float = if (hasCenter) 0.02f else FEET_Y

private fun DrawScope.drawMascot(s: MascotScratch, p: MascotPose, state: OrbState, hasCenter: Boolean, t: Float) {
    val l = if (hasCenter) CENTER else FULL
    buildBody(s, t, l)
    buildLeaves(s, p, l)

    // Sombra no chão: um oval macio sob os pés, mais estreito quando o mascote sobe.
    withTransform({ translate(0f, FEET_Y + 0.045f); scale(1.25f + p.bob * 4f, 0.17f, Offset.Zero) }) {
        drawCircle(ShadowBrush, radius = 0.2f, center = Offset.Zero)
    }

    // Os efeitos de fora do corpo (não balançam com ele): os arcos de som de quem escuta.
    if (state == OrbState.Listening) drawSoundArcs(p)

    withTransform({
        translate(0f, p.bob)
        rotate(p.lean, Offset(0f, leanPivotY(hasCenter)))
        scale(p.scaleX, p.scaleY, Offset(0f, FEET_Y))
    }) {
        // 1) O filete creme por fora de tudo (o "adesivo"): primeiro, para o que vem depois cobrir a metade de dentro.
        drawEarsAndTail(s, p, l, rimPass = true)
        feet(rimPass = true)
        drawPath(s.body, Rim, style = RimStroke)

        // 2) Atrás do corpo: rabo, pés e orelhas.
        drawEarsAndTail(s, p, l, rimPass = false)
        feet(rimPass = false)

        // 3) O corpo: degradê, a barriga e os brilhos recortados por ele, e o contorno.
        drawPath(s.body, BodyBrush)
        clipPath(s.body) { drawBellyAndShine(p, l) }
        drawPath(s.body, Contour, style = ContourStroke)

        // 4) O rosto, por cima: gira um pouquinho para onde o mascote olha.
        translate(p.gazeX * 0.010f, p.gazeY * 0.006f) { drawFace(s, p, l) }

        // 5) Efeitos que moram na cabeça: os "z" do sonolento e os pontinhos de quem pensa.
        if (state == OrbState.Paused) drawZs(s, p)
        if (state == OrbState.Transcribing) drawThinkDots(p)
    }
}

/** Monta o corpo: 12 pontos com uma ondulação leve cada um, ligados por Béziers (Catmull-Rom). */
private fun buildBody(s: MascotScratch, t: Float, l: MascotLayout) {
    val xs = s.xs
    val ys = s.ys
    for (i in 0 until BODY_N) {
        val w = 1f + BODY_WOBBLE * sin(t * WOB_W[i] + WOB_P[i])
        xs[i] = BODY_UX[i] * BODY_RX * BODY_PEAR[i] * w
        ys[i] = l.bodyCy + BODY_UY[i] * l.bodyRy * w
    }
    val path = s.body
    path.rewind()
    path.moveTo(xs[0], ys[0])
    for (i in 0 until BODY_N) {
        val p0 = (i + BODY_N - 1) % BODY_N
        val p2 = (i + 1) % BODY_N
        val p3 = (i + 2) % BODY_N
        path.cubicTo(
            xs[i] + (xs[p2] - xs[p0]) / 6f, ys[i] + (ys[p2] - ys[p0]) / 6f,
            xs[p2] - (xs[p3] - xs[i]) / 6f, ys[p2] - (ys[p3] - ys[i]) / 6f,
            xs[p2], ys[p2],
        )
    }
    path.close()
}

/** Uma folha: a orelha (e o rabo). Base em (0, 0), ponta em (bend, -len), bordas curvas, ponta bem arredondada. */
private fun leaf(path: Path, len: Float, w: Float, bend: Float) {
    path.rewind()
    path.moveTo(-w * 0.5f, 0.03f)
    path.cubicTo(-w * 0.80f, -len * 0.42f, bend - w * 0.56f, -len, bend, -len)
    path.cubicTo(bend + w * 0.56f, -len, w * 0.80f, -len * 0.42f, w * 0.5f, 0.03f)
    path.close()
}

/**
 * A dobra da ponta do rabo no desenho: sempre para cima (<= 0 no espaço do rabo), de 0 a -0,05, com o acompanhamento da pose
 * ([MascotPose.tailBend]) por cima de uma curvatura de repouso de -0,03.
 *
 * **Não pode virar positiva.** O emulador do CI roda com `-gpu swiftshader_indirect`, e nele o `qemu` leva SIGSEGV na
 * RenderThread ao preencher com degradê a folha do rabo quando a ponta dobra para o outro lado (medido em 2026-10-03: só o rabo,
 * com dobra de +0,03 ou +0,08, derrubou o emulador na 1ª a 5ª abertura da rota; com 0 ou -0,04, 0 quedas em 8 aberturas; o mesmo
 * degradê num oval, com cor lisa ou com a dobra negativa, também não derrubou). Ver `.specs/quick/018-skin-mascote/TASK.md`.
 */
internal fun tailBendOf(p: MascotPose): Float = (p.tailBend - 0.03f).coerceIn(-0.05f, 0f)

/** Monta as folhas do quadro (rabo, topete, as duas orelhas e o miolo delas) uma vez só: os dois passes do desenho as reaproveitam. */
private fun buildLeaves(s: MascotScratch, p: MascotPose, l: MascotLayout) {
    leaf(s.tail, TAIL_LEN, TAIL_W, tailBendOf(p))
    leaf(s.tuft, TUFT_LEN, TUFT_W, p.tuft * 0.0006f)
    val len = l.earLen * p.earLen
    // O de dentro: terracota, menor, mais perto da base que da ponta.
    leaf(s.earL, len, l.earW, p.earBendL)
    leaf(s.innerEarL, len * 0.74f, l.earW * 0.54f, p.earBendL * 0.7f)
    leaf(s.earR, len, l.earW, p.earBendR)
    leaf(s.innerEarR, len * 0.74f, l.earW * 0.54f, p.earBendR * 0.7f)
}

/** O rabo, o topete e as orelhas, atrás do corpo: no passo do filete só o contorno largo; no outro, o preenchimento e o contorno fino. */
private fun DrawScope.drawEarsAndTail(s: MascotScratch, p: MascotPose, l: MascotLayout, rimPass: Boolean) {
    // Rabo: do lado direito embaixo, apontando para fora.
    withTransform({ translate(TAIL_X, TAIL_Y); rotate(TAIL_ANGLE + p.tail, Offset.Zero) }) {
        if (rimPass) {
            drawPath(s.tail, Rim, style = RimStroke)
        } else {
            drawPath(s.tail, TailBrush)
            drawPath(s.tail, Contour, style = ContourStroke)
        }
    }
    // O topete, entre as orelhas: a base fica escondida sob a cabeça.
    withTransform({ translate(0f, l.bodyCy - l.bodyRy + 0.05f); rotate(p.tuft, Offset.Zero) }) {
        if (rimPass) {
            drawPath(s.tuft, Rim, style = RimStroke)
        } else {
            drawPath(s.tuft, TuftBrush)
            drawPath(s.tuft, Contour, style = ContourStroke)
        }
    }
    withTransform({ translate(-l.earX, l.earY); rotate(p.earL, Offset.Zero) }) {
        drawEar(s.earL, s.innerEarL, l.earLen * p.earLen, rimPass)
    }
    withTransform({ translate(l.earX, l.earY); rotate(p.earR, Offset.Zero) }) {
        drawEar(s.earR, s.innerEarR, l.earLen * p.earLen, rimPass)
    }
}

private fun DrawScope.drawEar(ear: Path, inner: Path, len: Float, rimPass: Boolean) {
    if (rimPass) {
        drawPath(ear, Rim, style = RimStroke)
    } else {
        drawPath(ear, EarBrush)
        translate(0f, -len * 0.03f) { drawPath(inner, InnerEarBrush) }
        drawPath(ear, Contour, style = ContourStroke)
    }
}

private fun DrawScope.feet(rimPass: Boolean) {
    for (i in 0..1) {
        val topLeft = Offset((if (i == 0) -0.14f else 0.14f) - 0.075f, FEET_Y - 0.052f)
        val size = Size(0.15f, 0.085f)
        if (rimPass) {
            drawOval(Rim, topLeft, size, style = RimStroke)
        } else {
            drawOval(FootColor, topLeft, size)
            drawOval(Contour, topLeft, size, style = ContourStroke)
        }
    }
}

/** A barriga creme (placa lisa: é onde o app escreve o tempo) e os brilhos do corpo, já recortados pelo corpo. */
private fun DrawScope.drawBellyAndShine(p: MascotPose, l: MascotLayout) {
    // Borda macia: um oval um pouco maior, translúcido, antes da barriga.
    drawOval(Cream.copy(alpha = 0.40f), Offset(-l.bellyRx - 0.012f, l.bellyCy - l.bellyRy - 0.012f), Size((l.bellyRx + 0.012f) * 2f, (l.bellyRy + 0.012f) * 2f))
    drawOval(BellyBrush, Offset(-l.bellyRx, l.bellyCy - l.bellyRy), Size(l.bellyRx * 2f, l.bellyRy * 2f))
    // O brilho do alto do corpo e a luz refletida do chão, embaixo à direita (dá volume ao corpo).
    drawCircle(ShineBrush, radius = 0.15f, center = Offset(-0.15f, -0.20f))
    drawCircle(BounceBrush, radius = 0.20f, center = Offset(0.13f, 0.31f))
    // Escutando, o peito ganha um calor que pulsa com a voz.
    if (p.chestGlow > 0.02f) {
        drawOval(Terracotta.copy(alpha = 0.10f * p.chestGlow), Offset(-l.bellyRx, l.bellyCy - l.bellyRy), Size(l.bellyRx * 2f, l.bellyRy * 2f))
    }
}

private fun DrawScope.drawFace(s: MascotScratch, p: MascotPose, l: MascotLayout) {
    val eyeY = l.eyeY
    // Bochechas (por baixo dos olhos, nas laterais).
    for (i in 0..1) {
        val sx = if (i == 0) -1f else 1f
        withTransform({ translate(sx * (l.eyeDx + 0.075f), eyeY + 0.066f); scale(1f, 0.62f, Offset.Zero) }) {
            drawCircle(CheekBrush, radius = 0.052f, center = Offset.Zero, alpha = p.cheek)
        }
    }
    for (i in 0..1) {
        val sx = if (i == 0) -1f else 1f
        // O olho também gira um pouco dentro do rosto, além do rosto todo se virar.
        drawEye(s, p, sx * l.eyeDx + p.gazeX * 0.008f, eyeY + p.gazeY * 0.006f, l.faceScale)
    }
    if (l.mouth) drawMouth(s, p, eyeY + 0.085f)
}

private fun DrawScope.drawEye(s: MascotScratch, p: MascotPose, cx: Float, cy: Float, faceScale: Float) {
    val rx = EYE_RX * p.eyeScale * faceScale
    val ry = EYE_RY * p.eyeScale * faceScale
    if (p.blink > 0.88f || p.lid > 0.80f) {
        // Fechado: um arco para baixo (olho feliz de gato), mais fino que o olho.
        s.lid.rewind()
        s.lid.moveTo(cx - rx * 0.95f, cy + ry * 0.15f)
        s.lid.quadraticTo(cx, cy + ry * 0.90f, cx + rx * 0.95f, cy + ry * 0.15f)
        drawPath(s.lid, Ink, style = ClosedEyeStroke)
        return
    }
    // Piscando, o olho achata (em torno do centro) em vez de ser cortado: dá o ar de olho molhado se fechando.
    val squash = 1f - 0.9f * p.blink
    if (p.lid <= 0.02f) {
        scale(1f, squash, Offset(cx, cy)) { eyeShapes(p, cx, cy, rx, ry) }
        return
    }
    // A pálpebra caída: um corte reto sobre o oval do olho (o sonolento tem o olhar pesado).
    val lidY = cy - ry + 2f * ry * p.lid
    clipRect(cx - rx * 1.4f, lidY, cx + rx * 1.4f, cy + ry * 1.4f) {
        scale(1f, squash, Offset(cx, cy)) { eyeShapes(p, cx, cy, rx, ry) }
    }
}

/** O olho aberto: o oval escuro, o reflexo da íris embaixo e dois brilhos que ficam quase parados enquanto o olho gira. */
private fun DrawScope.eyeShapes(p: MascotPose, cx: Float, cy: Float, rx: Float, ry: Float) {
    drawOval(Ink, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f))
    drawOval(IrisGlow, Offset(cx - rx * 0.72f, cy + ry * 0.12f), Size(rx * 1.44f, ry * 0.76f))
    val hx = -p.gazeX * 0.004f
    val hy = -p.gazeY * 0.003f
    drawCircle(Color.White, radius = rx * 0.36f, center = Offset(cx - rx * 0.30f + hx, cy - ry * 0.34f + hy))
    drawCircle(SmallShine, radius = rx * (0.15f + 0.07f * p.sparkle), center = Offset(cx + rx * 0.34f + hx, cy + ry * 0.30f + hy))
}

private fun DrawScope.drawMouth(s: MascotScratch, p: MascotPose, y: Float) {
    // O nariz: um pontinho escuro, redondo, acima da boca.
    drawOval(Ink, Offset(-0.013f, y - 0.034f), Size(0.026f, 0.017f))
    if (p.mouthOpen > 0.1f) {
        // Aberta: um "o" pequeno, com a língua terracota aparecendo quando abre mais.
        val h = 0.012f + 0.020f * p.mouthOpen
        val w = 0.016f + 0.007f * p.mouthOpen
        drawOval(Ink, Offset(-w, y - 0.004f), Size(w * 2f, h * 2f))
        if (p.mouthOpen > 0.45f) drawOval(Terracotta, Offset(-w * 0.55f, y + h * 0.9f), Size(w * 1.1f, h * 0.8f))
    } else {
        // Fechada: a boquinha de gato (dois arcos), sorrindo ou não.
        val d = 0.016f * p.smile
        s.mouth.rewind()
        s.mouth.moveTo(-0.030f, y - 0.002f)
        s.mouth.quadraticTo(-0.015f, y - 0.002f + d, 0f, y - 0.002f)
        s.mouth.quadraticTo(0.015f, y - 0.002f + d, 0.030f, y - 0.002f)
        drawPath(s.mouth, Ink, style = MouthStroke)
    }
}

/** Arcos de som dos dois lados da cabeça de quem escuta: crescem e somem, em duas ondas defasadas. */
private fun DrawScope.drawSoundArcs(p: MascotPose) {
    for (k in 0 until 2) {
        val fr = MascotPose.wrapPhase(p.soundPhase + k * 0.5f)
        val r = 0.40f + 0.085f * fr
        val a = 1f - fr
        val topLeft = Offset(-r, -0.12f - r)
        val size = Size(r * 2f, r * 2f)
        // O filete creme por fora, como no corpo, e o verde por dentro: lê no branco e no preto.
        for (start in ARC_STARTS) {
            drawArc(Rim.copy(alpha = a * 0.95f), start, 56f, false, topLeft, size, style = ArcRimStroke)
            drawArc(BodyMid.copy(alpha = a), start, 56f, false, topLeft, size, style = ArcStroke)
        }
    }
}

private val ARC_STARTS = floatArrayOf(152f, -28f)

/** Os "z" do sonolento: sobem da cabeça e somem, um atrás do outro. */
private fun DrawScope.drawZs(s: MascotScratch, p: MascotPose) {
    for (k in 0 until 2) {
        val fr = MascotPose.wrapPhase(p.zPhase + k * 0.5f)
        val a = sin(fr * PI.toFloat()).coerceAtLeast(0f)
        val sz = 0.026f + 0.022f * fr
        s.z.rewind()
        s.z.moveTo(-sz, -sz)
        s.z.lineTo(sz, -sz)
        s.z.lineTo(-sz, sz)
        s.z.lineTo(sz, sz)
        translate(0.10f + 0.07f * fr, -0.33f - 0.13f * fr) {
            // Contorno verde-fundo por fora e creme por dentro: a letra lê no branco e no preto.
            drawPath(s.z, Contour.copy(alpha = a), style = ZOutline)
            drawPath(s.z, Rim.copy(alpha = a), style = ZStroke)
        }
    }
}

/** Os pontinhos de quem pensa: três, entre as orelhas, que se acendem em sequência. */
private fun DrawScope.drawThinkDots(p: MascotPose) {
    for (i in 0 until 3) {
        val on = (sin((p.thinkPhase - i * 0.16f) * 2f * PI.toFloat()) * 0.5f + 0.5f)
        val r = 0.016f + 0.010f * on
        val c = Offset(-0.055f + 0.055f * i, -0.405f - 0.012f * on)
        drawCircle(Rim, radius = r + 0.009f, center = c)
        drawCircle(Terracotta.copy(alpha = 0.55f + 0.45f * on), radius = r, center = c)
    }
}
