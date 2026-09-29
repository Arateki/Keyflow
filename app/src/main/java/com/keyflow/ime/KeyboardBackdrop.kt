package com.keyflow.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * O que o teclado pinta atrás de barras e grade numa skin com superfície própria ([KeyboardColors.backdrop]).
 *
 * [AERO] é uma lâmina de vidro **translúcida de verdade**: o que o app desenha atrás dele aparece através
 * (Yan, 2026-09-29: "prefiro o efeito de vidro real ao que está ao fundo"). Ela só carrega a cor do vidro — uma
 * tinta clara em degradê —, o fio de luz da borda de cima e o reflexo diagonal; o fosco é do compositor
 * ([windowBlurAvailable]), onde o aparelho oferece. Uma aparência só, no tema claro e no escuro (pedido do Yan).
 */
enum class KeyboardBackdrop(
    /** Faixa da barra de navegação sob o teclado: a tinta do fundo, para o teclado seguir até a borda. */
    val navigationBarColor: Int,
) {
    /** Frutiger Aero: vidro claro, quase transparente. */
    AERO(AERO_BOTTOM),

    /** Glitch: preto com scanlines e rasgos de ciano e vermelho — opaco, sem ver o app atrás. */
    GLITCH(0xFF000000.toInt()),
    ;

    fun drawable(density: Float): Drawable = when (this) {
        AERO -> AeroGlassSheet(density = density)
        GLITCH -> GlitchSheet(density = density)
    }

    /** A superfície das teclas que combina com este fundo. */
    fun surface(density: Float): KeySurface = when (this) {
        AERO -> GlassPainter(density)
        GLITCH -> GlitchPainter(density)
    }
}

// Vidro claro e quase transparente: um fio de azul-céu, mais denso embaixo. Nada de tinta escura (sobre o branco do
// app ela vira cinza) nem de azul carregado (esconde o app de trás).
private const val AERO_TOP = 0x1FBFE6FF
private const val AERO_BOTTOM = 0x385CB8FF

/**
 * A lâmina de vidro que as teclas conhecem: o tamanho dela é o que estende o reflexo diagonal por todo o
 * teclado ([GlassPainter.useSheet]), de modo que cada tecla mostre o seu pedaço da mesma faixa de luz.
 */
interface GlassSheet {
    val widthPx: Int
    val heightPx: Int
}

/**
 * Acha a lâmina atrás de [view] (o fundo de um ancestral) e onde [view] está sobre ela — em cada `onDraw`,
 * sem alocar.
 */
internal class GlassSheetLocator(private val view: View) {
    private val viewInWindow = IntArray(2)
    private val hostInWindow = IntArray(2)
    var dx = 0f
        private set
    var dy = 0f
        private set

    fun locate(): GlassSheet? {
        var parent = view.parent
        while (parent is View) {
            val sheet = parent.background
            if (sheet is GlassSheet) {
                view.getLocationInWindow(viewInWindow)
                parent.getLocationInWindow(hostInWindow)
                dx = (viewInWindow[0] - hostInWindow[0]).toFloat()
                dy = (viewInWindow[1] - hostInWindow[1]).toFloat()
                return sheet
            }
            parent = parent.parent
        }
        return null
    }
}

/**
 * O aparelho desfoca o que está atrás de uma janela (Android 12+, e só onde o fabricante liga)? Sem isso o
 * vidro fica claro, sem fosco — o app aparece nítido através dele. O A32 do Yan (One UI 5.1) não desfoca.
 */
fun windowBlurAvailable(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.isCrossWindowBlurEnabled == true

/**
 * A lâmina: tinta clara em degradê (mais densa embaixo), fio de luz na borda de cima e o reflexo diagonal, mais
 * fraco que o das teclas. Os degradês dependem do tamanho, então são montados em [onBoundsChange]; [draw]
 * só pinta. Nada animado.
 */
private class AeroGlassSheet(
    private val density: Float,
) : Drawable(), GlassSheet {

    override val widthPx: Int get() = bounds.width()
    override val heightPx: Int get() = bounds.height()

    private val tintPaint = Paint()
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint().apply { color = 0xBFFFFFFF.toInt() }
    private val sheenMatrix = Matrix()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return
        tintPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            AERO_TOP,
            AERO_BOTTOM,
            Shader.TileMode.CLAMP,
        )
        val sheen = GlassSheen.shader(bounds.width(), bounds.height(), strength = SHEET_SHEEN)
        sheen?.setLocalMatrix(sheenMatrix.apply { setTranslate(bounds.left.toFloat(), bounds.top.toFloat()) })
        sheenPaint.shader = sheen
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, tintPaint)
        if (sheenPaint.shader != null) canvas.drawRect(b, sheenPaint)
        // O fio de luz da borda de cima: onde a lâmina "pega" a luz.
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + density, edgePaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** O reflexo da lâmina é mais discreto que o das teclas, que ficam por cima dele. */
        const val SHEET_SHEEN = 0.55f
    }
}

/**
 * O fundo do Glitch: preto, com scanlines (uma linha clara a cada poucos dp, em azulejo repetido) e rasgos
 * horizontais de ciano e vermelho, cada um com um gêmeo deslocado e mais fraco. Posições fixas: nada animado, e o
 * mesmo teclado dá o mesmo quadro. Só aparece nos vãos entre as teclas e nas barras — as teclas têm face própria.
 */
