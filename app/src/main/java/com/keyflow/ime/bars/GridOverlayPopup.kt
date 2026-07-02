package com.keyflow.ime.bars

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.PopupWindow
import com.keyflow.core.shortcuts.ShortcutGrid

/**
 * Rendering da grade efêmera (GRD-02/06/07): `PopupWindow` **não-tocável** (o gesto pertence ao gatilho
 * na `BarView` — padrão provado pelo seletor do mic) cobrindo o retângulo da grade + uma faixa acima
 * para o preview do dedo. A grade em si é invisível: só a **lente** (célula atual + vizinhas imediatas)
 * e o **preview acima do ponto de toque**; com o dedo parado, o modo aprendizado revela tudo
 * semi-transparente.
 */
class GridOverlayPopup(
    private val context: Context,
    dark: Boolean,
    private val grid: ShortcutGrid,
    private val cellWidthPx: Float,
    private val cellHeightPx: Float,
    private val scale: Float = 1f,
) {
    private val kbKey = if (dark) 0xFF3A332A.toInt() else 0xFFFBF6E6.toInt()
    private val kbKeyBorder = if (dark) 0xFF4A4234.toInt() else 0xFFD7C9A0.toInt()
    private val kbInk = if (dark) 0xFFF1E7C9.toInt() else 0xFF2B2720.toInt()
    private val accent = MicVisuals.ACCENT_TEAL

    private val previewSpacePx = dp(56f)
    private var popup: PopupWindow? = null
    private val view = OverlayView()

    val isShowing: Boolean get() = popup != null

    /** Ancora o popup cobrindo [frame] (tela) + faixa de preview acima, relativo ao [anchor] (a barra). */
    fun show(anchor: View, frame: GridFrame) {
        if (isShowing) return
        val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val window = PopupWindow(
            view,
            frame.width.toInt(),
            (frame.height + previewSpacePx).toInt(),
        ).apply {
            isTouchable = false
            isFocusable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false // a grade pode subir além da janela do IME (GRD-07)
        }
        val xoff = (frame.left - loc[0]).toInt()
        val yoff = ((frame.top - previewSpacePx) - (loc[1] + anchor.height)).toInt()
        window.showAsDropDown(anchor, xoff, yoff)
        popup = window
    }

    /** Estado novo vindo da [GridSession]: célula atual, reveal e posição do dedo (coordenadas de tela). */
    fun update(frame: GridFrame, cell: Int?, revealed: Boolean, fingerX: Float, fingerY: Float) {
        view.frame = frame
        view.cell = cell
        view.revealed = revealed
        view.fingerX = fingerX
        view.fingerY = fingerY
        view.invalidate()
    }

    fun dismiss() {
        popup?.let { runCatching { it.dismiss() } }
        popup = null
    }

    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density * scale

    /** Desenha lente/reveal/preview em coordenadas locais (origem = topo da faixa de preview). */
    private inner class OverlayView : View(context) {
        var frame: GridFrame? = null
        var cell: Int? = null
        var revealed = false
        var fingerX = 0f
        var fingerY = 0f

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = dp(13f)
        }
        private val corner = dp(7f)
        private val cellMargin = dp(2f)

        override fun onDraw(canvas: Canvas) {
            val f = frame ?: return
            val current = cell
            if (revealed) {
                for (index in 0 until grid.rows * grid.cols) drawCell(canvas, index, current, alpha = 210)
            } else if (current != null) {
                neighborsOf(current).forEach { drawCell(canvas, it, current, alpha = 150) }
                drawCell(canvas, current, current, alpha = 255)
            }
            if (current != null) drawPreview(canvas, f, current)
        }

        private fun cellRect(index: Int): RectF {
            val row = index / grid.cols
            val col = index % grid.cols
            val left = col * cellWidthPx + cellMargin
            val top = previewSpacePx + row * cellHeightPx + cellMargin
            return RectF(left, top, left + cellWidthPx - 2 * cellMargin, top + cellHeightPx - 2 * cellMargin)
        }

        private fun drawCell(canvas: Canvas, index: Int, current: Int?, alpha: Int) {
            val rect = cellRect(index)
            val isCurrent = index == current
            fillPaint.color = if (isCurrent) accent else kbKey
            fillPaint.alpha = alpha
            canvas.drawRoundRect(rect, corner, corner, fillPaint)
            borderPaint.color = kbKeyBorder
            borderPaint.alpha = alpha
            canvas.drawRoundRect(rect, corner, corner, borderPaint)

            val label = grid.cells[index]?.displayLabel ?: return
            textPaint.color = if (isCurrent) Color.WHITE else kbInk
            textPaint.alpha = alpha
            val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, rect.centerX(), y, textPaint)
        }

        /** Vizinhas imediatas (8-conectadas) da célula — a "lente" ao redor do dedo (GRD-02). */
        private fun neighborsOf(index: Int): List<Int> {
            val row = index / grid.cols
            val col = index % grid.cols
            return buildList {
                for (r in row - 1..row + 1) {
                    for (c in col - 1..col + 1) {
                        if (r == row && c == col) continue
                        if (r in 0 until grid.rows && c in 0 until grid.cols) add(r * grid.cols + c)
                    }
                }
            }
        }

        /** Bolha acima do ponto de toque com o atalho da célula atual (o dedo não cobre — GRD-02). */
        private fun drawPreview(canvas: Canvas, f: GridFrame, current: Int) {
            val label = grid.cells[current]?.displayLabel ?: "·"
            val localX = (fingerX - f.left).coerceIn(0f, f.width)
            val localY = fingerY - f.top + previewSpacePx
            textPaint.alpha = 255
            val halfWidth = maxOf(textPaint.measureText(label) / 2 + dp(10f), dp(22f))
            val bubbleBottom = localY - dp(28f)
            val bubble = RectF(localX - halfWidth, bubbleBottom - dp(30f), localX + halfWidth, bubbleBottom)
            fillPaint.color = kbInk
            fillPaint.alpha = 240
            canvas.drawRoundRect(bubble, corner, corner, fillPaint)
            textPaint.color = if (kbInk == 0xFF2B2720.toInt()) Color.WHITE else Color.BLACK
            val y = bubble.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, bubble.centerX(), y, textPaint)
        }
    }
}
