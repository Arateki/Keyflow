package com.keyflow.skins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * O fundo do teclado de um pacote (SKP-KBD-02): o `keyboard_tray.svg` **esticado** até o tamanho da área que ele cobre (barras e grade
 * juntas), atrás das teclas. Vira um bitmap só quando o tamanho muda ([onBoundsChange]) — no `draw` é um `drawBitmap`, nada de SVG por
 * quadro. Se o SVG não desenha (ou o tamanho é vazio), cai na cor lisa [fallbackColor] (`keyboard.trayColor`).
 *
 * Opacidade: [PixelFormat.TRANSLUCENT] — a bandeja pode ter transparência, e o que está atrás aparece.
 */
internal class PackBackdrop(private val image: SvgImage, private val fallbackColor: Int) : Drawable() {

    private var bitmap: Bitmap? = null
    private var renderedWidth = 0
    private var renderedHeight = 0

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val width = bounds.width()
        val height = bounds.height()
        if (width == renderedWidth && height == renderedHeight) return
        renderedWidth = width
        renderedHeight = height
        bitmap = if (width > 0 && height > 0) {
            try {
                SvgRender.toBitmap(image, width, height, SvgFit.STRETCH)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rendered = bitmap
        if (rendered != null) canvas.drawBitmap(rendered, b.left.toFloat(), b.top.toFloat(), null) else canvas.drawColor(fallbackColor)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
