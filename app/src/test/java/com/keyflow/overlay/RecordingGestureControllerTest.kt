package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode
import org.junit.Test

class RecordingGestureControllerTest {

    private var now = 0L
    private var mode = RecordingMode.TOGGLE

    private fun controller(targetAvailable: Boolean = true) =
        RecordingGestureController(
            mode = { mode },
            targetAvailable = { targetAvailable },
            pttPauseWindowMs = WINDOW,
            clock = { now },
        )

    // ---- TOGGLE ----

    @Test
    fun `toggle tap starts and a second tap stops`() {
        mode = RecordingMode.TOGGLE
        val c = controller()

        assertThat(c.onSignal(TouchSignal.TAP)).isEqualTo(GestureIntent.START)
        assertThat(c.state).isEqualTo(ButtonState.RECORDING)

        assertThat(c.onSignal(TouchSignal.TAP)).isEqualTo(GestureIntent.STOP)
        assertThat(c.state).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `toggle hold pauses and resumes recording`() {
        mode = RecordingMode.TOGGLE
        val c = controller()
        c.onSignal(TouchSignal.TAP) // RECORDING

        assertThat(c.onSignal(TouchSignal.HOLD)).isEqualTo(GestureIntent.PAUSE)
        assertThat(c.state).isEqualTo(ButtonState.PAUSED)

        assertThat(c.onSignal(TouchSignal.HOLD)).isEqualTo(GestureIntent.RESUME)
        assertThat(c.state).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `toggle tap while paused stops`() {
        mode = RecordingMode.TOGGLE
        val c = controller()
        c.onSignal(TouchSignal.TAP)  // RECORDING
        c.onSignal(TouchSignal.HOLD) // PAUSED

        assertThat(c.onSignal(TouchSignal.TAP)).isEqualTo(GestureIntent.STOP)
        assertThat(c.state).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `triple tap requests keyboard picker without starting`() {
        mode = RecordingMode.TOGGLE
        val c = controller()

        assertThat(c.onSignal(TouchSignal.TRIPLE_TAP)).isEqualTo(GestureIntent.KEYBOARD_PICKER)
        assertThat(c.state).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `drag only repositions and never starts recording`() {
        mode = RecordingMode.TOGGLE
        val c = controller()

        assertThat(c.onSignal(TouchSignal.DRAG_START)).isEqualTo(GestureIntent.REPOSITION_ONLY)
        // Um tap acidental no mesmo ciclo de arrasto é suprimido
        assertThat(c.onSignal(TouchSignal.TAP)).isEqualTo(GestureIntent.NONE)
        assertThat(c.state).isEqualTo(ButtonState.IDLE_READY)

        // Após o arrasto terminar, taps voltam a iniciar a gravação
        assertThat(c.onSignal(TouchSignal.DRAG_END)).isEqualTo(GestureIntent.NONE)
        assertThat(c.onSignal(TouchSignal.TAP)).isEqualTo(GestureIntent.START)
    }

    // ---- PUSH-TO-TALK ----

    @Test
    fun `ptt down starts and release after window stops`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()

        assertThat(c.onSignal(TouchSignal.DOWN)).isEqualTo(GestureIntent.START)
        assertThat(c.state).isEqualTo(ButtonState.RECORDING)

        // Release não decide na hora: aguarda a janela do tap-rápido
        assertThat(c.onSignal(TouchSignal.UP)).isEqualTo(GestureIntent.NONE)
        assertThat(c.state).isEqualTo(ButtonState.RECORDING)

        now += WINDOW
        assertThat(c.onReleaseTimeout()).isEqualTo(GestureIntent.STOP)
        assertThat(c.state).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `ptt quick tap after release pauses instead of stopping`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()
        c.onSignal(TouchSignal.DOWN) // RECORDING
        c.onSignal(TouchSignal.UP)   // janela aberta

        now += WINDOW / 2
        assertThat(c.onSignal(TouchSignal.DOWN)).isEqualTo(GestureIntent.PAUSE)
        assertThat(c.state).isEqualTo(ButtonState.PAUSED)

        // O release do tap-rápido mantém PAUSED (não é Stop)
        assertThat(c.onSignal(TouchSignal.UP)).isEqualTo(GestureIntent.NONE)
        assertThat(c.state).isEqualTo(ButtonState.PAUSED)

        // E o timeout já não dispara Stop (a janela foi consumida pelo pause)
        now += WINDOW
        assertThat(c.onReleaseTimeout()).isEqualTo(GestureIntent.NONE)
        assertThat(c.state).isEqualTo(ButtonState.PAUSED)
    }

    @Test
    fun `ptt hold again resumes from paused`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()
        c.onSignal(TouchSignal.DOWN) // RECORDING
        c.onSignal(TouchSignal.UP)
        c.onSignal(TouchSignal.DOWN) // PAUSE (dentro da janela)
        c.onSignal(TouchSignal.UP)   // segue PAUSED

        // Segurar de novo retoma (decisão Yan 2026-06-27)
        assertThat(c.onSignal(TouchSignal.DOWN)).isEqualTo(GestureIntent.RESUME)
        assertThat(c.state).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `ptt drag while recording repositions and keeps recording`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()
        c.onSignal(TouchSignal.DOWN) // RECORDING

        assertThat(c.onSignal(TouchSignal.DRAG_START)).isEqualTo(GestureIntent.REPOSITION_ONLY)
        assertThat(c.state).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `release timeout without pending release is a no-op`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()

        assertThat(c.onReleaseTimeout()).isEqualTo(GestureIntent.NONE)
        assertThat(c.state).isEqualTo(ButtonState.IDLE_READY)
    }

    private companion object {
        const val WINDOW = 350L
    }
}
