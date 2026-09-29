package com.keyflow.ui.icons

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * [KfIconSpec] como `Drawable`, para views de texto do `android.widget` (os chips do seletor do mic
 * usam como ícone composto ao lado do rótulo, no lugar dos glifos ⏸ ▶ ✕, que viravam emoji colorido).
 */
class KfIconDrawable(
    private val icon: KfIconSpec,
    private val color: Int,
    private val sizePx: Int,
    private val strokeWidth: Float = icon.strokeWidth,
) : Drawable() {
    private val drawer = KfIconDrawer()
    private var drawAlpha = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        drawer.draw(
            canvas,
            icon,
            b.exactCenterX(),
            b.exactCenterY(),
            minOf(b.width(), b.height()).toFloat(),
            color,
            alpha = drawAlpha,
            strokeWidth = strokeWidth,
        )
    }

    override fun getIntrinsicWidth(): Int = sizePx

    override fun getIntrinsicHeight(): Int = sizePx

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
