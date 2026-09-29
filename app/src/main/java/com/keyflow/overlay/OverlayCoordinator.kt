package com.keyflow.overlay

import android.os.SystemClock
import android.util.Log
import com.keyflow.audio.AudioConcatenator
import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorKind
import com.keyflow.core.ErrorNavTarget
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import com.keyflow.transcription.partial.PartialSession
import com.keyflow.transcription.partial.PartialSnapshot
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

    /** Inicia no modo segmentado (transcrição parcial ao pausar, PAR-02). Sem suporte: sessão comum. */
    fun startSegmented() = start()

    /** Pausa fechando o trecho (modo segmentado) e o devolve; sem suporte, só pausa. */
    fun pauseSegment(): File? {
        pause()
        return null
    }
}

interface OverlayTranscriber {
    suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult

    /** Aborta a requisição em andamento (chamada ao cancelar a partir de SENDING). No-op se não houver. */
    fun cancelInFlight() {}
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
    // Tempo em tela do banner de limite antes de sumir sozinho (a decisão continua pendente — só a
    // view some; ver retriggerLimitBanner()).
    private val limitBannerDismissMs: Long = DEFAULT_LIMIT_BANNER_DISMISS_MS,
    // Tempo autoritativo de gravação (acumulado, início do segmento) — superfícies leem daqui p/ não
    // dessincronizar (ex.: teclado que sobe depois de transições já terem ocorrido).
    private val onRecordingTime: (accumulatedMs: Long, segmentStartMs: Long) -> Unit = { _, _ -> },
    // (mensagem, ação): ver [ErrorNavTarget] — decide o que tocar no X/glifo ✖ faz.
    private val onError: (String, ErrorNavTarget) -> Unit = { _, _ -> },
    private val recordingClockMs: () -> Long = { SystemClock.elapsedRealtime() },
    // Checagem proativa antes de gravar (RECORD_AUDIO) — sem ela, só se descobre a falta na hora que o
    // recorder nativo explode dentro do runCatching (Yan, 2026-07-04: as 3 superfícies de gravação —
    // orbe, teclado, notificação — davam só um toast genérico "falha ao iniciar", sem dizer o motivo nem
    // levar a lugar nenhum).
    private val microphoneGranted: () -> Boolean = { true },
    // Funde os segmentos retidos num arquivo só antes de reenviar (ver stopAndTranscribe()). Injetável
    // porque o real usa MediaExtractor/MediaMuxer (framework Android, não roda em unit test puro).
    private val concatAudioSegments: (List<File>, File) -> File = { segments, dir -> AudioConcatenator.concat(segments, dir) },
    // Transcrição parcial ao pausar (G12): lido no início de cada sessão — mudar no meio vale para a próxima.
    private val partialOnPause: () -> Boolean = { false },
    // Estado do parcial para a UI junto à orbe (PAR-04/05); null quando a sessão acaba.
    private val onPartial: (PartialSnapshot?) -> Unit = {},
) {
    private val machine = ButtonStateMachine(targetAvailable = targetAvailable)
    private var recordingStartMs = 0L
    private var cancelJob: kotlinx.coroutines.Job? = null
    private var limitJob: kotlinx.coroutines.Job? = null
    private var bannerDismissJob: kotlinx.coroutines.Job? = null
    private var transcribeJob: kotlinx.coroutines.Job? = null
    // Áudio já gravado e retido enquanto uma decisão está pendente após SENDING (cancelar durante a
    // transcrição). Diferente do cancelar a partir de RECORDING/PAUSED: ali o recorder nunca foi parado
    // (só pausado), então dá pra retomar a MESMA sessão; aqui o recorder já foi liberado em stop() — a
    // retomada abre uma sessão NOVA e funde os dois trechos no próximo stop (ver [continuingPendingAudio]).
    private var pendingAudioFile: File? = null
    // true = a retomada após desfazer um cancelamento em SENDING iniciou uma NOVA sessão de gravação
    // (continuação); o próximo stop precisa FUNDIR pendingAudioFile + o novo segmento (ver
    // stopAndTranscribe()), em vez de só reenviar pendingAudioFile como está.
    private var continuingPendingAudio = false
    // Soma da duração de todos os segmentos já fundidos em pendingAudioFile (Yan, 2026-07-03: sem isso,
    // o limite de gravação reiniciaria do zero a cada ciclo cancelar-durante-envio→desfazer→continuar,
    // reabrindo uma variante do bug de burlar o teto já corrigido no Q4). Zerada quando o áudio é enviado
    // de vez ou descartado.
    private var priorSegmentsMs = 0L
    // Incrementado a cada tentativa de envio (stopAndTranscribe) — distingue uma conclusão tardia de uma
    // tentativa já cancelada de uma tentativa nova (reenvio), mesmo se ambas coincidirem em SENDING.
    private var sendAttempt = 0
    // true = a gravação já bateu o limite e a decisão "enviar ou descartar" ainda não foi resolvida.
    // Persiste mesmo que a VIEW do banner já tenha sumido (sozinha, ou por entrar em CANCELLING) — só é
    // limpa quando o áudio é de fato enviado ou descartado. Ver onCancel()/finalizeCancel() para o porquê:
    // sem essa separação entre "decisão pendente" e "banner visível", cancelar+desfazer+retomar burlava o
    // limite de vez (bug reportado pelo Yan, 2026-07-03).
    private var limitDecisionPending = false

    // Sessão com transcrição parcial (G12): cada pausa fecha um trecho e o transcreve para o buffer.
    // Vive da gravação até a entrega ou o cancelamento concluído — sobrevive ao "desfazer" (PAR-08).
    private var partial: PartialSession? = null
    // true = o recorder da sessão parcial foi encerrado (envio que foi cancelado e desfeito): retomar abre
    // uma nova gravação segmentada, e os trechos continuam na mesma sessão.
    private var partialRecorderStopped = false

    /** Edição do texto parcial pelo usuário (PAR-06) — o que ele escreveu passa a ser o buffer. */
    fun onPartialEdited(text: String) {
        partial?.edit(text)
    }

    /** Pausa o recorder; na sessão parcial, o trecho fechado vai para a fila de transcrição (PAR-03). */
    private fun pauseRecorder() {
        val session = partial
        if (session == null) {
            runCatching { recorder.pause() }
            return
        }
        if (partialRecorderStopped) return
        runCatching { recorder.pauseSegment() }.getOrNull()?.let(session::enqueue)
    }

    private fun endPartial() {
        partial?.discard()
        partial = null
        partialRecorderStopped = false
        onPartial(null)
    }

    // Tempo de gravação (acumulado + início do segmento atual), publicado a cada transição.
    private var timeAccumulatedMs = 0L
    private var timeSegmentStartMs = 0L
    private fun publishTime() = onRecordingTime(timeAccumulatedMs, timeSegmentStartMs)
    private fun timeStart() { timeAccumulatedMs = 0L; timeSegmentStartMs = recordingClockMs(); publishTime() }
    private fun timeResume() { timeSegmentStartMs = recordingClockMs(); publishTime() }
    // Idempotente (reseta timeSegmentStartMs após acumular): chamar de novo enquanto já congelado soma
    // ~0 em vez de contar o mesmo intervalo duas vezes — permite chamar sem saber se já estava congelado
    // (ver stopAndTranscribe(), que precisa do total tanto vindo de RECORDING quanto de PAUSED).
    private fun timeFreeze() {
        timeAccumulatedMs += recordingClockMs() - timeSegmentStartMs
        timeSegmentStartMs = recordingClockMs()
        publishTime()
    }
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
        val remaining = max - priorSegmentsMs - timeAccumulatedMs
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
        if (limitDecisionPending) return
        limitDecisionPending = true
        onShowLimitReached()
        scheduleBannerDismiss()
    }

    /**
     * Esconde só a VIEW do banner de limite — não resolve a decisão pendente. Usado ao entrar em
     * CANCELLING (o banner de cancelar substitui o de limite na tela, mas se o usuário desfizer o
     * cancelamento, retomar ainda deve respeitar o limite). Quem resolve a decisão de fato
     * (enviar/descartar para valer) limpa [limitDecisionPending] explicitamente.
     */
    private fun hideLimitBannerView() {
        bannerDismissJob?.cancel()
        bannerDismissJob = null
        onHideLimitReached()
    }

    /** Some sozinho após [limitBannerDismissMs]: só a view, a decisão pendente continua. */
    private fun scheduleBannerDismiss() {
        bannerDismissJob?.cancel()
        bannerDismissJob = scope.launch {
            delay(limitBannerDismissMs)
            onHideLimitReached()
        }
    }

    /**
     * Reage a uma tentativa de retomar a gravação enquanto a decisão "enviar ou descartar" ainda está
     * pendente: reacende o banner do zero (novos 3s) em vez de deixar a gravação continuar sem teto.
     */
    private fun retriggerLimitBanner() {
        onShowLimitReached()
        scheduleBannerDismiss()
    }

    /** Atingido o tempo configurado: pausa a gravação (retém o áudio) e avisa "enviar ou descartar". */
    private fun onLimitReached() {
        if (machine.state != ButtonState.RECORDING) return
        pauseRecorder()
        timeFreeze()
        render(machine.onPause())
        showLimitBanner()
    }

    /**
     * "Enviar" no aviso de limite: mesmo caminho de um STOP normal a partir de PAUSED — o próprio
     * [stopAndTranscribe] já limpa a decisão pendente e esconde o banner, sem duplicar aqui.
     */
    fun onLimitSend() {
        if (machine.state != ButtonState.PAUSED) return
        handleState(machine.onStop())
    }

    /**
     * "Descartar" no aviso de limite: reusa o mesmo fluxo do cancelar comum (retém o áudio e abre a
     * janela de desfazer), em vez de um descarte instantâneo e separado — um único padrão de descarte
     * em toda a UI (pedido do Yan, 2026-07-03).
     */
    fun onLimitDiscard() {
        if (machine.state != ButtonState.PAUSED) return
        onCancel()
    }

    /**
     * Cancela a gravação/envio em curso retendo o áudio (não descarta na hora) e abre a janela de
     * desfazer. Ao expirar sem desfazer, descarta de vez e volta ao idle (Fase 2a). A partir de SENDING
     * (pedido do Yan, 2026-07-03) aborta a requisição em vez de pausar o recorder — o áudio já tinha sido
     * entregue ao transcriber, então não há recorder pra pausar.
     */
    fun onCancel() {
        val before = machine.state
        val next = machine.onCancel()
        if (next != ButtonState.CANCELLING || before == ButtonState.CANCELLING) return
        // Só a VIEW do banner de limite some (se estava pendente); a decisão continua pendente até o
        // cancelamento se concluir de fato — ver finalizeCancel().
        if (limitDecisionPending) hideLimitBannerView()
        cancelLimitJob()
        if (before == ButtonState.SENDING) {
            transcribeJob?.cancel()
            transcribeJob = null
            runCatching { transcriber.cancelInFlight() }
        } else {
            pauseRecorder()
            if (before == ButtonState.RECORDING) timeFreeze() // vindo de PAUSED já estava congelado
        }
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
        limitDecisionPending = false // áudio descartado de vez: não há mais nada a enviar/descartar
        runCatching { recorder.discard() } // no-op se o recorder já tinha sido liberado (veio de SENDING)
        endPartial() // PAR-08: o buffer vai junto com o áudio
        pendingAudioFile?.delete()
        pendingAudioFile = null
        continuingPendingAudio = false
        priorSegmentsMs = 0L
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
        private const val DEFAULT_LIMIT_BANNER_DISMISS_MS = 3000L
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
                pauseRecorder()
                timeFreeze()
                render(machine.onPause())
            }
            GestureIntent.RESUME -> {
                if (limitDecisionPending) {
                    retriggerLimitBanner()
                } else if (partial != null && partialRecorderStopped) {
                    // Desfez um cancelamento feito durante o envio da sessão parcial: o recorder já tinha
                    // sido encerrado — nova gravação segmentada, mesmos buffer e trechos.
                    if (maxRecordingMs() > 0L && priorSegmentsMs >= maxRecordingMs()) {
                        showLimitBanner()
                    } else {
                        partialRecorderStopped = false
                        runCatching { recorder.startSegmented() }
                        timeStart()
                        scheduleLimitJob()
                        render(machine.onResume())
                    }
                } else if (pendingAudioFile != null) {
                    // Desfez um cancelamento que começou durante SENDING: não há sessão de gravação viva
                    // pra continuar (o recorder já foi liberado no primeiro stop()), mas o Yan pode querer
                    // seguir falando — abre uma NOVA sessão; ao parar de novo, stopAndTranscribe() funde
                    // os dois trechos num arquivo só (AudioConcatenator). Se os trechos já retidos sozinhos
                    // já batem o teto configurado, não deixa continuar sem limite — reabre a decisão.
                    if (maxRecordingMs() > 0L && priorSegmentsMs >= maxRecordingMs()) {
                        showLimitBanner()
                    } else {
                        continuingPendingAudio = true
                        runCatching { recorder.start() }
                        timeStart()
                        scheduleLimitJob()
                        render(machine.onResume())
                    }
                } else {
                    runCatching { recorder.resume() }
                    timeResume()
                    scheduleLimitJob()
                    render(machine.onResume())
                }
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
        if (!microphoneGranted()) {
            onError("Conceda permissão de microfone para usar essa função.", ErrorNavTarget.MIC)
            handleState(machine.onTranscriptionError())
            return
        }
        recordingStartMs = recordingClockMs()
        timeStart()
        scheduleLimitJob()
        endPartial()
        if (partialOnPause()) {
            partial = PartialSession(
                scope = scope,
                transcribe = { segment -> transcribeWithTimeout(segment) },
                onChange = { onPartial(it) },
            )
        }
        runCatching { if (partial != null) recorder.startSegmented() else recorder.start() }
            .onFailure {
                endPartial()
                // Rede de segurança: se a checagem proativa passou mas o recorder nativo ainda assim
                // falhou por permissão (ex.: revogada bem no meio), não perde a mensagem certa.
                val target = if (!microphoneGranted()) ErrorNavTarget.MIC else ErrorNavTarget.NONE
                onError("Falha ao iniciar a gravação.", target)
                handleState(machine.onTranscriptionError())
            }
    }

    private fun stopAndTranscribe() {
        limitDecisionPending = false
        hideLimitBannerView()
        cancelLimitJob()
        partial?.let { session ->
            stopPartialAndDeliver(session)
            return
        }

        // Reenvio puro (cancelou durante SENDING, desfez e mandou de novo sem gravar mais nada): não houve
        // sessão viva nesse meio-tempo, então NÃO soma o tempo ocioso da interação com o banner como se
        // fosse gravação (timeFreeze() contaria isso errado — ver priorSegmentsMs).
        val pureResend = pendingAudioFile != null && !continuingPendingAudio
        if (!pureResend) timeFreeze() // captura o tempo desta gravação antes de zerar (idempotente)
        val segmentMs = if (pureResend) 0L else timeAccumulatedMs
        timeReset() // gravação encerrada: zera o tempo autoritativo

        val audio: File? = when {
            pendingAudioFile != null && continuingPendingAudio -> {
                // Desfez um cancelamento em SENDING e continuou gravando: funde o trecho retido com o novo
                // num arquivo só antes de mandar pra transcrição (pedido do Yan, 2026-07-03).
                val previous = pendingAudioFile!!
                continuingPendingAudio = false
                val newSegment = runCatching { recorder.stop() }.getOrNull()
                when (newSegment) {
                    null -> previous // recorder falhou/trecho novo curto demais: manda só o que já tinha
                    else -> {
                        val merged = runCatching {
                            concatAudioSegments(listOf(previous, newSegment), previous.parentFile!!)
                        }.getOrNull()
                        if (merged != null) {
                            previous.delete()
                            newSegment.delete()
                            merged
                        } else {
                            // Fusão falhou: mantém o trecho novo isolado em vez de travar o envio — o Yan
                            // perde só o pedaço anterior, não a sessão toda.
                            previous.delete()
                            newSegment
                        }
                    }
                }
            }
            // Reenvio puro de um áudio já retido (cancelou durante SENDING e desfez, sem continuar
            // gravando): reusa o MESMO arquivo — o recorder original já foi liberado no primeiro stop().
            pendingAudioFile != null -> pendingAudioFile
            else -> {
                val duration = recordingClockMs() - recordingStartMs
                if (duration < MIN_RECORDING_MS) {
                    Log.d(TAG, "recording too short (${duration}ms), cancelling")
                    runCatching { recorder.stop() }
                    onError("Gravação muito curta.", ErrorNavTarget.NONE)
                    render(machine.onErrorDismissed())
                    return
                }
                runCatching { recorder.stop() }.getOrNull()
            }
        }
        if (audio == null) {
            onError("Não foi possível gravar o áudio.", ErrorNavTarget.NONE)
            handleState(machine.onTranscriptionError())
            return
        }
        pendingAudioFile = audio
        if (!pureResend) priorSegmentsMs += segmentMs
        val attempt = ++sendAttempt

        transcribeJob = scope.launch {
            val result = transcribeWithTimeout(audio)

            // Cancelado enquanto enviava (onCancel() já moveu a máquina pra CANCELLING/PAUSED/idle) — ou
            // já superado por um reenvio mais novo (cancelar+desfazer+enviar de novo antes desta corrotina
            // cancelada terminar de desenrolar): não processa um resultado tardio por cima de um estado
            // que já foi resolvido de outra forma. O contador [sendAttempt] cobre o caso em que a tentativa
            // antiga só resume DEPOIS que a nova já está em SENDING de novo (aí checar só o estado não
            // seria suficiente pra distinguir as duas).
            if (attempt != sendAttempt || machine.state != ButtonState.SENDING) return@launch
            pendingAudioFile = null
            priorSegmentsMs = 0L
            deliver(result)
        }
    }

    /**
     * Transcreve um arquivo com a config do momento. Rede de segurança além do callTimeout do WhisperApi
     * (Yan, 2026-07-01): garante que o coordinator sempre desiste dentro de cfg.timeoutMs + margem, mesmo
     * se o transcriber travar por um caminho que o OkHttp não cobre (ex.: implementação customizada de
     * OverlayTranscriber). Usado também por trecho da sessão parcial.
     */
    private suspend fun transcribeWithTimeout(audio: File): TranscriptionResult {
        val cfg = configProvider()
        return runCatching {
            // "∞": a pessoa escolheu esperar o quanto for preciso — cancelar durante o envio segue valendo.
            if (cfg.waitsForever) {
                transcriber.transcribe(audio, cfg)
            } else {
                withTimeout(cfg.timeoutMs + TIMEOUT_SAFETY_MARGIN_MS) { transcriber.transcribe(audio, cfg) }
            }
        }.getOrElse { e ->
            when (e) {
                is TimeoutCancellationException ->
                    TranscriptionResult.Error(ErrorKind.TIMEOUT, "client-side timeout after ${cfg.timeoutMs}ms")
                // Cancelamento de quem chamou (cancelar durante o envio) não vira erro: propaga.
                is kotlinx.coroutines.CancellationException -> throw e
                else -> TranscriptionResult.Error(ErrorKind.NETWORK, e.message.orEmpty())
            }
        }
    }

    /** Entrega o resultado final pelo fluxo de sempre: inserção/pendência/clipboard ou erro. */
    private fun deliver(result: TranscriptionResult) {
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
                onError(errorMessage(result), errorNavTarget(result.kind))
                handleState(machine.onTranscriptionError())
            }
        }
    }

    /**
     * Envio da sessão parcial (PAR-07/09): fecha o trecho em curso, espera a fila (refazendo os que
     * falharam) e entrega o texto inteiro — buffer com as edições + último trecho — pelo fluxo de sempre.
     * Cancelar durante o envio mantém a sessão (o desfazer volta a ela, PAR-08); a entrega a encerra.
     */
    private fun stopPartialAndDeliver(session: PartialSession) {
        timeFreeze()
        val segmentMs = timeAccumulatedMs
        timeReset()
        if (!partialRecorderStopped) {
            runCatching { recorder.stop() }.getOrNull()?.let(session::enqueue)
            partialRecorderStopped = true
        }
        if (session.isEmpty && recordingClockMs() - recordingStartMs < MIN_RECORDING_MS) {
            endPartial()
            onError("Gravação muito curta.", ErrorNavTarget.NONE)
            render(machine.onErrorDismissed())
            return
        }
        priorSegmentsMs += segmentMs
        val attempt = ++sendAttempt
        transcribeJob = scope.launch {
            val result = session.finish()
            if (attempt != sendAttempt || machine.state != ButtonState.SENDING) return@launch
            priorSegmentsMs = 0L
            endPartial()
            deliver(result)
        }
    }

    /**
     * Erros de transcrição resolvíveis na aba Voz — só estes tornam o X clicável. LOCAL inclui: é lá que
     * se baixa de novo o modelo ou se escolhe um menor.
     */
    private fun errorNavTarget(kind: ErrorKind): ErrorNavTarget = when (kind) {
        ErrorKind.CONFIG_MISSING, ErrorKind.AUTH, ErrorKind.LOCAL -> ErrorNavTarget.VOZ
        else -> ErrorNavTarget.NONE
    }

    /** O erro local já chega com a mensagem para o usuário (causa específica: modelo, memória…). */
    private fun errorMessage(error: TranscriptionResult.Error): String =
        if (error.kind == ErrorKind.LOCAL) error.message else errorMessage(error.kind)

    private fun errorMessage(kind: ErrorKind): String = when (kind) {
        ErrorKind.CONFIG_MISSING -> "Configure um provedor de voz na aba Transcrição."
        ErrorKind.AUTH -> "Falha de autenticação — verifique o token."
        ErrorKind.NETWORK -> "Sem conexão de rede."
        ErrorKind.TIMEOUT -> "Tempo esgotado ao transcrever."
        ErrorKind.TOO_LARGE -> "Áudio muito longo para transcrever."
        ErrorKind.EMPTY_AUDIO -> "Nada foi captado pelo microfone."
        ErrorKind.HTTP -> "Erro no servidor de transcrição."
        ErrorKind.LOCAL -> "Falha na transcrição local."
    }

    private fun ButtonStateMachine.onTargetChangedAndReturn(available: Boolean): ButtonState {
        onTargetChanged(available)
        return state
    }
}
