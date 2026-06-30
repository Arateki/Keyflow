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
    /** Encerra e descarta a gravação sem devolver o arquivo (cancelar — Fase 2a). */
    fun discard() {}
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
    // Banner clicável "cancelado · desfazer" durante a janela de descarte (Fase 2a).
    private val onShowCancelUndo: () -> Unit = {},
    private val onHideCancelUndo: () -> Unit = {},
    private val cancelWindowMs: Long = DEFAULT_CANCEL_WINDOW_MS,
    // Tempo autoritativo de gravação (acumulado, início do segmento) — superfícies leem daqui p/ não
    // dessincronizar (ex.: teclado que sobe depois de transições já terem ocorrido).
    private val onRecordingTime: (accumulatedMs: Long, segmentStartMs: Long) -> Unit = { _, _ -> },
    // (mensagem, abreVoz): abreVoz = erro resolvível na aba Voz (config/token) → tocar no X leva lá.
    private val onError: (String, Boolean) -> Unit = { _, _ -> },
    private val recordingClockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val machine = ButtonStateMachine(targetAvailable = targetAvailable)
    private var recordingStartMs = 0L
    private var cancelJob: kotlinx.coroutines.Job? = null

    // Tempo de gravação (acumulado + início do segmento atual), publicado a cada transição.
    private var timeAccumulatedMs = 0L
    private var timeSegmentStartMs = 0L
    private fun publishTime() = onRecordingTime(timeAccumulatedMs, timeSegmentStartMs)
    private fun timeStart() { timeAccumulatedMs = 0L; timeSegmentStartMs = recordingClockMs(); publishTime() }
    private fun timeResume() { timeSegmentStartMs = recordingClockMs(); publishTime() }
    private fun timeFreeze() { timeAccumulatedMs += recordingClockMs() - timeSegmentStartMs; publishTime() }
    private fun timeReset() { timeAccumulatedMs = 0L; timeSegmentStartMs = recordingClockMs(); publishTime() }

    /**
     * Cancela a gravação em curso retendo o áudio (pausa o recorder, não descarta na hora) e abre a
     * janela de desfazer. Ao expirar sem desfazer, descarta de vez e volta ao idle (Fase 2a).
     */
    fun onCancel() {
        val before = machine.state
        val next = machine.onCancel()
        if (next != ButtonState.CANCELLING || before == ButtonState.CANCELLING) return
        runCatching { recorder.pause() }
        if (before == ButtonState.RECORDING) timeFreeze() // congela; vindo de PAUSED já estava congelado
        render(next)
        onShowCancelUndo()
        cancelJob?.cancel()
        cancelJob = scope.launch {
            delay(cancelWindowMs)
            finalizeCancel()
        }
    }

    /** Desfaz o cancelamento dentro da janela (CANCELLING → PAUSED); o áudio segue retido/pausado. */
    fun onUndoCancel() {
        if (machine.state != ButtonState.CANCELLING) return
        cancelJob?.cancel()
        cancelJob = null
        render(machine.onUndoCancel())
        onHideCancelUndo()
    }

    /** Conclui o cancelamento na hora (toque na orbe/mic): descarta já e vai pra idle, sem esperar a janela. */
    fun onCommitCancel() = finalizeCancel()

    /** Descarta o áudio retido e volta ao idle. Chamado pela expiração da janela ou pelo "concluir agora". */
    private fun finalizeCancel() {
        if (machine.state != ButtonState.CANCELLING) return
        cancelJob?.cancel()
        cancelJob = null
        runCatching { recorder.discard() }
        timeReset()
        render(machine.onCancelExpired())
        onHideCancelUndo()
    }

    /** Estado atual da única [ButtonStateMachine] — lido pelo [RecordingGestureController]. */
    fun currentState(): ButtonState = machine.state

    companion object {
        private const val TAG = "KeyFlow.Coordinator"
        private const val MIN_RECORDING_MS = 400L
        private const val ERROR_AUTO_DISMISS_MS = 2000L
        private const val DEFAULT_CANCEL_WINDOW_MS = 3000L
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
                timeFreeze()
                render(machine.onPause())
            }
            GestureIntent.RESUME -> {
                runCatching { recorder.resume() }
                timeResume()
                render(machine.onResume())
            }
            GestureIntent.CANCEL -> onCancel()
            GestureIntent.CANCEL_COMMIT -> onCommitCancel()
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
            ButtonState.CANCELLING -> Unit // efeitos de cancelar são orquestrados em onCancel/onUndoCancel
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
        timeStart()
        runCatching { recorder.start() }
            .onFailure {
                onError("Falha ao iniciar a gravação.", false)
                handleState(machine.onTranscriptionError())
            }
    }

    private fun stopAndTranscribe() {
        timeReset() // gravação encerrada: zera o tempo autoritativo
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
