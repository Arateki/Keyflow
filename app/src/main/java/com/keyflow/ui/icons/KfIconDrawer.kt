package com.keyflow.ui.icons

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/**
 * O mesmo [KfIconSpec] do Compose, para as views em `android.graphics` (barra do teclado). Guarda os
 * `Path` já interpretados por ícone — `onDraw` roda a cada toque e não pode reinterpretar string.
 */
class KfIconDrawer {
    private val cache = HashMap<KfIconSpec, Pair<List<Path>, List<Path>>>()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Desenha [icon] num quadrado de lado [sizePx] centrado em ([cx], [cy]). */
    fun draw(
        canvas: Canvas,
        icon: KfIconSpec,
        cx: Float,
        cy: Float,
        sizePx: Float,
        color: Int,
        alpha: Int = 255,
        strokeWidth: Float = icon.strokeWidth,
    ) {
        val (strokes, fills) = cache.getOrPut(icon) {
            icon.strokes.map(PathParser::createPathFromPathData) to icon.fills.map(PathParser::createPathFromPathData)
        }
        val s = sizePx / 24f
        canvas.save()
        canvas.translate(cx - sizePx / 2f, cy - sizePx / 2f)
        canvas.scale(s, s)
        strokePaint.color = color
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = strokeWidth
        fillPaint.color = color
        fillPaint.alpha = alpha
        strokes.forEach { canvas.drawPath(it, strokePaint) }
        fills.forEach { canvas.drawPath(it, fillPaint) }
        canvas.restore()
    }

    /** Atalho para centralizar em [bounds]. */
    fun draw(canvas: Canvas, icon: KfIconSpec, bounds: RectF, sizePx: Float, color: Int, alpha: Int = 255) =
        draw(canvas, icon, bounds.centerX(), bounds.centerY(), sizePx, color, alpha)
}
