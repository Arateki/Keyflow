package com.keyflow.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.ceil

/**
 * Paisagem que o próprio teclado pinta atrás de barras e grade, para as teclas de vidro terem o que
 * mostrar por trás ([KeyboardColors.backdrop]). Desenhada por código, não imagem: acompanha qualquer
 * altura do teclado (escala, fileira de números, barras extras, retraído) sem distorcer e quase não pesa.
 */
enum class KeyboardBackdrop(
    /** Faixa da barra de navegação sob o teclado: a base da colina, para parecer o chão continuando. */
    val navigationBarColor: Int,
) {
    /** Frutiger Aero de dia: céu azul, sol, faixas de luz, colina verde e bolhas. */
    AERO_DAY(DAY_GROUND),

    /** Frutiger Aero à noite: céu marinho, aurora verde-água, colina escura e bolhas. */
    AERO_NIGHT(NIGHT_GROUND),
    ;

    fun drawable(density: Float): Drawable = AeroBackdropDrawable(night = this == AERO_NIGHT, density = density)
}

private const val DAY_GROUND = 0xFF38A83C.toInt()
private const val NIGHT_GROUND = 0xFF021A22.toInt()

/**
 * Os degradês dependem do tamanho, então são montados em [onBoundsChange] (só quando o teclado muda de
 * tamanho); [draw] só pinta. A cena é fixa — nenhuma animação, nada de relógio.
 */
/**
 * Paisagem que também oferece uma cópia desfocada de si — o "fosco" que o vidro das teclas mostra por
 * trás ([GlassPainter.useFrost]), como o vidro do Windows Vista/7 desfoca o que está atrás dele.
 */
interface FrostedBackdrop {
    /** A paisagem desfocada, reduzida ([frostDownscale] px da view por px do bitmap); null sem tamanho. */
    val frost: Bitmap?
    val frostDownscale: Float

    /** Tamanho da paisagem em px (0 sem tamanho): o reflexo do vidro ([GlassPainter]) se estende por ela toda. */
    val widthPx: Int
    val heightPx: Int
}

/**
 * Acha a paisagem atrás de [view] (o fundo de um ancestral) e onde [view] está sobre ela — em cada
 * `onDraw`, sem alocar.
 */
internal class FrostLocator(private val view: View) {
    private val viewInWindow = IntArray(2)
    private val hostInWindow = IntArray(2)
    var dx = 0f
        private set
    var dy = 0f
        private set

    fun locate(): FrostedBackdrop? {
        var parent = view.parent
        while (parent is View) {
            val backdrop = parent.background
            if (backdrop is FrostedBackdrop) {
                view.getLocationInWindow(viewInWindow)
                parent.getLocationInWindow(hostInWindow)
                dx = (viewInWindow[0] - hostInWindow[0]).toFloat()
                dy = (viewInWindow[1] - hostInWindow[1]).toFloat()
                return backdrop
            }
            parent = parent.parent
        }
        return null
    }
}

