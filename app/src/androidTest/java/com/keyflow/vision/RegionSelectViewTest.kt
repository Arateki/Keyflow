package com.keyflow.vision

import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import com.keyflow.ui.gallery.GalleryActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegionSelectViewTest {
    @Test fun selecionaRegiaoInteiraEPermiteCancelar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var chosen: Rect? = null
        var cancelled = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = RegionSelectView(context, { chosen = it }, { cancelled = true })
            view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1000, 1000)
            fun touch(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(0, 0, action, x, y, 0)
                try { view.onTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 950f, 10f); touch(MotionEvent.ACTION_UP, 950f, 10f)
            touch(MotionEvent.ACTION_DOWN, 500f, 990f); touch(MotionEvent.ACTION_UP, 500f, 990f)
            assertEquals(Rect(0, 0, 1000, 1000), chosen)
            touch(MotionEvent.ACTION_DOWN, 10f, 10f); touch(MotionEvent.ACTION_UP, 10f, 10f)
        }
        assertTrue(cancelled)
    }

    @Test fun arrastaHandleEConfirmaCoordenadasDaTela() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var chosen: Rect? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = RegionSelectView(context, { chosen = it }, {})
            view.layout(0, 0, 1000, 1000)
            for ((action, point) in listOf(MotionEvent.ACTION_DOWN to (200f to 200f),
                MotionEvent.ACTION_MOVE to (170f to 185f), MotionEvent.ACTION_UP to (150f to 175f),
                MotionEvent.ACTION_DOWN to (500f to 990f), MotionEvent.ACTION_UP to (500f to 990f))) {
                val event = MotionEvent.obtain(0, 0, action, point.first, point.second, 0)
                try { view.onTouchEvent(event) } finally { event.recycle() }
            }
        }
        assertEquals(Rect(150, 175, 800, 800), chosen)
    }

    @Test fun confirmaEmCoordenadasDaTelaMesmoComJanelaDeslocada() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ready = CountDownLatch(1)
        var origin = IntArray(2)
        var chosen: Rect? = null
        ActivityScenario.launch<GalleryActivity>(Intent(context, GalleryActivity::class.java).putExtra("route", "config-visao")).use { scenario ->
            lateinit var selector: RegionSelectView
            scenario.onActivity { activity ->
                val root = FrameLayout(activity)
                selector = RegionSelectView(activity, { chosen = it }, {})
                root.addView(selector, FrameLayout.LayoutParams(600, 900).apply { leftMargin = 60; topMargin = 80 })
                selector.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    selector.getLocationOnScreen(origin)
                    ready.countDown()
                }
                activity.setContentView(root)
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            scenario.onActivity {
                val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 300f, 890f, 0)
                try { selector.onTouchEvent(event) } finally { event.recycle() }
            }
            assertEquals(Rect(120 + origin[0], 180 + origin[1], 480 + origin[0], 720 + origin[1]), chosen)
        }
    }

    @Test fun resizeCancelaCoordenadasAntigas() {
        var resized = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = RegionSelectView(ApplicationProvider.getApplicationContext(), {}, {}, { resized = true })
            view.layout(0, 0, 1000, 1000)
            view.layout(0, 0, 800, 600)
        }
        assertTrue(resized)
    }
}
