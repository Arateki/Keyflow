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
