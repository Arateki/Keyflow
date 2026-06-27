package com.keyflow.overlay

import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorKind
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

interface OverlayAudioRecorder {
    fun start()
    fun stop(): File?
}

interface OverlayTranscriber {
    suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult
}

class OverlayCoordinator(
    private val scope: CoroutineScope,
    private val configProvider: suspend () -> Config,
    private val recorder: OverlayAudioRecorder,
    private val transcriber: OverlayTranscriber,
    private val insertOrHold: (String) -> InsertOutcome,
    private val flushPending: () -> Boolean,
    targetAvailable: () -> Boolean,
    private val render: (ButtonState) -> Unit,
) {
    private val machine = ButtonStateMachine(targetAvailable = targetAvailable)

    init {
        render(machine.state)
    }

    fun onTargetChanged(available: Boolean) {
        render(machine.onTargetChangedAndReturn(available))
    }

    fun onTap() {
        if (machine.state == ButtonState.PENDING_PASTE) {
            if (flushPending()) {
                render(machine.onTargetChangedAndReturn(true))
            }
            return
        }

        handleState(machine.onTap())
    }

    fun onHold() {
        handleState(machine.onHold())
    }

    fun onRelease() {
        handleState(machine.onRelease())
    }

    fun onPendingChanged(pending: String?) {
        if (pending != null && machine.state != ButtonState.RECORDING && machine.state != ButtonState.SENDING) {
            render(ButtonState.PENDING_PASTE)
        }
    }

    private fun handleState(state: ButtonState) {
        render(state)
        when (state) {
            ButtonState.RECORDING -> startRecording()
            ButtonState.SENDING -> stopAndTranscribe()
            ButtonState.ERROR -> Unit
            ButtonState.IDLE_READY -> Unit
            ButtonState.IDLE_NO_FIELD -> Unit
            ButtonState.PENDING_PASTE -> Unit
        }
    }

    private fun startRecording() {
        runCatching { recorder.start() }
            .onFailure { render(machine.onTranscriptionError()) }
    }

    private fun stopAndTranscribe() {
        val audio = runCatching { recorder.stop() }.getOrNull()
        if (audio == null) {
            render(machine.onTranscriptionError())
            return
        }

        scope.launch {
            val result = runCatching { transcriber.transcribe(audio, configProvider()) }
                .getOrElse { TranscriptionResult.Error(ErrorKind.NETWORK, it.message.orEmpty()) }

            when (result) {
                is TranscriptionResult.Success -> {
                    insertOrHold(result.text)
                    render(machine.onTranscriptionSuccess())
                }
                is TranscriptionResult.Error -> render(machine.onTranscriptionError())
            }
        }
    }

    private fun ButtonStateMachine.onTargetChangedAndReturn(available: Boolean): ButtonState {
        onTargetChanged(available)
        return state
    }
}
