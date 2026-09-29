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
enum class KeyboardBackdrop {
    /** Frutiger Aero: vidro claro, quase transparente (e mais fosco quando o aparelho não desfoca o que está atrás). */
    AERO,

    /** Glitch: preto neutro; a falha vem das teclas e do movimento ([KeyboardMotion]), não do fundo. */
    GLITCH,

    /** Rose Gold: um fundo blush em degradê, com um fio de champanhe em cima e um brilho de seda. */
    ROSE_GOLD,
    ;

    /**
     * Faixa da barra de navegação sob o teclado: a tinta do fundo, para o teclado seguir até a borda. [blurBehind] =
     * o aparelho desfoca o que está atrás (o Aero fica mais fosco quando não).
     */
    fun navigationBarColor(blurBehind: Boolean): Int = when (this) {
        AERO -> if (blurBehind) AERO_BOTTOM else AERO_FROSTED_BOTTOM
        GLITCH -> GLITCH_BASE
        ROSE_GOLD -> ROSE_BOTTOM
    }

    fun drawable(density: Float, blurBehind: Boolean): Drawable = when (this) {
        AERO -> AeroGlassSheet(density = density, blurBehind = blurBehind)
        GLITCH -> GlitchSheet()
        ROSE_GOLD -> RoseGoldSheet(density)
    }

    /** A superfície das teclas que combina com este fundo. */
    fun surface(density: Float, blurBehind: Boolean): KeySurface = when (this) {
        AERO -> GlassPainter(density, blurBehind)
        GLITCH -> GlitchPainter(density)
        ROSE_GOLD -> RoseGoldPainter(density)
    }
}

// Vidro claro e quase transparente: um fio de azul-céu, mais denso embaixo. Nada de tinta escura (sobre o branco do
// app ela vira cinza) nem de azul carregado (esconde o app de trás).
private const val AERO_TOP = 0x1FBFE6FF
private const val AERO_BOTTOM = 0x385CB8FF

// Sem desfoque do aparelho a lâmina é mais densa: o app de trás aparece nítido, e o conteúdo dele não pode atrapalhar
// as letras das teclas.
private const val AERO_FROSTED_TOP = 0x47D8EFFF
private const val AERO_FROSTED_BOTTOM = 0x665CB8FF

private const val GLITCH_BASE = 0xFF0B0B0C.toInt()

private const val ROSE_TOP = 0xFFF8E7E2.toInt()
private const val ROSE_BOTTOM = 0xFFEECFC8.toInt()

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
    private val blurBehind: Boolean,
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
            if (blurBehind) AERO_TOP else AERO_FROSTED_TOP,
            if (blurBehind) AERO_BOTTOM else AERO_FROSTED_BOTTOM,
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
 * O fundo do Glitch: um preto neutro e liso. O Yan achou o fundo decorado (rasgos, blocos, scanlines) e as teclas
 * azuis "estourando, como um tema de alto contraste": a falha agora mora nas teclas (detalhes) e no movimento
 * ([KeyboardMotion]), e o fundo não mexe em nada.
 */
private class GlitchSheet : Drawable(), GlassSheet {

    override val widthPx: Int get() = bounds.width()
    override val heightPx: Int get() = bounds.height()

    private val basePaint = Paint().apply { color = GLITCH_BASE }

    override fun draw(canvas: Canvas) {
        if (!bounds.isEmpty) canvas.drawRect(bounds, basePaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * O fundo do Rose Gold: um blush em degradê (mais claro em cima), um fio de champanhe na borda de cima e uma faixa de
 * seda — um brilho largo e suave na diagonal, sem borda dura (o reflexo duro é coisa de vidro). Opaco e parado; o
 * brilho que se move é o [ShimmerMotion].
 */
private class RoseGoldSheet(private val density: Float) : Drawable(), GlassSheet {

    override val widthPx: Int get() = bounds.width()
    override val heightPx: Int get() = bounds.height()

    private val basePaint = Paint()
    private val silkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint().apply { color = 0xE6FFF4EC.toInt() }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return
        basePaint.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), ROSE_TOP, ROSE_BOTTOM, Shader.TileMode.CLAMP)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        // A seda: um pico suave e largo na diagonal, do canto de cima à esquerda ao de baixo à direita.
        silkPaint.shader = LinearGradient(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.left + w * 0.9f, bounds.top + h * 1.4f,
            intArrayOf(0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.28f, 0.5f, 0.74f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, basePaint)
        canvas.drawRect(b, silkPaint)
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + density, edgePaint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
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
