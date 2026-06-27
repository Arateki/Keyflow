package com.keyflow.overlay

import android.os.SystemClock
import android.util.Log
import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorKind
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
    private val onClipboard: (String) -> Unit = {},
    private val recordingClockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val machine = ButtonStateMachine(targetAvailable = targetAvailable)
    private var recordingStartMs = 0L

    companion object {
        private const val TAG = "KeyFlow.Coordinator"
        private const val MIN_RECORDING_MS = 400L
        private const val ERROR_AUTO_DISMISS_MS = 3000L
    }

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

        val prev = machine.state
        val next = if (prev == ButtonState.ERROR) machine.onErrorDismissed() else machine.onTap()
        if (next != prev) handleState(next)
    }

    fun onHold() {
        handleState(machine.onHold())
    }

    fun onRelease() {
        handleState(machine.onRelease())
    }

    private fun handleState(state: ButtonState) {
        render(state)
        when (state) {
            ButtonState.RECORDING -> startRecording()
            ButtonState.SENDING -> stopAndTranscribe()
            ButtonState.ERROR -> scheduleErrorDismiss()
            ButtonState.IDLE_READY -> Unit
            ButtonState.IDLE_NO_FIELD -> Unit
            ButtonState.PENDING_PASTE -> Unit
        }
    }

    private fun scheduleErrorDismiss() {
        scope.launch {
            delay(ERROR_AUTO_DISMISS_MS)
            if (machine.state == ButtonState.ERROR) {
                render(machine.onErrorDismissed())
            }
        }
    }

    private fun startRecording() {
        recordingStartMs = recordingClockMs()
        runCatching { recorder.start() }
            .onFailure { handleState(machine.onTranscriptionError()) }
    }

    private fun stopAndTranscribe() {
        val duration = recordingClockMs() - recordingStartMs
        if (duration < MIN_RECORDING_MS) {
            Log.d(TAG, "recording too short (${duration}ms), cancelling")
            runCatching { recorder.stop() }
            render(machine.onErrorDismissed())
            return
        }

        val audio = runCatching { recorder.stop() }.getOrNull()
        if (audio == null) {
            handleState(machine.onTranscriptionError())
            return
        }

        scope.launch {
            val result = runCatching { transcriber.transcribe(audio, configProvider()) }
                .getOrElse { TranscriptionResult.Error(ErrorKind.NETWORK, it.message.orEmpty()) }

            when (result) {
                is TranscriptionResult.Success -> {
                    if (insertOrHold(result.text) == InsertOutcome.Held) {
                        Log.d(TAG, "transcription held → clipboard: '${result.text.take(30)}'")
                        onClipboard(result.text)
                    }
                    render(machine.onTranscriptionSuccess())
                }
                is TranscriptionResult.Error -> handleState(machine.onTranscriptionError())
            }
        }
    }

    private fun ButtonStateMachine.onTargetChangedAndReturn(available: Boolean): ButtonState {
        onTargetChanged(available)
        return state
    }
}
