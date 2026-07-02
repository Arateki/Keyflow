package com.keyflow.overlay

import android.os.SystemClock
import android.util.Log
import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorKind
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    // Aviso "enviar ou descartar" ao atingir Settings.maxRecordingSec (0 = desabilitado; lido ao vivo,
    // como os demais ajustes editáveis durante o uso — ver tapBurstWindowMs no OverlayService).
    private val onShowLimitReached: () -> Unit = {},
    private val onHideLimitReached: () -> Unit = {},
    private val maxRecordingMs: () -> Long = { 0L },
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
    private var limitJob: kotlinx.coroutines.Job? = null
    private var limitBannerVisible = false

    // Tempo de gravação (acumulado + início do segmento atual), publicado a cada transição.
    private var timeAccumulatedMs = 0L
    private var timeSegmentStartMs = 0L
    private fun publishTime() = onRecordingTime(timeAccumulatedMs, timeSegmentStartMs)
    private fun timeStart() { timeAccumulatedMs = 0L; timeSegmentStartMs = recordingClockMs(); publishTime() }
    private fun timeResume() { timeSegmentStartMs = recordingClockMs(); publishTime() }
    private fun timeFreeze() { timeAccumulatedMs += recordingClockMs() - timeSegmentStartMs; publishTime() }
    private fun timeReset() { timeAccumulatedMs = 0L; timeSegmentStartMs = recordingClockMs(); publishTime() }

    /**
     * (Re)agenda o aviso de limite máximo de gravação para o tempo restante (maxRecordingMs -
     * timeAccumulatedMs). Não reagenda se o limite já foi atingido (evita disparar de novo ao retomar
     * uma gravação que já passou do limite).
     */
    private fun scheduleLimitJob() {
        limitJob?.cancel()
        limitJob = null
        val max = maxRecordingMs()
        if (max <= 0L) return
        val remaining = max - timeAccumulatedMs
        if (remaining <= 0L) return
        limitJob = scope.launch {
            delay(remaining)
            onLimitReached()
        }
    }

    private fun cancelLimitJob() {
        limitJob?.cancel()
        limitJob = null
    }

    private fun showLimitBanner() {
        if (limitBannerVisible) return
        limitBannerVisible = true
        onShowLimitReached()
    }

    private fun hideLimitBanner() {
        if (!limitBannerVisible) return
        limitBannerVisible = false
        onHideLimitReached()
    }

    /** Atingido o tempo configurado: pausa a gravação (retém o áudio) e avisa "enviar ou descartar". */
    private fun onLimitReached() {
        if (machine.state != ButtonState.RECORDING) return
        runCatching { recorder.pause() }
        timeFreeze()
        render(machine.onPause())
        showLimitBanner()
    }

    /** "Enviar" no aviso de limite: mesmo caminho de um STOP normal a partir de PAUSED. */
    fun onLimitSend() {
        if (machine.state != ButtonState.PAUSED) return
        hideLimitBanner()
        handleState(machine.onStop())
    }

    /** "Descartar" no aviso de limite: descarta na hora, sem a janela de desfazer do cancelar. */
    fun onLimitDiscard() {
        if (machine.state != ButtonState.PAUSED) return
        hideLimitBanner()
        cancelLimitJob()
        runCatching { recorder.discard() }
        timeReset()
        render(machine.onDiscard())
    }

    /**
     * Cancela a gravação em curso retendo o áudio (pausa o recorder, não descarta na hora) e abre a
     * janela de desfazer. Ao expirar sem desfazer, descarta de vez e volta ao idle (Fase 2a).
     */
    fun onCancel() {
        val before = machine.state
        val next = machine.onCancel()
        if (next != ButtonState.CANCELLING || before == ButtonState.CANCELLING) return
        hideLimitBanner()
        cancelLimitJob()
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
        // Margem acima de cfg.timeoutMs — dá tempo do callTimeout do OkHttp (a rede primária) surgir
        // primeiro no caso comum; esta é só a última linha de defesa.
        private const val TIMEOUT_SAFETY_MARGIN_MS = 2000L
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
                cancelLimitJob()
                runCatching { recorder.pause() }
                timeFreeze()
                render(machine.onPause())
            }
            GestureIntent.RESUME -> {
                runCatching { recorder.resume() }
                timeResume()
                scheduleLimitJob()
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
        scheduleLimitJob()
        runCatching { recorder.start() }
            .onFailure {
                onError("Falha ao iniciar a gravação.", false)
                handleState(machine.onTranscriptionError())
            }
    }

    private fun stopAndTranscribe() {
        hideLimitBanner()
        cancelLimitJob()
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
            val cfg = configProvider()
            // Rede de segurança além do callTimeout do WhisperApi (Yan, 2026-07-01): garante que o
            // coordinator sempre desiste dentro de cfg.timeoutMs + margem, mesmo se o transcriber travar
            // por um caminho que o OkHttp não cobre (ex.: implementação customizada de OverlayTranscriber).
            val result = runCatching {
                withTimeout(cfg.timeoutMs + TIMEOUT_SAFETY_MARGIN_MS) { transcriber.transcribe(audio, cfg) }
            }.getOrElse { e ->
                if (e is TimeoutCancellationException) {
                    TranscriptionResult.Error(ErrorKind.TIMEOUT, "client-side timeout after ${cfg.timeoutMs}ms")
                } else {
                    TranscriptionResult.Error(ErrorKind.NETWORK, e.message.orEmpty())
                }
            }

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
