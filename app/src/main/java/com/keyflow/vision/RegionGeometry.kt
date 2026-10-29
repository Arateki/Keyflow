package com.keyflow.vision

import android.graphics.Rect
import kotlin.math.roundToInt

/** Geometria em coordenadas da tela; cada canto move suas duas bordas sem inverter o retângulo. */
object RegionGeometry {
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    fun initial(width: Int, height: Int): Rect = Rect(width / 5, height / 5, width * 4 / 5, height * 4 / 5)
    fun full(width: Int, height: Int): Rect = Rect(0, 0, width, height)
    fun move(rect: Rect, corner: Corner, x: Float, y: Float, width: Int, height: Int, minSize: Int): Rect {
        val r = Rect(rect)
        val px = x.roundToInt().coerceIn(0, width)
        val py = y.roundToInt().coerceIn(0, height)
        when (corner) {
            Corner.TOP_LEFT -> { r.left = px.coerceAtMost(r.right - minSize); r.top = py.coerceAtMost(r.bottom - minSize) }
            Corner.TOP_RIGHT -> { r.right = px.coerceAtLeast(r.left + minSize); r.top = py.coerceAtMost(r.bottom - minSize) }
            Corner.BOTTOM_LEFT -> { r.left = px.coerceAtMost(r.right - minSize); r.bottom = py.coerceAtLeast(r.top + minSize) }
            Corner.BOTTOM_RIGHT -> { r.right = px.coerceAtLeast(r.left + minSize); r.bottom = py.coerceAtLeast(r.top + minSize) }
        }
        return r
    }
}
