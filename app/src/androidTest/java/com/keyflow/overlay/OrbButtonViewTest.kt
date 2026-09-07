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
import com.keyflow.ui.orb.OrbDragKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── modo-arrasto travado (segurar → setas → soltar na zona) ───────────────
    //
    // Injeção direta de MotionEvent em onTouchEvent (mesma técnica das demais classes deste arquivo) em
    // vez de instrumentation.sendPointerSync: mais confiável que simular o toque pelo sistema (a
    // fragilidade documentada no projeto é da entrega REAL de toque via display, não da lógica do gesto
    // em si). Pedido do Yan (2026-07-04): sem cronometrar a espera — segura, dá o arrasto, espera o
    // estado/intent chegar via waitUntil.

    private fun startHold(view: OrbButtonView, x: Float = 100f, y: Float = 100f) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = x, y = y, time = 0))
        waitUntil("modo-arrasto não travou (dragLockedForTest)") { view.dragLockedForTest }
    }

    /** [dx]/[dy] em px reais (o padrão de [ORB_SIZE_PX] cruza a ativação com folga de sobra). */
    private fun dragTo(view: OrbButtonView, dx: Float, dy: Float, x0: Float = 100f, y0: Float = 100f) {
        view.onTouchEvent(event(MotionEvent.ACTION_MOVE, x = x0 + dx, y = y0 + dy, time = 30))
    }

    private fun release(view: OrbButtonView, x: Float, y: Float) {
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = x, y = y, time = 60))
    }

    @Test
    fun holdingUpWhileRecordingPausesTheRecording() {
        fakeState = ButtonState.RECORDING
        val view = view()
        view.render(ButtonState.RECORDING)

        startHold(view)
        dragTo(view, dx = 0f, dy = -DRAG_DISTANCE)
        waitUntil("dragKindForTest não resolveu PAUSE") { view.dragKindForTest == OrbDragKind.PAUSE }
        release(view, x = 100f, y = 100f - DRAG_DISTANCE)

        assertTrue("esperava PAUSE em $intents", intents.contains(GestureIntent.PAUSE))
        assertFalse(view.dragLockedForTest)
    }

    @Test
    fun holdingUpWhilePausedResumesTheRecording() {
        fakeState = ButtonState.PAUSED
        val view = view()
        view.render(ButtonState.PAUSED)

        startHold(view)
        dragTo(view, dx = 0f, dy = -DRAG_DISTANCE)
        waitUntil("dragKindForTest não resolveu RESUME") { view.dragKindForTest == OrbDragKind.RESUME }
        release(view, x = 100f, y = 100f - DRAG_DISTANCE)

        assertTrue("esperava RESUME em $intents", intents.contains(GestureIntent.RESUME))
    }

    @Test
    fun holdingLateralWhileRecordingCancelsTheRecording() {
        fakeState = ButtonState.RECORDING
        val view = view()
        view.render(ButtonState.RECORDING)

        startHold(view)
        // Lado padrão (destro=RIGHT): lateral = arrastar pro CENTRO da tela, ou seja dx negativo.
        dragTo(view, dx = -DRAG_DISTANCE, dy = 0f)
        waitUntil("dragKindForTest não resolveu CANCEL") { view.dragKindForTest == OrbDragKind.CANCEL }
        release(view, x = 100f - DRAG_DISTANCE, y = 100f)

        assertTrue("esperava CANCEL em $intents", intents.contains(GestureIntent.CANCEL))
    }

    @Test
    fun holdingLateralWhileSendingCancelsTheTranscriptionInToggleMode() {
        fakeState = ButtonState.SENDING
        val view = view(mode = RecordingMode.TOGGLE)
        view.render(ButtonState.SENDING)

        startHold(view)
        dragTo(view, dx = -DRAG_DISTANCE, dy = 0f)
        waitUntil("dragKindForTest não resolveu CANCEL em SENDING (Alternar)") {
            view.dragKindForTest == OrbDragKind.CANCEL
        }
        release(view, x = 100f - DRAG_DISTANCE, y = 100f)

        assertTrue("esperava CANCEL em $intents", intents.contains(GestureIntent.CANCEL))
    }

    @Test
    fun holdingUpWhileSendingDoesNothingInToggleMode() {
        // Yan, 2026-07-03: em SENDING só a lateral (cancelar) é válida — cima/baixo ficam pinados em 0
        // mesmo arrastando, pra não reacender ações que não fazem sentido nesse estado.
        fakeState = ButtonState.SENDING
        val view = view(mode = RecordingMode.TOGGLE)
        view.render(ButtonState.SENDING)

        startHold(view)
        dragTo(view, dx = 0f, dy = -DRAG_DISTANCE)
        // Sem waitUntil positivo aqui de propósito: aguarda o looper assentar e confere que NADA resolveu.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(150)
        release(view, x = 100f, y = 100f - DRAG_DISTANCE)

        assertTrue(
            "não deveria ter emitido PAUSE/RESUME/CANCEL durante SENDING arrastando pra cima: $intents",
            intents.none { it == GestureIntent.PAUSE || it == GestureIntent.RESUME || it == GestureIntent.CANCEL },
        )
    }

    @Test
    fun holdingLateralWhileSendingCancelsTheTranscriptionInPushToTalkMode() {
        // Extensão pedida pelo Yan (2ª rodada do Q5, 2026-07-03): o gesto de cancelar durante a
        // transcrição vale também no PTT, não só no Alternar.
        fakeState = ButtonState.SENDING
        val view = view(mode = RecordingMode.PUSH_TO_TALK)
        view.render(ButtonState.SENDING)

        startHold(view)
        dragTo(view, dx = -DRAG_DISTANCE, dy = 0f)
        waitUntil("dragKindForTest não resolveu CANCEL em SENDING (PTT)") {
            view.dragKindForTest == OrbDragKind.CANCEL
        }
        release(view, x = 100f - DRAG_DISTANCE, y = 100f)

        assertTrue("esperava CANCEL em $intents", intents.contains(GestureIntent.CANCEL))
    }

    private fun tap(view: OrbButtonView, time: Long) {
        view.onTouchEvent(event(MotionEvent.ACTION_DOWN, x = 50f, y = 50f, time = time))
        view.onTouchEvent(event(MotionEvent.ACTION_UP, x = 50f, y = 50f, time = time + 20))
    }

    private fun event(action: Int, x: Float, y: Float, time: Long): MotionEvent =
        MotionEvent.obtain(0, time, action, x, y, 0)

    private companion object {
        // sizePx padrão (156) × DRAG_ACTIVATION_FRACTION (0.7) = ~109px de ativação — folga generosa
        // pra garantir que o arrasto cruze o limiar mesmo com alguma imprecisão de coordenadas no teste.
        const val DRAG_DISTANCE = 160f
    }
}
