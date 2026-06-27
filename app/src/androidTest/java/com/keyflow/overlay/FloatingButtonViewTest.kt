package com.keyflow.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.ButtonState
import com.keyflow.data.OverlayPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingButtonViewTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun renderMapsStateToButtonPresentation() {
        val view = FloatingButtonView(context)

        view.render(ButtonState.IDLE_READY)
        assertEquals(ButtonState.IDLE_READY, view.renderedState)
        assertEquals("KeyFlow ready", view.contentDescription)

        view.render(ButtonState.RECORDING)
        assertEquals(ButtonState.RECORDING, view.renderedState)
        assertEquals("KeyFlow recording", view.contentDescription)

        view.render(ButtonState.PENDING_PASTE)
        assertEquals(ButtonState.PENDING_PASTE, view.renderedState)
        assertEquals("KeyFlow pending paste", view.contentDescription)
    }

    @Test
    fun dragUpdatesWindowLayoutAndPersistsPosition() {
        val persisted = mutableListOf<OverlayPosition>()
        val updated = mutableListOf<WindowManager.LayoutParams>()
        val view = FloatingButtonView(
            context = context,
            onPositionChanged = { persisted += it },
        )
        val params = WindowManager.LayoutParams().apply {
            x = 10
            y = 20
        }
        view.bindWindowLayout(params) { updated += WindowManager.LayoutParams().also { copy ->
            copy.x = it.x
            copy.y = it.y
        } }

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 100f, y = 200f, time = 0))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 125f, y = 245f, time = 10))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = 125f, y = 245f, time = 20))

        assertEquals(35, params.x)
        assertEquals(65, params.y)
        assertTrue(updated.isNotEmpty())
        assertEquals(OverlayPosition(35, 65), persisted.single())
    }

    @Test
    fun tripleTapInvokesKeyboardPickerCallback() {
        var pickerCount = 0
        val view = FloatingButtonView(
            context = context,
            onShowInputMethodPicker = { pickerCount++ },
        )

        tap(view, 0)
        tap(view, 100)
        tap(view, 200)

        assertEquals(1, pickerCount)
    }

    private fun tap(view: FloatingButtonView, time: Long) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 50f, y = 50f, time = time))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = 50f, y = 50f, time = time + 20))
    }

    private fun event(action: Int, x: Float, y: Float, time: Long): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)
}
