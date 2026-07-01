package com.keyflow.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    private val shortcutTaps = mutableListOf<Int>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        intents.clear()
        shortcutTaps.clear()
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
            onShortcut = { shortcutTaps += it },
        )
    }

    /** Aguarda uma condição assíncrona (resolvida via `Handler.postDelayed` no looper principal). */
    private fun waitUntil(message: String, timeoutMs: Long = 3000L, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
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
    fun tripleTapInvokesShortcutCallback() {
        // SHORTCUT-01/02: desde os atalhos configuráveis por toque, a rajada (2/3 toques) resolve via
        // onShortcut(count) — não mais via onIntent(KEYBOARD_PICKER) direto (esse mapeamento no
        // RecordingGestureController só sobrevive para o sinal TRIPLE_TAP do GestureInterpreter, que o
        // OrbButtonView não emite mais; ele conta a rajada por si via registerBurstTap()). A resolução
        // é assíncrona (Handler.postDelayed por tapBurstWindowMs) — precisa aguardar.
        val view = view()

        tap(view, 0)
        tap(view, 20)
        tap(view, 40)

        waitUntil("Esperando onShortcut(3)") { shortcutTaps.contains(3) }
        assertEquals(1, shortcutTaps.count { it == 3 })
    }

    @Test
    fun doubleTapInvokesShortcutCallback() {
        // SHORTCUT-02: 2 toques também é rajada de atalho (não só 3).
        val view = view()

        tap(view, 0)
        tap(view, 20)

        waitUntil("Esperando onShortcut(2)") { shortcutTaps.contains(2) }
        assertEquals(1, shortcutTaps.count { it == 2 })
    }

    @Test
    fun shortcutDuringRecordingDoesNotEmitRecordingIntent() {
        // SHORTCUT-04: o atalho não mexe na gravação — nenhum GestureIntent é emitido pela rajada,
        // só o callback onShortcut (cuja ação é resolvida fora, em OverlayService.handleOrbShortcut).
        fakeState = ButtonState.RECORDING
        val view = view()

        tap(view, 0)
        tap(view, 20)
        tap(view, 40)

        waitUntil("Esperando onShortcut(3)") { shortcutTaps.contains(3) }
        assertTrue("rajada não deveria emitir GestureIntent: $intents", intents.isEmpty())
    }

    @Test
    fun pushToTalkDownEmitsStartIntent() {
        val view = view(mode = RecordingMode.PUSH_TO_TALK)

        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 50f, y = 50f, time = 0))

        // PTT-01 (start "não otimista"): o START só é confirmado após o threshold de segurar (Tempo de
        // reação + 150ms), agendado via Handler.postDelayed no looper principal — precisa aguardar.
        waitUntil("Esperando GestureIntent.START") { intents.contains(GestureIntent.START) }
    }

    private fun tap(view: OrbButtonView, time: Long) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 50f, y = 50f, time = time))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = 50f, y = 50f, time = time + 20))
    }

    private fun event(action: Int, x: Float, y: Float, time: Long): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)
}
