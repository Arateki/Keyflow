package com.keyflow.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/**
 * Tecla do tema Glitch (o efeito de falha gráfica da logo do TikTok): preto, branco, vermelho e azul. A face é
 * escura e a borda é desenhada em **três canais** — ciano deslocado para a esquerda, vermelho para a direita e o
 * branco no meio —, como a aberração cromática de uma tela com defeito; onde ciano e vermelho se cruzam, somam
 * (ADD) e viram branco. Cerca de uma tecla em cinco leva ainda uma "rasgadura": uma faixa horizontal de cor que
 * vaza da face, em posição estável (vem da posição da tecla, não do relógio). O rótulo é desenhado em três
 * passadas com o mesmo deslocamento ([labelPasses]). Pressionada, a tecla inverte: face branca, letra preta.
 */
class GlitchPainter(private val density: Float) : KeySurface {

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.1f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val overPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.6f
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val slicePaint = Paint()
    private val rect = RectF()

    private val split = density * SPLIT_DP

    override fun draw(canvas: Canvas, bounds: RectF, radius: Float, tint: Int, pressed: Boolean) {
        facePaint.color = if (pressed) WHITE else tint
        canvas.drawRoundRect(bounds, radius, radius, facePaint)

        val h = hash(bounds)
        if (!pressed && h % SLICE_ONE_IN == 0) drawSlice(canvas, bounds, h)

        // A borda em três canais. Pressionada, sobre a face branca, os canais somados sumiriam: vão por cima, normais.
        val width = if (pressed) density * 1.6f else density * 1.1f
        val half = width / 2f
        rect.set(bounds)
        rect.inset(half, half)
        val channel = if (pressed) overPaint else addPaint
        channel.strokeWidth = width
        channel.color = CYAN
        canvas.save()
        canvas.translate(-split, 0f)
        canvas.drawRoundRect(rect, radius - half, radius - half, channel)
        canvas.restore()
        channel.color = RED
        canvas.save()
        canvas.translate(split, 0f)
        canvas.drawRoundRect(rect, radius - half, radius - half, channel)
        canvas.restore()
        edgePaint.strokeWidth = width
        edgePaint.color = if (pressed) BLACK else WHITE
        canvas.drawRoundRect(rect, radius - half, radius - half, edgePaint)
    }

    /** A rasgadura: uma faixa de cor que vaza um pouco da face, com um fio branco logo abaixo, deslocado. */
    private fun drawSlice(canvas: Canvas, bounds: RectF, h: Int) {
        val y = bounds.top + bounds.height() * (0.22f + 0.5f * ((h ushr 3) % 100) / 100f)
        val thick = density * (1.2f + ((h ushr 9) % 3) * 0.6f)
        val shift = density * (2.5f + (h ushr 11) % 4)
        slicePaint.color = if ((h ushr 2) % 2 == 0) CYAN else RED
        canvas.drawRect(bounds.left - shift * 0.5f, y, bounds.right + shift, y + thick, slicePaint)
        slicePaint.color = WHITE
        canvas.drawRect(bounds.left + shift, y + thick, bounds.right - shift * 0.5f, y + thick + density * 0.7f, slicePaint)
    }

    override val labelPasses: Int get() = 3

    override fun labelOffsetX(pass: Int): Float = when (pass) {
        0 -> -split
        1 -> split
        else -> 0f
    }

    override fun labelColor(pass: Int, ink: Int, pressed: Boolean): Int = when (pass) {
        0 -> CYAN
        1 -> RED
        else -> if (pressed) BLACK else ink
    }

    /** Número estável a partir da posição da tecla: a mesma tecla leva sempre a mesma falha. */
    private fun hash(bounds: RectF): Int =
        ((bounds.left * 31f + bounds.top * 17f).toInt() * -0x61c88647) ushr 8 and 0x7FFFFFFF

    companion object {
        const val CYAN = 0xFF25F4EE.toInt()
        const val RED = 0xFFFE2C55.toInt()
        const val BLUE = 0xFF2250FF.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val SPLIT_DP = 1.1f
        private const val SLICE_ONE_IN = 5
    }
}