private class AeroBackdropDrawable(
    private val night: Boolean,
    private val density: Float,
) : Drawable(), FrostedBackdrop {

    override var frost: Bitmap? = null
        private set
    override val frostDownscale: Float = FROST_DOWNSCALE
    override val widthPx: Int get() = bounds.width()
    override val heightPx: Int get() = bounds.height()

    private val skyPaint = Paint()
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swooshFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swooshEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val farHillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hillRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bubbleShinePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // As faixas: a fita preenchida e o fio de luz da borda de cima. Os raios saem do sol.
    private val swooshes = listOf(Path(), Path())
    private val swooshEdges = listOf(Path(), Path())
    private var swooshShaders: List<Shader> = emptyList()
    private val rays = List(RAY_COUNT) { Path() }
    private var cloudShaders: List<Shader> = emptyList()
    private val farHill = Path()
    private val hill = Path()
    private val hillRim = Path()
    private val shine = RectF()
    private var bubbleShaders: List<Shader> = emptyList()

    // Retraído (só as barras): a colina vira uma tira e as bolhas ficam maiores que os botões — fica só
    // o céu com as faixas de luz.
    private var compact = false

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val top = bounds.top.toFloat()
        val left = bounds.left.toFloat()

        skyPaint.shader = LinearGradient(
            0f, top, 0f, top + h,
            if (night) NIGHT_SKY else DAY_SKY,
            SKY_STOPS,
            Shader.TileMode.CLAMP,
        )
        sunPaint.shader = RadialGradient(
            left + w * SUN_X, top + h * SUN_Y, w * 0.55f,
            if (night) intArrayOf(0x3345D8FF, 0x0045D8FF) else intArrayOf(0xB3FFFFFF.toInt(), 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP,
        )

        // Raios do sol (dia) ou feixes de luar (noite): cunhas de borda nítida que saem do canto de cima e
        // cruzam o teclado. As bordas duras são o que o desfoque das teclas apaga — e o que faz o vidro aparecer.
        val sunX = left + w * SUN_X
        val sunY = top + h * SUN_Y
        val reach = maxOf(w, h) * 1.7f
        rays.forEachIndexed { i, path ->
            val angle = Math.toRadians(RAY_ANGLES[i].toDouble())
            val half = Math.toRadians(RAY_HALF_WIDTHS[i].toDouble())
            path.apply {
                reset()
                moveTo(sunX, sunY)
                lineTo(sunX + (Math.cos(angle - half) * reach).toFloat(), sunY + (Math.sin(angle - half) * reach).toFloat())
                lineTo(sunX + (Math.cos(angle + half) * reach).toFloat(), sunY + (Math.sin(angle + half) * reach).toFloat())
                close()
            }
        }
        rayPaint.shader = RadialGradient(
            sunX, sunY, reach * 0.75f,
            if (night) intArrayOf(0x4045D8FF, 0x0045D8FF) else intArrayOf(0x66FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP,
        )

        // Nuvens: círculos macios sobrepostos, brancos no dia e só um vapor azul à noite.
        cloudShaders = CLOUDS.flatMap { cloud ->
            cloud.puffs.map { (_, _, rDp) ->
                RadialGradient(
                    0f, 0f, rDp * density,
                    if (night) intArrayOf(0x2645D8FF, 0x0045D8FF) else intArrayOf(0xE6FFFFFF.toInt(), 0x00FFFFFF),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
        }

        // As faixas do Windows 7 (dia) ou a aurora do Vista (noite): fitas que afinam nas pontas, com um fio de
        // luz na borda de cima.
        swooshes[0].apply {
            reset()
            moveTo(left - w * 0.1f, top + h * 0.72f)
            cubicTo(left + w * 0.25f, top + h * 0.42f, left + w * 0.55f, top + h * 0.72f, left + w * 1.1f, top + h * 0.20f)
            lineTo(left + w * 1.1f, top + h * 0.32f)
            cubicTo(left + w * 0.6f, top + h * 0.90f, left + w * 0.28f, top + h * 0.60f, left - w * 0.1f, top + h * 0.92f)
            close()
        }
        swooshEdges[0].apply {
            reset()
            moveTo(left - w * 0.1f, top + h * 0.72f)
            cubicTo(left + w * 0.25f, top + h * 0.42f, left + w * 0.55f, top + h * 0.72f, left + w * 1.1f, top + h * 0.20f)
        }
        swooshes[1].apply {
            reset()
            moveTo(left - w * 0.1f, top + h * 0.34f)
            cubicTo(left + w * 0.3f, top + h * 0.04f, left + w * 0.65f, top + h * 0.42f, left + w * 1.1f, top + h * 0.08f)
            lineTo(left + w * 1.1f, top + h * 0.15f)
            cubicTo(left + w * 0.68f, top + h * 0.52f, left + w * 0.32f, top + h * 0.15f, left - w * 0.1f, top + h * 0.44f)
            close()
        }
        swooshEdges[1].apply {
            reset()
            moveTo(left - w * 0.1f, top + h * 0.34f)
            cubicTo(left + w * 0.3f, top + h * 0.04f, left + w * 0.65f, top + h * 0.42f, left + w * 1.1f, top + h * 0.08f)
        }
        swooshShaders = listOf(
            LinearGradient(
                left, 0f, left + w, 0f,
                if (night) intArrayOf(0x264DFFB8, 0x594DFFB8, 0x1F4DFFB8) else intArrayOf(0x40C8F1FF, 0x80FFFFFF.toInt(), 0x33C8F1FF),
                null,
                Shader.TileMode.CLAMP,
            ),
            LinearGradient(
                left, 0f, left + w, 0f,
                if (night) intArrayOf(0x2645D8FF, 0x5945D8FF, 0x1F45D8FF) else intArrayOf(0x33FFFFFF, 0x66E4F8FF, 0x33FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            ),
        )
        swooshEdgePaint.strokeWidth = density * 1.4f
        swooshEdgePaint.color = if (night) 0x8CAAFFEE.toInt() else 0xCCFFFFFF.toInt()

        // Colina ao fundo, mais clara, e a da frente com o brilho na crista.
        farHill.apply {
            reset()
            moveTo(left, top + h * 0.80f)
            cubicTo(left + w * 0.35f, top + h * 0.70f, left + w * 0.7f, top + h * 0.84f, left + w, top + h * 0.74f)
            lineTo(left + w, top + h)
            lineTo(left, top + h)
            close()
        }
        // A crista é a mesma curva no contorno da colina e no traço de brilho por cima dela.
        for (path in listOf(hillRim, hill)) {
            path.reset()
            path.moveTo(left, top + h * 0.90f)
            path.cubicTo(left + w * 0.3f, top + h * 0.78f, left + w * 0.62f, top + h * 0.86f, left + w, top + h * 0.80f)
        }
        hill.apply {
            lineTo(left + w, top + h)
            lineTo(left, top + h)
            close()
        }
        farHillPaint.shader = LinearGradient(
            0f, top + h * 0.70f, 0f, top + h,
            if (night) intArrayOf(0xFF0A4556.toInt(), 0xFF062C38.toInt()) else intArrayOf(0xFFA6E4B4.toInt(), 0xFF6CC7A0.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        hillPaint.shader = LinearGradient(
            0f, top + h * 0.78f, 0f, top + h,
            if (night) intArrayOf(0xFF07303C.toInt(), NIGHT_GROUND) else intArrayOf(0xFF97E55E.toInt(), DAY_GROUND),
            null,
            Shader.TileMode.CLAMP,
        )
        hillRimPaint.strokeWidth = density * 1.5f
        hillRimPaint.color = if (night) 0x6645D8FF else 0xB3FFFFFF.toInt()

        bubbleShaders = BUBBLES.map { (fx, fy, rDp) ->
            val r = rDp * density
            RadialGradient(
                left + w * fx, top + h * fy, r,
                if (night) {
                    intArrayOf(0x0345D8FF, 0x0D45D8FF, 0x5245D8FF)
                } else {
                    intArrayOf(0x08FFFFFF, 0x12FFFFFF, 0x6BFFFFFF)
                },
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        bubbleRimPaint.strokeWidth = density
        bubbleRimPaint.color = if (night) 0x5945D8FF else 0x8CFFFFFF.toInt()
        bubbleShinePaint.color = if (night) 0x73D8F6FF else 0xA6FFFFFF.toInt()
        compact = h < COMPACT_HEIGHT_DP * density
        frost = buildFrost(bounds)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val left = b.left.toFloat()
        val top = b.top.toFloat()

        canvas.drawRect(b, skyPaint)
        canvas.drawRect(b, sunPaint)
        rays.forEach { canvas.drawPath(it, rayPaint) }
        if (!compact) drawClouds(canvas, left, top, w, h)
        drawSwooshes(canvas)
        if (compact) return
        canvas.drawPath(farHill, farHillPaint)
        canvas.drawPath(hill, hillPaint)
        canvas.drawPath(hillRim, hillRimPaint)
        BUBBLES.forEachIndexed { i, (fx, fy, rDp) ->
            val cx = left + w * fx
            val cy = top + h * fy
            val r = rDp * density
            bubbleFillPaint.shader = bubbleShaders.getOrNull(i) ?: return@forEachIndexed
            canvas.drawCircle(cx, cy, r, bubbleFillPaint)
            canvas.drawCircle(cx, cy, r, bubbleRimPaint)
            shine.set(cx - r * 0.62f, cy - r * 0.66f, cx - r * 0.08f, cy - r * 0.32f)
            canvas.drawOval(shine, bubbleShinePaint)
        }
    }

    /**
     * A cena desenhada em 1/[FROST_DOWNSCALE] e desfocada (caixa em 2 passadas, que já lembra gaussiana).
     * Pequena de propósito: ampliada pelo filtro bilinear na hora de desenhar, o desfoque fica macio e o
     * bitmap custa alguns KB. Só roda quando o teclado muda de tamanho.
     */
    private fun buildFrost(bounds: Rect): Bitmap {
        val w = ceil(bounds.width() / FROST_DOWNSCALE).toInt().coerceAtLeast(1)
        val h = ceil(bounds.height() / FROST_DOWNSCALE).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            scale(1f / FROST_DOWNSCALE, 1f / FROST_DOWNSCALE)
            translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            draw(this)
        }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(FROST_PASSES) { boxBlur(pixels, w, h, FROST_RADIUS) }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    private fun drawClouds(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        var shaderIndex = 0
        CLOUDS.forEach { cloud ->
            cloud.puffs.forEach { (dxDp, dyDp, rDp) ->
                val shader = cloudShaders.getOrNull(shaderIndex++) ?: return@forEach
                val cx = left + w * cloud.x + dxDp * density
                val cy = top + h * cloud.y + dyDp * density
                canvas.save()
                canvas.translate(cx, cy)
                cloudPaint.shader = shader
                canvas.drawCircle(0f, 0f, rDp * density, cloudPaint)
                canvas.restore()
            }
        }
    }

    private fun drawSwooshes(canvas: Canvas) {
        swooshes.forEachIndexed { i, path ->
            swooshFillPaint.shader = swooshShaders.getOrNull(i)
            canvas.drawPath(path, swooshFillPaint)
            canvas.drawPath(swooshEdges[i], swooshEdgePaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val COMPACT_HEIGHT_DP = 140f
        const val FROST_DOWNSCALE = 8f
        const val FROST_RADIUS = 2
        const val FROST_PASSES = 2
        val SKY_STOPS = floatArrayOf(0f, 0.38f, 0.72f, 1f)
        val DAY_SKY = intArrayOf(0xFF1C7BD6.toInt(), 0xFF48B0F0.toInt(), 0xFFA5E0FB.toInt(), 0xFFDDF6FF.toInt())
        val NIGHT_SKY = intArrayOf(0xFF030A22.toInt(), 0xFF062652.toInt(), 0xFF0A4A70.toInt(), 0xFF0B6474.toInt())

        /** O sol (ou a lua): canto de cima à direita, de onde saem os raios. */
        const val SUN_X = 0.84f
        const val SUN_Y = 0.06f

        /** Raios: direção (graus, 0 = direita, 90 = para baixo) e meia abertura de cada cunha. */
        const val RAY_COUNT = 5
        val RAY_ANGLES = floatArrayOf(112f, 132f, 152f, 171f, 190f)
        val RAY_HALF_WIDTHS = floatArrayOf(4.5f, 3f, 5.5f, 3.2f, 4f)

        /** Nuvem: centro (fração do teclado) e os círculos (dx e dy em dp, raio em dp) que a formam. */
        class Cloud(val x: Float, val y: Float, val puffs: List<Triple<Float, Float, Float>>)

        val CLOUDS = listOf(
            Cloud(0.20f, 0.30f, listOf(Triple(-26f, 4f, 22f), Triple(0f, -6f, 30f), Triple(28f, 2f, 24f), Triple(6f, 10f, 26f))),
            Cloud(0.58f, 0.14f, listOf(Triple(-20f, 2f, 18f), Triple(2f, -6f, 24f), Triple(24f, 4f, 20f))),
            Cloud(0.93f, 0.52f, listOf(Triple(-18f, 0f, 20f), Triple(6f, -8f, 26f), Triple(24f, 6f, 20f))),
        )

        /** (x e y como fração do teclado, raio em dp) — posições fixas, a cena não sorteia nada. */
        val BUBBLES = listOf(
            Triple(0.08f, 0.24f, 16f),
            Triple(0.30f, 0.60f, 9f),
            Triple(0.55f, 0.42f, 20f),
            Triple(0.80f, 0.74f, 11f),
            Triple(0.90f, 0.28f, 13f),
            Triple(0.42f, 0.88f, 8f),
        )
    }
}

/** Desfoque de caixa separável (horizontal, depois vertical) sobre ARGB empacotado, com as bordas repetidas. */
private fun boxBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
    val tmp = IntArray(pixels.size)
    blurLine(pixels, tmp, w, h, radius, horizontal = true)
    blurLine(tmp, pixels, w, h, radius, horizontal = false)
}

private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val window = radius * 2 + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, length - 1)
            return if (horizontal) src[line * w + c] else src[c * w + line]
        }
        var a = 0; var r = 0; var g = 0; var b = 0
        for (i in -radius..radius) {
            val p = at(i)
            a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        for (i in 0 until length) {
            val out = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            val add = at(i + radius + 1)
            val drop = at(i - radius)
            a += (add ushr 24) - (drop ushr 24)
            r += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
            g += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
            b += (add and 0xFF) - (drop and 0xFF)
        }
    }
}
