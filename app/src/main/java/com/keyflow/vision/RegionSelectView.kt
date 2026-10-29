package com.keyflow.vision

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.keyflow.i18n.tr
import kotlin.math.hypot

/** Seletor transparente sobre a tela viva; cancelar em resize impede usar coordenadas antigas. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class RegionSelectView(context: Context, private val confirm: (Rect) -> Unit, private val cancel: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var area = Rect()
    private var selected: RegionGeometry.Corner? = null
    private var initialized = false
    private val d = resources.displayMetrics.density
    private val margin get() = 48f * d

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (initialized && (w != oldw || h != oldh)) { cancel(); return }
        if (w > 0 && h > 0) { area = RegionGeometry.initial(w, h); initialized = true }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = 0x99000000.toInt(); paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, width.toFloat(), area.top.toFloat(), paint)
        canvas.drawRect(0f, area.bottom.toFloat(), width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, area.top.toFloat(), area.left.toFloat(), area.bottom.toFloat(), paint)
        canvas.drawRect(area.right.toFloat(), area.top.toFloat(), width.toFloat(), area.bottom.toFloat(), paint)
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d
        canvas.drawRect(RectF(area), paint)
        paint.style = Paint.Style.FILL
        corners().forEach { (_, p) -> canvas.drawCircle(p.first, p.second, 9 * d, paint) }
        paint.color = 0xcc202020.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), margin * 1.25f, 8 * d, 8 * d, paint)
        canvas.drawRoundRect(0f, height - margin * 1.25f, width.toFloat(), height.toFloat(), 8 * d, 8 * d, paint)
        paint.color = Color.WHITE; paint.textSize = 16 * d
        canvas.drawText(tr("Cancelar"), 12 * d, margin * .8f, paint)
        val full = tr("Tela inteira"); canvas.drawText(full, width - paint.measureText(full) - 12 * d, margin * .8f, paint)
        val ok = tr("Confirmar"); canvas.drawText(ok, (width - paint.measureText(ok)) / 2, height - margin * .4f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selected = corners().minByOrNull { (_, p) -> hypot(event.x - p.first, event.y - p.second) }
                    ?.takeIf { (_, p) -> hypot(event.x - p.first, event.y - p.second) <= 32 * d }?.first
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                selected?.let { area = RegionGeometry.move(area, it, event.x, event.y, width, height, (32 * d).toInt()); invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (selected == null) when {
                    event.y < margin * 1.25f && event.x < width / 2 -> cancel()
                    event.y < margin * 1.25f -> { area = RegionGeometry.full(width, height); invalidate() }
                    event.y > height - margin * 1.25f -> confirm(Rect(area))
                }
                selected = null; return true
            }
            MotionEvent.ACTION_CANCEL -> { selected = null; return true }
        }
        return true
    }

    private fun corners() = listOf(
        RegionGeometry.Corner.TOP_LEFT to (area.left.toFloat() to area.top.toFloat()),
        RegionGeometry.Corner.TOP_RIGHT to (area.right.toFloat() to area.top.toFloat()),
        RegionGeometry.Corner.BOTTOM_LEFT to (area.left.toFloat() to area.bottom.toFloat()),
        RegionGeometry.Corner.BOTTOM_RIGHT to (area.right.toFloat() to area.bottom.toFloat()),
    )
}
