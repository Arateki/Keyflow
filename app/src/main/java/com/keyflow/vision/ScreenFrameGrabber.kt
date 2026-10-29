package com.keyflow.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Captura única: prazo só para o primeiro quadro; conversão e leitura de pixels fora da thread da UI. */
class ScreenFrameGrabber(private val firstFrameTimeoutMs: Long = 5_000L) {
    internal interface Frame : AutoCloseable { fun crop(rect: Rect): Bitmap }
    internal interface Source : AutoCloseable { fun start(handler: Handler, frame: (Frame) -> Unit) }

    private val main = Handler(Looper.getMainLooper())
    private var active: Capture? = null

    private class Capture(val source: Source, val thread: HandlerThread) {
        val worker = Handler(thread.looper)
        val received = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        lateinit var timeout: Runnable
    }

    fun capture(projection: MediaProjection, width: Int, height: Int, densityDpi: Int, rect: Rect,
                result: (Result<Bitmap>) -> Unit) {
        require(width > 0 && height > 0 && rect.left >= 0 && rect.top >= 0 &&
            rect.right <= width && rect.bottom <= height && rect.width() > 0 && rect.height() > 0)
        capture(ProjectionSource(projection, width, height, densityDpi), Rect(rect), result)
    }

    /** Fonte trocável permite testar silêncio, quadro tardio e cancelamento sem autorizar uma projeção real. */
    internal fun capture(source: Source, rect: Rect, result: (Result<Bitmap>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(active == null)
        val current = Capture(source, HandlerThread("KeyFlowVisionFrame").apply { start() })
        active = current
        current.timeout = Runnable {
            if (active === current && !current.received.get()) {
                close()
                result(Result.failure(TimeoutException("primeiro quadro não recebido")))
            }
        }
        main.postDelayed(current.timeout, firstFrameTimeoutMs)
        try {
            source.start(current.worker) { frame ->
                if (current.cancelled.get() || !current.received.compareAndSet(false, true)) {
                    runCatching { frame.close() }
                } else {
                    main.removeCallbacks(current.timeout)
                    val outcome = runCatching { frame.use { it.crop(rect) } }
                    main.post {
                        if (active === current) {
                            close()
                            result(outcome)
                        } else outcome.getOrNull()?.recycle()
                    }
                }
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /** O reader só fecha após qualquer conversão em andamento liberar seu Image. */
    fun close() {
        val current = active ?: return
        active = null
        current.cancelled.set(true)
        main.removeCallbacks(current.timeout)
        current.worker.post {
            try { current.source.close() } catch (error: Exception) {
                android.util.Log.w("KeyFlowVision", "Falha ao liberar a fonte de quadros", error)
            } finally { current.thread.quitSafely() }
        }
    }

    private class ProjectionSource(private val projection: MediaProjection, private val width: Int,
                                   private val height: Int, private val densityDpi: Int) : Source {
        private var reader: ImageReader? = null
        private var display: VirtualDisplay? = null
        override fun start(handler: Handler, frame: (Frame) -> Unit) {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            this.reader = reader
            reader.setOnImageAvailableListener({ source ->
                val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                frame(object : Frame {
                    override fun crop(rect: Rect): Bitmap = decode(image, width, height, rect)
                    override fun close() = image.close()
                })
            }, handler)
            display = projection.createVirtualDisplay("KeyFlow Visão", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
        }
        override fun close() {
            try { display?.release() } finally {
                display = null
                reader?.setOnImageAvailableListener(null, null)
                reader?.close(); reader = null
            }
        }
    }

    companion object {
        private fun decode(image: Image, width: Int, height: Int, rect: Rect): Bitmap {
            val plane = image.planes[0]
            val paddedWidth = width + (plane.rowStride - plane.pixelStride * width) / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            try {
                padded.copyPixelsFromBuffer(plane.buffer)
                val crop = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                try {
                    Canvas(crop).drawBitmap(padded, -rect.left.toFloat(), -rect.top.toFloat(), null)
                    check(!isBlackFrame(crop)) { "tela protegida ou preta" }
                    return crop
                } catch (error: Throwable) { crop.recycle(); throw error }
            } finally { padded.recycle() }
        }

        /** Um fundo preto com conteúdo esparso é válido; uma captura protegida é inteiramente preta. */
        internal fun isBlackFrame(bitmap: Bitmap): Boolean {
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                if (row.any { it and 0x00ffffff != 0 }) return false
            }
            return true
        }
    }
}
