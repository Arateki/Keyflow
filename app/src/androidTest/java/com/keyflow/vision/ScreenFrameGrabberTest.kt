package com.keyflow.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenFrameGrabberTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun fonteSemQuadroTerminaComErroELiberaRecursos() {
        val completed = CountDownLatch(1)
        val released = CountDownLatch(1)
        var failure: Throwable? = null
        var mainResult = false
        val source = object : ScreenFrameGrabber.Source {
            override fun start(handler: Handler, frame: (ScreenFrameGrabber.Frame) -> Unit) = Unit
            override fun close() { released.countDown() }
        }
        instrumentation.runOnMainSync {
            ScreenFrameGrabber(firstFrameTimeoutMs = 30).capture(source, Rect(0, 0, 8, 8)) {
                failure = it.exceptionOrNull()
                mainResult = Looper.myLooper() == Looper.getMainLooper()
                completed.countDown()
            }
        }
        assertTrue("o prazo não encerrou a captura", completed.await(5, TimeUnit.SECONDS))
        assertTrue(failure is TimeoutException)
        assertTrue(mainResult)
        assertTrue("a fonte ficou aberta", released.await(5, TimeUnit.SECONDS))
    }

    @Test fun converteForaDaUiEEntregaNaThreadPrincipal() {
        val completed = CountDownLatch(1)
        val released = CountDownLatch(1)
        var offMain = false
        var frameClosed = false
        var delivered: Bitmap? = null
        var mainResult = false
        val source = object : ScreenFrameGrabber.Source {
            override fun start(handler: Handler, frame: (ScreenFrameGrabber.Frame) -> Unit) {
                handler.post {
                    frame(object : ScreenFrameGrabber.Frame {
                        override fun crop(rect: Rect): Bitmap {
                            offMain = Looper.myLooper() != Looper.getMainLooper()
                            return Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                        }
                        override fun close() { frameClosed = true }
                    })
                }
            }
            override fun close() { released.countDown() }
        }
        instrumentation.runOnMainSync {
            ScreenFrameGrabber().capture(source, Rect(0, 0, 12, 9)) {
                delivered = it.getOrThrow()
                mainResult = Looper.myLooper() == Looper.getMainLooper()
                completed.countDown()
            }
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertTrue(released.await(5, TimeUnit.SECONDS))
        try {
            assertTrue("bitmap montado na UI", offMain)
            assertTrue(mainResult)
            assertTrue(frameClosed)
            assertEquals(12, delivered!!.width)
            assertEquals(9, delivered!!.height)
        } finally { delivered?.recycle() }
    }

    @Test fun fundoPretoComConteudoForaDaGradeAntigaNaoEhProtegido() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLACK)
            assertTrue("quadro inteiramente preto deve manter o aviso", ScreenFrameGrabber.isBlackFrame(bitmap))
            // As 16 posições antigas continuam pretas, mas há conteúdo na captura de terminal.
            bitmap.setPixel(1, 1, Color.WHITE)
            assertFalse("conteúdo esparso foi confundido com FLAG_SECURE", ScreenFrameGrabber.isBlackFrame(bitmap))
        } finally { bitmap.recycle() }
    }
}
