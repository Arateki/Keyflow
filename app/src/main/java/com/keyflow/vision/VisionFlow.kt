package com.keyflow.vision

import com.keyflow.core.ButtonState

/** Etapa efêmera independente da origem do acionamento e da interface de seleção. */
enum class VisionFlowState { NONE, CONSENT, SELECTING, ACTIONS, WORKING, COPYING }

/** Entrada tipada que qualquer gatilho pode fornecer. */
data class VisionFlowRequest(val recordingState: ButtonState)

/** Fluxo não altera a máquina de gravação além de solicitar pausa antes do consentimento. */
class VisionFlow<T>(
    private val pause: () -> Unit,
    private val requestConsent: () -> Unit,
    private val showSelector: () -> Unit,
    private val cleanup: () -> Unit,
    private val onState: (VisionFlowState) -> Unit = {},
    private val onResult: (T) -> Unit,
) {
    var state: VisionFlowState = VisionFlowState.NONE
        private set

    fun start(request: VisionFlowRequest): Boolean {
        if (state != VisionFlowState.NONE || request.recordingState == ButtonState.SENDING ||
            request.recordingState == ButtonState.CANCELLING) return false
        if (request.recordingState == ButtonState.RECORDING) pause()
        set(VisionFlowState.CONSENT)
        requestConsent()
        return true
    }

    fun consentGranted() {
        if (state != VisionFlowState.CONSENT) return
        set(VisionFlowState.SELECTING)
        showSelector()
    }

    fun selected() {
        if (state == VisionFlowState.SELECTING) set(VisionFlowState.WORKING)
    }

    fun captured(value: T) {
        if (state != VisionFlowState.WORKING) return
        try { onResult(value) } finally { finish() }
    }

    fun finish() {
        if (state == VisionFlowState.NONE) return
        set(VisionFlowState.NONE)
        cleanup()
    }

    private fun set(next: VisionFlowState) { state = next; onState(next) }
}
