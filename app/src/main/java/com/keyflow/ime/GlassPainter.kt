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
 * Tecla de vidro do Aero ([KeyboardColors.isGlass]), usada pela grade e pelas barras. O que faz parecer o
 * vidro do Windows Vista/7 e não um cinza translúcido, de baixo para cima:
 *
 * - **Sombra** macia embaixo e fio escuro por fora: a tecla é uma peça de vidro sobre a paisagem.
 * - **A paisagem desfocada** exatamente do que está atrás da tecla ([useFrost]), mais viva que o original.
 * - **Tinta azul** ([tint]) — vidro colorido, não um véu branco —, e a luz que se junta na base.
 * - **O reflexo diagonal**: duas faixas de borda nítida que atravessam o teclado inteiro como uma só
 *   lâmina de vidro; cada tecla mostra o seu pedaço. É a assinatura do Aero ([useFrost] a posiciona).
 * - **O brilho** da metade de cima, discreto, com borda nítida, e o **aro** claro que fecha o gel.
 *
 * Todos os objetos de desenho nascem aqui; por tecla só muda a matriz dos degradês (feitos em 0..1 e
 * esticados até a tecla), então [draw] não aloca nada no `onDraw`. O fosco e o reflexo são um por view
 * (a matriz muda a cada quadro): não são compartilhados.
 */
class GlassPainter(private val density: Float, private val night: Boolean) {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = if (night) 0x99000A1A.toInt() else 0x520B3A66
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // O fosco: a paisagem desfocada, com mais cor e luz (a "vivacidade" do vidro), ampliada com filtro.
    private val frostPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(FROST_SATURATION)
                val lift = if (night) 3f else 8f
                postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, lift, 0f, 1f, 0f, 0f, lift, 0f, 0f, 1f, 0f, lift, 0f, 0f, 0f, 1f, 0f)))
            },
        )
    }
    private var frostBitmap: Bitmap? = null
    private var frostShader: BitmapShader? = null
    private val frostMatrix = Matrix()

    // O reflexo: um degradê diagonal com paradas duras, do tamanho da paisagem, deslocado como o fosco.
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenShader: LinearGradient? = null
    private var sheenWidth = 0
    private var sheenHeight = 0
    private val sheenMatrix = Matrix()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = unitGradient(
            if (night) intArrayOf(0x0045D8FF, 0x0045D8FF, 0x5245D8FF) else intArrayOf(0x0080E6FF, 0x0080E6FF, 0x6680E6FF),
            floatArrayOf(0f, 0.5f, 1f),
        )
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = unitGradient(
            if (night) intArrayOf(0x4DFFFFFF, 0x0DFFFFFF) else intArrayOf(0x8CFFFFFF.toInt(), 0x14FFFFFF),
            null,
        )
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        shader = unitGradient(
            if (night) intArrayOf(0xB3D8F6FF.toInt(), 0x33D8F6FF) else intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF),
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
     * ([dx]/[dy], já descontada a rolagem). Sem paisagem (ou antes do 1º tamanho), o vidro fica sem fosco
     * nem reflexo.
     */
    fun useFrost(backdrop: FrostedBackdrop?, dx: Float, dy: Float) {
        val bitmap = backdrop?.frost
        if (bitmap == null) {
            frostShader = null
            sheenShader = null
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

        if (backdrop.widthPx != sheenWidth || backdrop.heightPx != sheenHeight || sheenShader == null) {
            sheenWidth = backdrop.widthPx
            sheenHeight = backdrop.heightPx
            sheenShader = buildSheen(sheenWidth, sheenHeight)
            sheenPaint.shader = sheenShader
        }
        sheenMatrix.setTranslate(-dx, -dy)
        sheenShader?.setLocalMatrix(sheenMatrix)
    }

    fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean) {
        // Sombra macia (duas passadas, mais larga e mais fraca por fora) e o fio escuro em volta.
        shadowPaint.color = if (night) 0x33000814 else 0x1F06264F
        rect.set(bounds.left - density * 0.5f, bounds.top, bounds.right + density * 0.5f, bounds.bottom + density * 2.6f)
        canvas.drawRoundRect(rect, radius + density, radius + density, shadowPaint)
        shadowPaint.color = if (night) 0x4D000814 else 0x2E06264F
        rect.set(bounds.left, bounds.top + density, bounds.right, bounds.bottom + density * 1.5f)
        canvas.drawRoundRect(rect, radius, radius, shadowPaint)
        rect.set(bounds)
        rect.inset(-density, -density)
        canvas.drawRoundRect(rect, radius + density, radius + density, outlinePaint)

        if (frostShader != null) canvas.drawRoundRect(bounds, radius, radius, frostPaint)
        bodyPaint.color = tint
        canvas.drawRoundRect(bounds, radius, radius, bodyPaint)
        stretch(glowPaint, bounds.top, bounds.height())
        canvas.drawRoundRect(bounds, radius, radius, glowPaint)
        if (sheenShader != null) canvas.drawRoundRect(bounds, radius, radius, sheenPaint)
        if (pressed) canvas.drawRoundRect(bounds, radius, radius, pressedPaint)

        // Brilho: a metade de cima, recuada do aro — a faixa clara, de borda nítida, do gel.
        val inset = density * 1.5f
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.top + bounds.height() * 0.48f)
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

    /**
     * As duas faixas do reflexo: paradas duras num degradê que corre na diagonal (do canto de cima à esquerda
     * ao de baixo à direita), então as bordas das faixas sobem da esquerda para a direita, como no vidro do
     * Vista. A largura e a altura da paisagem fazem a faixa cruzar o teclado inteiro em qualquer tamanho.
     */
    private fun buildSheen(width: Int, height: Int): LinearGradient? {
        if (width <= 0 || height <= 0) return null
        // Direção do degradê (1, SHEEN_SLOPE): t = 1 exatamente no canto de baixo à direita.
        val lambda = (width + SHEEN_SLOPE * height) / (1f + SHEEN_SLOPE * SHEEN_SLOPE)
        val wide = if (night) 0x2EFFFFFF else 0x59FFFFFF
        val thin = if (night) 0x1AFFFFFF else 0x38FFFFFF
        val colors = intArrayOf(0, 0, wide, wide, 0, 0, thin, thin, 0, 0)
        val stops = floatArrayOf(0f, 0.13f, 0.136f, 0.30f, 0.306f, 0.335f, 0.341f, 0.38f, 0.386f, 1f)
        return LinearGradient(0f, 0f, lambda, lambda * SHEEN_SLOPE, colors, stops, Shader.TileMode.CLAMP)
    }

    private companion object {
        const val FROST_SATURATION = 1.5f
        const val SHEEN_SLOPE = 1.6f
    }
}