private class GlitchSheet(private val density: Float) : Drawable(), GlassSheet {

    override val widthPx: Int get() = bounds.width()
    override val heightPx: Int get() = bounds.height()

    private val basePaint = Paint().apply { color = 0xFF050505.toInt() }
    private val scanPaint = Paint()
    private val barPaint = Paint()

    init {
        // Um pixel de largura, o período da scanline de altura: só a 1ª linha é clara.
        val period = maxOf(3, (SCAN_PERIOD_DP * density).toInt())
        val tile = android.graphics.Bitmap.createBitmap(1, period, android.graphics.Bitmap.Config.ARGB_8888)
        tile.setPixel(0, 0, 0x14FFFFFF)
        scanPaint.shader = android.graphics.BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        canvas.drawRect(b, basePaint)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRect(0f, 0f, w, h, scanPaint)
        for (tear in TEARS) {
            val y = h * tear.y
            val x = w * tear.x
            val bw = w * tear.w
            val bh = tear.h * density
            barPaint.color = if (tear.cyan) CYAN_MAIN else RED_MAIN
            canvas.drawRect(x, y, x + bw, y + bh, barPaint)
            // O gêmeo: outra cor, mais fraco, deslocado para o lado e para baixo, como o canal que não alinhou.
            barPaint.color = if (tear.cyan) RED_TWIN else CYAN_TWIN
            canvas.drawRect(x + 3f * density, y + 1.6f * density, x + bw * 0.8f + 3f * density, y + 1.6f * density + bh, barPaint)
            barPaint.color = WHITE_TICK
            canvas.drawRect(x, y, x + bw * 0.12f, y + maxOf(1f, 0.8f * density), barPaint)
        }
        for (block in BLOCKS) {
            barPaint.color = block.color
            val bx = w * block.x
            val by = h * block.y
            canvas.drawRect(bx, by, bx + block.w * density, by + block.h * density, barPaint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private class Tear(val y: Float, val h: Float, val x: Float, val w: Float, val cyan: Boolean)
    private class Block(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int)

    private companion object {
        const val SCAN_PERIOD_DP = 3f
        const val CYAN_MAIN = 0x8C25F4EE.toInt()
        const val RED_MAIN = 0x8CFE2C55.toInt()
        const val CYAN_TWIN = 0x5925F4EE
        const val RED_TWIN = 0x59FE2C55
        const val WHITE_TICK = 0x8CFFFFFF.toInt()
        val TEARS = listOf(
            Tear(0.08f, 2.2f, 0.08f, 0.30f, true),
            Tear(0.19f, 1.2f, 0.52f, 0.42f, false),
            Tear(0.31f, 2.6f, 0.03f, 0.20f, false),
            Tear(0.44f, 1.4f, 0.60f, 0.32f, true),
            Tear(0.58f, 2.0f, 0.18f, 0.34f, false),
            Tear(0.71f, 1.2f, 0.68f, 0.26f, true),
            Tear(0.83f, 2.4f, 0.02f, 0.38f, true),
            Tear(0.94f, 1.6f, 0.45f, 0.50f, false),
        )
        val BLOCKS = listOf(
            Block(0.86f, 0.12f, 9f, 4f, 0x8C25F4EE.toInt()),
            Block(0.30f, 0.27f, 6f, 3f, 0x8CFE2C55.toInt()),
            Block(0.93f, 0.40f, 7f, 3f, 0x8CFFFFFF.toInt()),
            Block(0.12f, 0.52f, 8f, 3f, 0x8C25F4EE.toInt()),
            Block(0.74f, 0.66f, 6f, 4f, 0x8CFE2C55.toInt()),
            Block(0.40f, 0.88f, 9f, 3f, 0x8C25F4EE.toInt()),
            Block(0.97f, 0.79f, 5f, 3f, 0x8CFE2C55.toInt()),
        )
    }
}

/**
 * As duas faixas do reflexo, do vidro do Windows Vista: paradas duras num degradê que corre na diagonal
 * (do canto de cima à esquerda ao de baixo à direita), então as bordas das faixas sobem da esquerda para a
 * direita. A largura e a altura fazem a faixa cruzar o teclado inteiro em qualquer tamanho. O mesmo degradê
 * serve à lâmina e às teclas ([GlassPainter]), então a luz é contínua entre os dois.
 */
internal object GlassSheen {
    private const val SLOPE = 1.6f

    fun shader(width: Int, height: Int, strength: Float = 1f): LinearGradient? {
        if (width <= 0 || height <= 0) return null
        // Direção do degradê (1, SLOPE): t = 1 exatamente no canto de baixo à direita.
        val lambda = (width + SLOPE * height) / (1f + SLOPE * SLOPE)
        val wide = withAlpha(0x47, strength)
        val thin = withAlpha(0x2B, strength)
        val colors = intArrayOf(0, 0, wide, wide, 0, 0, thin, thin, 0, 0)
        val stops = floatArrayOf(0f, 0.13f, 0.136f, 0.30f, 0.306f, 0.335f, 0.341f, 0.38f, 0.386f, 1f)
        return LinearGradient(0f, 0f, lambda, lambda * SLOPE, colors, stops, Shader.TileMode.CLAMP)
    }

    private fun withAlpha(alpha: Int, strength: Float): Int = ((alpha * strength).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
}
