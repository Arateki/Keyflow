package com.keyflow.vision

import android.content.Context
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

    @Test fun arrastaCantoSemSairDaTela() {
        val initial = Rect(100, 100, 900, 900)
        assertEquals(Rect(0, 0, 900, 900), RegionGeometry.move(initial, RegionGeometry.Corner.TOP_LEFT, -10f, -20f, 1000, 1000, 40))
        assertEquals(Rect(860, 860, 900, 900), RegionGeometry.move(initial, RegionGeometry.Corner.TOP_LEFT, 990f, 990f, 1000, 1000, 40))
    }
}
