package com.keyflow.vision

import kotlin.math.hypot
import kotlin.math.roundToInt

/** Coordenadas independentes do Android, para validar limites antes de criar o Rect da captura. */
data class ScreenRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Geometria e hit-test puros; cada canto move duas bordas sem inverter o retângulo. */
object RegionGeometry {
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    fun initial(width: Int, height: Int) = ScreenRegion(width / 5, height / 5, width * 4 / 5, height * 4 / 5)
    fun full(width: Int, height: Int) = ScreenRegion(0, 0, width, height)
    fun corners(rect: ScreenRegion) = listOf(
        Corner.TOP_LEFT to (rect.left.toFloat() to rect.top.toFloat()),
        Corner.TOP_RIGHT to (rect.right.toFloat() to rect.top.toFloat()),
        Corner.BOTTOM_LEFT to (rect.left.toFloat() to rect.bottom.toFloat()),
        Corner.BOTTOM_RIGHT to (rect.right.toFloat() to rect.bottom.toFloat()),
    )

    fun hit(rect: ScreenRegion, x: Float, y: Float, radius: Float): Corner? =
        corners(rect).minByOrNull { (_, p) -> hypot(x - p.first, y - p.second) }
            ?.takeIf { (_, p) -> hypot(x - p.first, y - p.second) <= radius }?.first

    fun move(rect: ScreenRegion, corner: Corner, x: Float, y: Float, width: Int, height: Int, minSize: Int): ScreenRegion {
        val px = x.roundToInt().coerceIn(0, width)
        val py = y.roundToInt().coerceIn(0, height)
        val left = px.coerceAtMost((rect.right - minSize).coerceAtLeast(0))
        val top = py.coerceAtMost((rect.bottom - minSize).coerceAtLeast(0))
        val right = px.coerceAtLeast((rect.left + minSize).coerceAtMost(width))
        val bottom = py.coerceAtLeast((rect.top + minSize).coerceAtMost(height))
        return when (corner) {
            Corner.TOP_LEFT -> rect.copy(left = left, top = top)
            Corner.TOP_RIGHT -> rect.copy(right = right, top = top)
            Corner.BOTTOM_LEFT -> rect.copy(left = left, bottom = bottom)
            Corner.BOTTOM_RIGHT -> rect.copy(right = right, bottom = bottom)
        }
    }
}
