package com.keyflow.vision

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper

/** Uma projeção cria somente um VirtualDisplay; libera ImageReader e display ao receber o primeiro quadro. */
class ScreenFrameGrabber {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    fun capture(projection: MediaProjection, width: Int, height: Int, densityDpi: Int, rect: Rect,
                result: (Result<Bitmap>) -> Unit) {
        require(width > 0 && height > 0 && rect.left >= 0 && rect.top >= 0 &&
            rect.right <= width && rect.bottom <= height && rect.width() > 0 && rect.height() > 0)
        check(reader == null)
        val current = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = current
        current.setOnImageAvailableListener({ source ->
            val frame = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            val outcome = runCatching {
                frame.use {
                    val plane = it.planes[0]
                    val paddedWidth = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
                    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    try {
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val crop = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                        Canvas(crop).drawBitmap(padded, -rect.left.toFloat(), -rect.top.toFloat(), null)
                        val black = (0 until 16).all { n ->
                            val color = crop.getPixel((n % 4) * (crop.width - 1) / 3, (n / 4) * (crop.height - 1) / 3)
                            color and 0x00ffffff == 0
                        }
                        if (black) { crop.recycle(); error("tela protegida ou preta") }
                        crop
                    } finally { padded.recycle() }
                }
            }
            close()
            result(outcome)
        }, Handler(Looper.getMainLooper()))
        try {
            display = projection.createVirtualDisplay("KeyFlow Visão", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, current.surface, null, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { close(); throw e }
    }

    fun close() {
        display?.release(); display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
    }
}
