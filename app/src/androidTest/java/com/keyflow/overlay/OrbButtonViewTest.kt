package com.keyflow.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode
import com.keyflow.data.OverlayPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbButtonViewTest {

    private lateinit var context: Context
    private var fakeState = ButtonState.IDLE_READY
    private val intents = mutableListOf<GestureIntent>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        intents.clear()
        fakeState = ButtonState.IDLE_READY
    }

    private fun view(
        mode: RecordingMode = RecordingMode.TOGGLE,
        onPositionChanged: (OverlayPosition) -> Unit = {},
    ): OrbButtonView {
        val controller = RecordingGestureController(
            mode = { mode },
            currentState = { fakeState },
        )
        return OrbButtonView(
            context = context,
            controller = controller,
            mode = { mode },
            onIntent = { intents += it },
            onPositionChanged = onPositionChanged,
        )
    }

    @Test
    fun renderMapsStateToContentDescription() {
        val view = view()

        view.render(ButtonState.IDLE_READY)
        assertEquals(ButtonState.IDLE_READY, view.renderedState)
        assertEquals("KeyFlow ready", view.contentDescription)

        view.render(ButtonState.RECORDING)
        assertEquals(ButtonState.RECORDING, view.renderedState)
        assertEquals("KeyFlow recording", view.contentDescription)

        view.render(ButtonState.PAUSED)
        assertEquals(ButtonState.PAUSED, view.renderedState)
        assertEquals("KeyFlow paused", view.contentDescription)
    }

    @Test
    fun dragRepositionsWindowAndPersistsWithoutRecording() {
        val persisted = mutableListOf<OverlayPosition>()
        val view = view(onPositionChanged = { persisted += it })
        val params = WindowManager.LayoutParams().apply { x = 10; y = 20 }
        view.bindWindowLayout(params) { /* updateViewLayout no-op */ }

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 100f, y = 200f, time = 0))
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = 125f, y = 245f, time = 10))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = 125f, y = 245f, time = 20))

        assertEquals(35, params.x)
        assertEquals(65, params.y)
        assertEquals(OverlayPosition(35, 65), persisted.single())
        // Arrastar nunca inicia gravação (anti-movimento): só reposição.
        assertTrue(intents.none { it == GestureIntent.START })
        assertTrue(intents.contains(GestureIntent.REPOSITION_ONLY))
    }

    @Test
    fun tripleTapEmitsKeyboardPickerIntent() {
        val view = view()

        tap(view, 0)
        tap(view, 100)
        tap(view, 200)

        assertEquals(1, intents.count { it == GestureIntent.KEYBOARD_PICKER })
    }

    @Test
    fun pushToTalkDownEmitsStartIntent() {
        val view = view(mode = RecordingMode.PUSH_TO_TALK)

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 50f, y = 50f, time = 0))

        assertTrue(intents.contains(GestureIntent.START))
    }

    private fun tap(view: OrbButtonView, time: Long) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 50f, y = 50f, time = time))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = 50f, y = 50f, time = time + 20))
    }

    private fun event(action: Int, x: Float, y: Float, time: Long): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)
}
