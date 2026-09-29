package com.keyflow.ime

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * Tecla de vidro do Aero ([KeyboardColors.isGlass]), usada pela grade e pelas barras. De baixo para cima:
 * a paisagem desfocada exatamente do que está atrás da tecla ([useFrost] — é isso que faz parecer vidro
 * e não um cinza translúcido), um véu leve na cor pedida, o clarão na base, o brilho na metade de cima e
 * o aro claro — o "gel" do Windows Vista/7. Um fio escuro por fora separa a tecla do céu claro.
 *
 * Todos os objetos de desenho nascem aqui; por tecla só muda a matriz dos degradês (feitos em 0..1 e
 * esticados até a tecla), então [draw] não aloca nada no `onDraw`.
 */
class GlassPainter(private val density: Float, private val night: Boolean) {

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = if (night) 0x80000A1A.toInt() else 0x380B3A66
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // O fosco: a paisagem desfocada, com mais cor e luz (a "vivacidade" do vidro), ampliada com filtro.
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(FROST_SATURATION)
                val lift = if (night) 4f else 16f
                postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, lift, 0f, 1f, 0f, 0f, lift, 0f, 0f, 1f, 0f, lift, 0f, 0f, 0f, 1f, 0f)))
            },
        )
    }
    private var frostBitmap: Bitmap? = null
    private var frostShader: BitmapShader? = null
    private val frostMatrix = Matrix()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = unitGradient(intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x47FFFFFF), floatArrayOf(0f, 0.55f, 1f))
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = unitGradient(
            if (night) intArrayOf(0x66FFFFFF, 0x14FFFFFF) else intArrayOf(0xC7FFFFFF.toInt(), 0x2EFFFFFF),
            null,
        )
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        shader = unitGradient(
            if (night) intArrayOf(0xA6D8F6FF.toInt(), 0x33D8F6FF) else intArrayOf(0xF2FFFFFF.toInt(), 0x66FFFFFF),
            null,
        )
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x6B18C4FF }
    private val pressedRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = 0xFF8AEBFF.toInt()
    }

    private val matrix = Matrix()
    private val rect = RectF()

    /**
     * Antes das teclas de uma view: a paisagem atrás dela e onde a origem do canvas está sobre a paisagem
     * ([dx]/[dy], já descontada a rolagem). Sem paisagem (ou antes do 1º tamanho), o vidro fica sem fosco.
     */
    fun useFrost(backdrop: FrostedBackdrop?, dx: Float, dy: Float) {
        val bitmap = backdrop?.frost
        if (bitmap == null) {
            frostShader = null
            return
        }
        if (bitmap !== frostBitmap) {
            frostBitmap = bitmap
            // Um shader por view (não compartilhado): a matriz de cada uma muda a cada quadro.
            frostShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            frostPaint.shader = frostShader
        }
        frostMatrix.setScale(backdrop.frostDownscale, backdrop.frostDownscale)
        frostMatrix.postTranslate(-dx, -dy)
        frostShader?.setLocalMatrix(frostMatrix)
    }

    fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean) {
        rect.set(bounds)
        rect.inset(-density, -density)
        canvas.drawRoundRect(rect, radius + density, radius + density, outlinePaint)

        if (frostShader != null) canvas.drawRoundRect(bounds, radius, radius, frostPaint)
        bodyPaint.color = tint
        canvas.drawRoundRect(bounds, radius, radius, bodyPaint)
        stretch(glowPaint, bounds.top, bounds.height())
        canvas.drawRoundRect(bounds, radius, radius, glowPaint)
        if (pressed) canvas.drawRoundRect(bounds, radius, radius, pressedPaint)

        // Brilho: a metade de cima, recuada do aro — a faixa clara que dá a cara de gel.
        val inset = density * 1.5f
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.top + bounds.height() * 0.5f)
        val glossRadius = (radius - inset).coerceAtLeast(0f)
        stretch(glossPaint, rect.top, rect.height())
        canvas.drawRoundRect(rect, glossRadius, glossRadius, glossPaint)

        val rim = if (pressed) pressedRimPaint else rimPaint
        val half = rim.strokeWidth / 2f
        rect.set(bounds)
        rect.inset(half, half)
        stretch(rimPaint, bounds.top, bounds.height())
        canvas.drawRoundRect(rect, radius - half, radius - half, rim)
    }

    private fun stretch(paint: Paint, top: Float, height: Float) {
        matrix.setScale(1f, height.coerceAtLeast(1f))
        matrix.postTranslate(0f, top)
        paint.shader?.setLocalMatrix(matrix)
    }

    private fun unitGradient(colors: IntArray, stops: FloatArray?): Shader =
        LinearGradient(0f, 0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)

    private companion object {
        const val FROST_SATURATION = 1.35f
    }
}
