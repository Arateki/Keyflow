package com.keyflow.overlay

import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode

class ButtonStateMachine(
    private val mode: RecordingMode = RecordingMode.TOGGLE,
    private val targetAvailable: () -> Boolean = { true },
) {
    var state: ButtonState = if (targetAvailable()) ButtonState.IDLE_READY else ButtonState.IDLE_NO_FIELD
        private set

    fun onTargetChanged(available: Boolean) {
        state = when {
            state == ButtonState.RECORDING || state == ButtonState.SENDING -> state
            available -> ButtonState.IDLE_READY
            else -> ButtonState.IDLE_NO_FIELD
        }
    }

    fun onTap(): ButtonState {
        state = when (state) {
            ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD ->
                if (mode == RecordingMode.TOGGLE) ButtonState.RECORDING else state
            ButtonState.RECORDING ->
                if (mode == RecordingMode.TOGGLE) ButtonState.SENDING else state
            ButtonState.PENDING_PASTE -> ButtonState.IDLE_READY
            else -> state
        }
        return state
    }

    fun onHold(): ButtonState {
        state = when (state) {
            ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD -> ButtonState.RECORDING
            else -> state
        }
        return state
    }

    fun onRelease(): ButtonState {
        state = when (state) {
            ButtonState.RECORDING -> ButtonState.SENDING
            else -> state
        }
        return state
    }

    fun onTranscriptionSuccess(): ButtonState {
        state = if (targetAvailable()) ButtonState.IDLE_READY else ButtonState.PENDING_PASTE
        return state
    }

    fun onTranscriptionError(): ButtonState {
        state = ButtonState.ERROR
        return state
    }

    fun onErrorDismissed(): ButtonState {
        state = if (targetAvailable()) ButtonState.IDLE_READY else ButtonState.IDLE_NO_FIELD
        return state
    }
}
