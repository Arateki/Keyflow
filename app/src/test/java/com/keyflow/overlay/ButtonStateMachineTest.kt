package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode
import org.junit.Test

class ButtonStateMachineTest {

    @Test
    fun `toggle mode maps taps from idle to recording to sending`() {
        val machine = ButtonStateMachine(mode = RecordingMode.TOGGLE)

        assertThat(machine.state).isEqualTo(ButtonState.IDLE_READY)
        assertThat(machine.onTap()).isEqualTo(ButtonState.RECORDING)
        assertThat(machine.onTap()).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `push to talk maps hold and release to recording and sending`() {
        val machine = ButtonStateMachine(mode = RecordingMode.PUSH_TO_TALK)

        assertThat(machine.onTap()).isEqualTo(ButtonState.IDLE_READY)
        assertThat(machine.onHold()).isEqualTo(ButtonState.RECORDING)
        assertThat(machine.onRelease()).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `target changes update idle states only`() {
        val machine = ButtonStateMachine()

        machine.onTargetChanged(false)
        assertThat(machine.state).isEqualTo(ButtonState.IDLE_NO_FIELD)

        machine.onTargetChanged(true)
        assertThat(machine.state).isEqualTo(ButtonState.IDLE_READY)

        machine.onTap()
        machine.onTargetChanged(false)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `success returns to idle ready when target is available`() {
        val machine = ButtonStateMachine(targetAvailable = { true })
        machine.onTap()
        machine.onTap()

        assertThat(machine.onTranscriptionSuccess()).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `success returns to idle no field when target is unavailable`() {
        val machine = ButtonStateMachine(targetAvailable = { false })
        machine.onTargetChanged(true)
        machine.onTap()
        machine.onTap()

        assertThat(machine.onTranscriptionSuccess()).isEqualTo(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun `target change does not override recording or sending state`() {
        val machine = ButtonStateMachine()

        machine.onTap() // RECORDING
        machine.onTargetChanged(false)
        assertThat(machine.state).isEqualTo(ButtonState.RECORDING)

        machine.onTap() // SENDING
        machine.onTargetChanged(false)
        assertThat(machine.state).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `pause moves recording to paused and resume returns to recording`() {
        val machine = ButtonStateMachine(mode = RecordingMode.TOGGLE)

        machine.onTap() // RECORDING
        assertThat(machine.onPause()).isEqualTo(ButtonState.PAUSED)
        assertThat(machine.onResume()).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `stop from paused goes to sending`() {
        val machine = ButtonStateMachine()

        machine.onTap()   // RECORDING
        machine.onPause() // PAUSED
        assertThat(machine.onStop()).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `stop from recording goes to sending`() {
        val machine = ButtonStateMachine()

        machine.onTap() // RECORDING
        assertThat(machine.onStop()).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `pause is a no-op outside recording and resume outside paused`() {
        val machine = ButtonStateMachine()

        // Idle: pause/resume do nothing
        assertThat(machine.onPause()).isEqualTo(ButtonState.IDLE_READY)
        assertThat(machine.onResume()).isEqualTo(ButtonState.IDLE_READY)

        machine.onTap() // RECORDING
        // Already recording: resume is a no-op
        assertThat(machine.onResume()).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `target change does not override paused state`() {
        val machine = ButtonStateMachine()

        machine.onTap()   // RECORDING
        machine.onPause() // PAUSED
        machine.onTargetChanged(false)
        assertThat(machine.state).isEqualTo(ButtonState.PAUSED)
    }

    @Test
    fun `target change does not override cancelling state`() {
        // Regressão: abrir o teclado durante a janela de desfazer fazia um onTargetChanged sair de
        // CANCELLING para idle, orfanizando o cancelJob (recorder nunca descartado → gravar dava erro).
        val machine = ButtonStateMachine()

        machine.onTap()    // RECORDING
        machine.onCancel() // CANCELLING
        machine.onTargetChanged(true)
        assertThat(machine.state).isEqualTo(ButtonState.CANCELLING)
        machine.onTargetChanged(false)
        assertThat(machine.state).isEqualTo(ButtonState.CANCELLING)
    }

    @Test
    fun `cancel moves recording to cancelling`() {
        val machine = ButtonStateMachine()

        machine.onTap() // RECORDING
        assertThat(machine.onCancel()).isEqualTo(ButtonState.CANCELLING)
    }

    @Test
    fun `cancel moves paused to cancelling`() {
        val machine = ButtonStateMachine()

        machine.onTap()   // RECORDING
        machine.onPause() // PAUSED
        assertThat(machine.onCancel()).isEqualTo(ButtonState.CANCELLING)
    }

    @Test
    fun `cancel is a no-op outside recording or paused`() {
        val machine = ButtonStateMachine()

        // Idle
        assertThat(machine.onCancel()).isEqualTo(ButtonState.IDLE_READY)

        machine.onTap() // RECORDING
        machine.onTap() // SENDING
        assertThat(machine.onCancel()).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `undo cancel returns to paused`() {
        val machine = ButtonStateMachine()

        machine.onTap()    // RECORDING
        machine.onCancel() // CANCELLING
        assertThat(machine.onUndoCancel()).isEqualTo(ButtonState.PAUSED)
    }

    @Test
    fun `undo cancel is a no-op outside cancelling`() {
        val machine = ButtonStateMachine()

        machine.onTap() // RECORDING
        assertThat(machine.onUndoCancel()).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `cancel expired returns to idle ready when target available`() {
        val machine = ButtonStateMachine(targetAvailable = { true })

        machine.onTap()    // RECORDING
        machine.onCancel() // CANCELLING
        assertThat(machine.onCancelExpired()).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `cancel expired returns to idle no field when target unavailable`() {
        val machine = ButtonStateMachine(targetAvailable = { false })

        machine.onTargetChanged(true)
        machine.onTap()    // RECORDING
        machine.onCancel() // CANCELLING
        assertThat(machine.onCancelExpired()).isEqualTo(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun `cancel expired is a no-op outside cancelling`() {
        val machine = ButtonStateMachine()

        machine.onTap() // RECORDING
        assertThat(machine.onCancelExpired()).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `discard moves recording directly to idle without the undo window`() {
        val machine = ButtonStateMachine(targetAvailable = { true })

        machine.onTap() // RECORDING
        assertThat(machine.onDiscard()).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `discard moves paused directly to idle without the undo window`() {
        val machine = ButtonStateMachine(targetAvailable = { false })

        machine.onTap()    // RECORDING
        machine.onPause()  // PAUSED
        assertThat(machine.onDiscard()).isEqualTo(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun `discard is a no-op outside recording or paused`() {
        val machine = ButtonStateMachine()

        assertThat(machine.onDiscard()).isEqualTo(ButtonState.IDLE_READY)

        machine.onTap() // RECORDING
        machine.onTap() // SENDING
        assertThat(machine.onDiscard()).isEqualTo(ButtonState.SENDING)
    }

    @Test
    fun `error and dismiss map back through current target availability`() {
        var targetAvailable = false
        val machine = ButtonStateMachine(targetAvailable = { targetAvailable })

        assertThat(machine.onTranscriptionError()).isEqualTo(ButtonState.ERROR)
        assertThat(machine.onErrorDismissed()).isEqualTo(ButtonState.IDLE_NO_FIELD)

        targetAvailable = true

        assertThat(machine.onTranscriptionError()).isEqualTo(ButtonState.ERROR)
        assertThat(machine.onErrorDismissed()).isEqualTo(ButtonState.IDLE_READY)
    }
}
