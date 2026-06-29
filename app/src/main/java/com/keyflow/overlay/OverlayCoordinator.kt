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
    fun pause() {}
    fun resume() {}
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
    private val onShowKeyboardPicker: () -> Unit = {},
    // (mensagem, abreVoz): abreVoz = erro resolvível na aba Voz (config/token) → tocar no X leva lá.
    private val onError: (String, Boolean) -> Unit = { _, _ -> },
    private val recordingClockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val machine = ButtonStateMachine(targetAvailable = targetAvailable)
    private var recordingStartMs = 0L

    /** Estado atual da única [ButtonStateMachine] — lido pelo [RecordingGestureController]. */
    fun currentState(): ButtonState = machine.state

    companion object {
        private const val TAG = "KeyFlow.Coordinator"
        private const val MIN_RECORDING_MS = 400L
        private const val ERROR_AUTO_DISMISS_MS = 2000L
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

    /** Aplica uma intenção do [RecordingGestureController] à máquina + efeitos (gravação/pausa). */
    fun onIntent(intent: GestureIntent) {
        when (intent) {
            GestureIntent.START -> handleState(machine.onStart())   // → startRecording
            GestureIntent.STOP -> handleState(machine.onStop())     // → stopAndTranscribe
            GestureIntent.PAUSE -> {
                runCatching { recorder.pause() }
                render(machine.onPause())
            }
            GestureIntent.RESUME -> {
                runCatching { recorder.resume() }
                render(machine.onResume())
            }
            GestureIntent.KEYBOARD_PICKER -> onShowKeyboardPicker()
            GestureIntent.REPOSITION_ONLY, GestureIntent.NONE -> Unit
        }
    }

    private fun handleState(state: ButtonState) {
        render(state)
        when (state) {
            ButtonState.RECORDING -> startRecording()
            ButtonState.SENDING -> stopAndTranscribe()
            ButtonState.ERROR -> scheduleErrorDismiss()
            ButtonState.IDLE_READY -> Unit
            ButtonState.IDLE_NO_FIELD -> Unit
            ButtonState.PAUSED -> Unit // orquestração de pausa chega na T22
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
            .onFailure {
                onError("Falha ao iniciar a gravação.", false)
                handleState(machine.onTranscriptionError())
            }
    }

    private fun stopAndTranscribe() {
        val duration = recordingClockMs() - recordingStartMs
        if (duration < MIN_RECORDING_MS) {
            Log.d(TAG, "recording too short (${duration}ms), cancelling")
            runCatching { recorder.stop() }
            onError("Gravação muito curta.", false)
            render(machine.onErrorDismissed())
            return
        }

        val audio = runCatching { recorder.stop() }.getOrNull()
        if (audio == null) {
            onError("Não foi possível gravar o áudio.", false)
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
                is TranscriptionResult.Error -> {
                    Log.d(TAG, "transcription error: ${result.kind} ${result.message}")
                    onError(errorMessage(result.kind), opensVoz(result.kind))
                    handleState(machine.onTranscriptionError())
                }
            }
        }
    }

    /** Erros resolvíveis na aba Voz (config/token) — só estes tornam o X clicável para o atalho. */
    private fun opensVoz(kind: ErrorKind): Boolean =
        kind == ErrorKind.CONFIG_MISSING || kind == ErrorKind.AUTH

    private fun errorMessage(kind: ErrorKind): String = when (kind) {
        ErrorKind.CONFIG_MISSING -> "Configure um provedor de voz na aba Voz."
        ErrorKind.AUTH -> "Falha de autenticação — verifique o token."
        ErrorKind.NETWORK -> "Sem conexão de rede."
        ErrorKind.TIMEOUT -> "Tempo esgotado ao transcrever."
        ErrorKind.TOO_LARGE -> "Áudio muito longo para transcrever."
        ErrorKind.EMPTY_AUDIO -> "Nada foi captado pelo microfone."
        ErrorKind.HTTP -> "Erro no servidor de transcrição."
    }

    private fun ButtonStateMachine.onTargetChangedAndReturn(available: Boolean): ButtonState {
        onTargetChanged(available)
        return state
    }
}
