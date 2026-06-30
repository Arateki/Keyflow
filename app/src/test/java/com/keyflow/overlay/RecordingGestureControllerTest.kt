package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode
import org.junit.Test

class RecordingGestureControllerTest {

    private var now = 0L
    private var mode = RecordingMode.TOGGLE

    // Máquina única (como no OverlayCoordinator): o controller lê o estado, o teste aplica a transição.
    private val machine = ButtonStateMachine(targetAvailable = { true })

    private fun controller() = RecordingGestureController(
        mode = { mode },
        currentState = { machine.state },
        pttReleaseWindowMs = { WINDOW },
        clock = { now },
    )

    /** Aplica ao `machine` a transição correspondente ao intent — como faria o coordinator. */
    private fun RecordingGestureController.dispatch(signal: TouchSignal): GestureIntent {
        val intent = onSignal(signal)
        applyToMachine(intent)
        return intent
    }

    private fun applyToMachine(intent: GestureIntent) {
        when (intent) {
            GestureIntent.START -> machine.onStart()
            GestureIntent.STOP -> machine.onStop()
            GestureIntent.PAUSE -> machine.onPause()
            GestureIntent.RESUME -> machine.onResume()
            else -> Unit
        }
    }

    // ---- TOGGLE ----

    @Test
    fun `toggle tap starts and a second tap stops`() {
        mode = RecordingMode.TOGGLE
        val c = controller()

        assertThat(c.dispatch(TouchSignal.TAP)).isEqualTo(GestureIntent.START)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)

        assertThat(c.dispatch(TouchSignal.TAP)).isEqualTo(GestureIntent.STOP)
        assertThat(machine.state).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `toggle tap during cancelling commits the cancel`() {
        mode = RecordingMode.TOGGLE
        val c = controller()
        c.dispatch(TouchSignal.TAP) // RECORDING
        machine.onCancel()          // CANCELLING
        assertThat(c.onSignal(TouchSignal.TAP)).isEqualTo(GestureIntent.CANCEL_COMMIT)
    }

    @Test
    fun `ptt tap during cancelling commits the cancel`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()
        machine.onStart()  // RECORDING
        machine.onCancel() // CANCELLING
        assertThat(c.onSignal(TouchSignal.TAP)).isEqualTo(GestureIntent.CANCEL_COMMIT)
    }

    @Test
    fun `toggle hold pauses and resumes recording`() {
        mode = RecordingMode.TOGGLE
        val c = controller()
        c.dispatch(TouchSignal.TAP) // RECORDING

        assertThat(c.dispatch(TouchSignal.HOLD)).isEqualTo(GestureIntent.PAUSE)
        assertThat(machine.state).isEqualTo(ButtonState.PAUSED)

        assertThat(c.dispatch(TouchSignal.HOLD)).isEqualTo(GestureIntent.RESUME)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `toggle tap while paused stops`() {
        mode = RecordingMode.TOGGLE
        val c = controller()
        c.dispatch(TouchSignal.TAP)  // RECORDING
        c.dispatch(TouchSignal.HOLD) // PAUSED

        assertThat(c.dispatch(TouchSignal.TAP)).isEqualTo(GestureIntent.STOP)
        assertThat(machine.state).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `triple tap requests keyboard picker without starting`() {
        mode = RecordingMode.TOGGLE
        val c = controller()

        assertThat(c.dispatch(TouchSignal.TRIPLE_TAP)).isEqualTo(GestureIntent.KEYBOARD_PICKER)
        assertThat(machine.state).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `drag only repositions and never starts recording`() {
        mode = RecordingMode.TOGGLE
        val c = controller()

        assertThat(c.dispatch(TouchSignal.DRAG_START)).isEqualTo(GestureIntent.REPOSITION_ONLY)
        // Um tap acidental no mesmo ciclo de arrasto é suprimido
        assertThat(c.dispatch(TouchSignal.TAP)).isEqualTo(GestureIntent.NONE)
        assertThat(machine.state).isEqualTo(ButtonState.IDLE_READY)

        // Após o arrasto terminar, taps voltam a iniciar a gravação
        assertThat(c.dispatch(TouchSignal.DRAG_END)).isEqualTo(GestureIntent.NONE)
        assertThat(c.dispatch(TouchSignal.TAP)).isEqualTo(GestureIntent.START)
    }

    // ---- PUSH-TO-TALK ----

    @Test
    fun `ptt down starts and release after window stops`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()

        assertThat(c.dispatch(TouchSignal.DOWN)).isEqualTo(GestureIntent.START)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)

        // Release não decide na hora: aguarda a janela do tap-rápido
        assertThat(c.dispatch(TouchSignal.UP)).isEqualTo(GestureIntent.NONE)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)

        now += WINDOW
        applyToMachine(c.onReleaseTimeout().also { assertThat(it).isEqualTo(GestureIntent.STOP) })
        assertThat(machine.state).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `ptt re-hold within window keeps recording without pausing`() {
        // Novo modelo (Yan): pausar no PTT é SÓ arrastar ↑. Re-segurar/tocar na janela de release
        // continua gravando sem costura — nunca pausa — e cancela o envio pendente.
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()
        c.dispatch(TouchSignal.DOWN) // RECORDING
        c.dispatch(TouchSignal.UP)   // janela aberta

        now += WINDOW / 2
        assertThat(c.dispatch(TouchSignal.DOWN)).isEqualTo(GestureIntent.NONE)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)

        // O envio pendente foi cancelado: o timeout já não dispara Stop.
        now += WINDOW
        assertThat(c.onReleaseTimeout()).isEqualTo(GestureIntent.NONE)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `ptt hold again resumes from paused`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()
        c.dispatch(TouchSignal.DOWN) // RECORDING
        // Pausa via arrasto ↑ chega pelo onIntent(PAUSE) (fora do fluxo de sinais); simula a transição.
        applyToMachine(GestureIntent.PAUSE)
        assertThat(machine.state).isEqualTo(ButtonState.PAUSED)

        // Segurar de novo retoma (decisão Yan 2026-06-27)
        assertThat(c.dispatch(TouchSignal.DOWN)).isEqualTo(GestureIntent.RESUME)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `ptt drag while recording repositions and keeps recording`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()
        c.dispatch(TouchSignal.DOWN) // RECORDING

        assertThat(c.dispatch(TouchSignal.DRAG_START)).isEqualTo(GestureIntent.REPOSITION_ONLY)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `release timeout without pending release is a no-op`() {
        mode = RecordingMode.PUSH_TO_TALK
        val c = controller()

        assertThat(c.onReleaseTimeout()).isEqualTo(GestureIntent.NONE)
        assertThat(machine.state).isEqualTo(ButtonState.IDLE_READY)
    }

    private companion object {
        const val WINDOW = 350L
    }
}
