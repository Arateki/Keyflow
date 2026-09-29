package com.keyflow.ime

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Region
import android.graphics.RectF
import android.graphics.Shader

/**
 * Tecla de vidro do Aero ([KeyboardColors.isGlass]), usada pela grade e pelas barras, sobre a lâmina
 * translúcida do teclado ([KeyboardBackdrop]) — e, através dela, sobre o que o app desenha atrás. O que faz
 * parecer o vidro do Windows Vista/7, de baixo para cima:
 *
 * - **Sombra** macia embaixo e fio escuro por fora: a tecla é uma peça de vidro sobre a lâmina.
 * - **Tinta** ([tint]) — vidro colorido —, e a luz que se junta na base.
 * - **O reflexo diagonal**: duas faixas de borda nítida que atravessam o teclado inteiro como uma só
 *   lâmina; cada tecla mostra o seu pedaço. É a assinatura do Aero ([useSheet] a posiciona).
 * - **O brilho** da metade de cima, discreto, com borda nítida, e o **aro** claro que fecha o gel.
 *
 * Todos os objetos de desenho nascem aqui; por tecla só muda a matriz dos degradês (feitos em 0..1 e
 * esticados até a tecla), então [draw] não aloca nada no `onDraw`. O reflexo é um por view (a matriz muda a
 * cada quadro): não é compartilhado.
 */
class GlassPainter(private val density: Float, private val night: Boolean) {

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = if (night) 0x99000A1A.toInt() else 0x520B3A66
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // O reflexo: um degradê diagonal com paradas duras, do tamanho da lâmina, deslocado como a tecla.
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
    private val keyShape = Path()

    /**
     * Antes das teclas de uma view: a lâmina atrás dela e onde a origem do canvas está sobre a lâmina
     * ([dx]/[dy], já descontada a rolagem). Sem lâmina (ou antes do 1º tamanho), o vidro fica sem reflexo.
     */
    fun useSheet(sheet: GlassSheet?, dx: Float, dy: Float) {
        if (sheet == null || sheet.widthPx <= 0 || sheet.heightPx <= 0) {
            sheenShader = null
            return
        }
        if (sheet.widthPx != sheenWidth || sheet.heightPx != sheenHeight || sheenShader == null) {
            sheenWidth = sheet.widthPx
            sheenHeight = sheet.heightPx
            // Um shader por view (não compartilhado): a matriz de cada uma muda a cada quadro.
            sheenShader = GlassSheen.shader(sheenWidth, sheenHeight, night)
            sheenPaint.shader = sheenShader
        }
        sheenMatrix.setTranslate(-dx, -dy)
        sheenShader?.setLocalMatrix(sheenMatrix)
    }

    fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean) {
        // Sombra macia (duas passadas) e o fio escuro em volta, SÓ FORA da tecla: ela é translúcida, então uma
        // sombra por baixo dela escureceria o vidro e daria um cinza — o que não pode acontecer.
        keyShape.rewind()
        keyShape.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.save()
        @Suppress("DEPRECATION") // clipOutPath só existe no API 26; DIFFERENCE funciona no canvas acelerado antes disso
        canvas.clipPath(keyShape, Region.Op.DIFFERENCE)
        shadowPaint.color = if (night) 0x33000814 else 0x1F06264F
        rect.set(bounds.left - density * 0.5f, bounds.top, bounds.right + density * 0.5f, bounds.bottom + density * 2.6f)
        canvas.drawRoundRect(rect, radius + density, radius + density, shadowPaint)
        shadowPaint.color = if (night) 0x4D000814 else 0x2E06264F
        rect.set(bounds.left, bounds.top + density, bounds.right, bounds.bottom + density * 1.5f)
        canvas.drawRoundRect(rect, radius, radius, shadowPaint)
        rect.set(bounds)
        rect.inset(-density, -density)
        canvas.drawRoundRect(rect, radius + density, radius + density, outlinePaint)
        canvas.restore()

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
}
