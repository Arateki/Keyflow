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
            state == ButtonState.RECORDING || state == ButtonState.PAUSED ||
                state == ButtonState.SENDING -> state
            // Mantém PENDING_PASTE: o auto-flush do Controller já tenta inserir —
            // se falhar, o botão fica em PA para o usuário confirmar manualmente
            state == ButtonState.PENDING_PASTE -> state
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

    /** Inicia a gravação a partir de IDLE (independente do modo). No-op fora de IDLE. */
    fun onStart(): ButtonState {
        if (state == ButtonState.IDLE_READY || state == ButtonState.IDLE_NO_FIELD) {
            state = ButtonState.RECORDING
        }
        return state
    }

    /** Pausa a gravação (RECORDING → PAUSED). No-op em qualquer outro estado (REC-01). */
    fun onPause(): ButtonState {
        if (state == ButtonState.RECORDING) state = ButtonState.PAUSED
        return state
    }

    /** Retoma a gravação (PAUSED → RECORDING). No-op em qualquer outro estado (REC-02). */
    fun onResume(): ButtonState {
        if (state == ButtonState.PAUSED) state = ButtonState.RECORDING
        return state
    }

    /** Encerra a gravação (RECORDING ou PAUSED → SENDING). */
    fun onStop(): ButtonState {
        if (state == ButtonState.RECORDING || state == ButtonState.PAUSED) {
            state = ButtonState.SENDING
        }
        return state
    }

    fun onTranscriptionSuccess(): ButtonState {
        state = if (targetAvailable()) ButtonState.IDLE_READY else ButtonState.IDLE_NO_FIELD
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
