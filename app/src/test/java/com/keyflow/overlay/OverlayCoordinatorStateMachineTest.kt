package com.keyflow.overlay

import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorKind
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import com.keyflow.fluxos.execucao.DestinoFluxo
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.fail
import org.junit.Test

/**
 * Teste de máquina de estados do coordenador da gravação: sequências aleatórias de tudo o que a orbe, o teclado e a
 * notificação podem mandar — tocar, segurar, as intenções, cancelar, desfazer, concluir, trocar de campo, as saídas do limite
 * de tempo — com o relógio andando entre elas (inclusive além da janela de desfazer e no meio de uma transcrição lenta).
 *
 * Depois de CADA passo valem as invariantes (inclusive o cronômetro da gravação, que o PIT mostrou sem teste), e no fim a
 * gravação sempre volta ao repouso: é a área com mais bugs da história
 * do projeto (o CANCELLING que orfanizava o descarte na troca de campo, a janela que fechava antes do desfazer). Uma falha
 * mostra a sequência inteira e a semente para repetir.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayCoordinatorStateMachineTest {

    enum class Action { TAP, HOLD, RELEASE, START, STOP, PAUSE, RESUME, CANCEL_INTENT, CANCEL_COMMIT_INTENT, CANCEL, UNDO, COMMIT, TARGET_ON, TARGET_OFF, LIMIT_SEND, LIMIT_DISCARD, FLOW_START }

    data class Step(val action: Action, val waitMs: Long)

    /** O gravador como o microfone o vê: [capturing] = ligado e não pausado. */
    private class TrackingRecorder(private val dir: File) : OverlayAudioRecorder {
        var recording = false
        var paused = false
        var startedTwice = 0
        val capturing get() = recording && !paused
        override fun start() {
            if (recording) startedTwice++
            recording = true
            paused = false
        }
        override fun stop(): File? {
            if (!recording) return null
            recording = false
            paused = false
            return audio()
        }
        override fun pause() { if (recording) paused = true }
        override fun resume() { if (recording) paused = false }
        override fun discard() { recording = false; paused = false }
        override fun pauseSegment(): File? {
            if (!recording || paused) return null
            paused = true
            return audio()
        }
        private fun audio() = File.createTempFile("rec", ".m4a", dir).apply { writeBytes(ByteArray(64) { 1 }) }
    }

    private class SlowTranscriber(private val delayMs: Long, private val fails: Boolean) : OverlayTranscriber {
        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            delay(delayMs)
            return if (fails) TranscriptionResult.Error(ErrorKind.entries.first(), "falhou") else TranscriptionResult.Success("texto ditado")
        }
    }

    private val step = Arb.bind(Arb.enum<Action>(), Arb.long(0L..6_000L)) { action, wait -> Step(action, if (wait < 2_000) 0 else wait) }
    private val scenario = Arb.bind(Arb.list(step, 1..30), Arb.long(0L..8_000L), Arb.boolean(), Arb.int(0..2), Arb.boolean()) { steps, transcribeMs, fails, limit, partial ->
        Scenario(steps, transcribeMs, fails, maxRecordingMs = listOf(0L, 4_000L, 60_000L)[limit], partialOnPause = partial)
    }

    data class Scenario(
        val steps: List<Step>,
        val transcribeMs: Long,
        val transcriptionFails: Boolean,
        val maxRecordingMs: Long,
        val partialOnPause: Boolean,
    )

    @Test(timeout = 120_000)
    fun anySequenceKeepsTheInvariantsAndEndsAtRest() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("sm").toFile()
        checkAll(ITERATIONS, scenario) { scenario -> run(scenario, dir) }
        dir.deleteRecursively()
    }

    private fun run(s: Scenario, dir: File) {
        val scope = TestScope(StandardTestDispatcher())
        val scheduler = scope.testScheduler
        val recorder = TrackingRecorder(dir)
        val rendered = mutableListOf<ButtonState>()
        var bannerShown = 0
        var bannerHidden = 0
        var target = true
        val history = mutableListOf<String>()
        // O cronômetro como a orbe, o teclado e a barra o mostram: acumulado + (agora − início do trecho, se gravando).
        var timeAccumulated = 0L
        var timeSegmentStart = 0L
        fun shownTimeMs(state: ButtonState) = timeAccumulated + if (state == ButtonState.RECORDING) scheduler.currentTime - timeSegmentStart else 0L
        var lastShownMs = 0L
        // Teto do que pode aparecer: todo o tempo virtual passado em RECORDING desde o último zero (envio, repouso).
        var recordedSinceResetMs = 0L
        // Ditado de fluxo (FLX-18): cada destino aceito conta quantas vezes foi avisado. Valem, depois de CADA passo, "no máximo uma"
        // e, no fim, "exatamente uma" — e o texto de uma sessão com destino nunca vai ao campo.
        val destinos = mutableListOf<IntArray>()
        var coordinatorRef: OverlayCoordinator? = null
        var inseridoComDestino = false
        val coordinator = OverlayCoordinator(
            scope = scope,
            configProvider = { Config(endpointUrl = "https://example.test/v1/audio/transcriptions", authHeaderValue = "Bearer t") },
            recorder = recorder,
            transcriber = SlowTranscriber(s.transcribeMs, s.transcriptionFails),
            insertOrHold = { if (coordinatorRef?.ditadoDeFluxo == true) inseridoComDestino = true; if (target) InsertOutcome.Inserted else InsertOutcome.Held },
            flushPending = { false },
            targetAvailable = { target },
            render = { rendered += it },
            onShowCancelUndo = { bannerShown++ },
            onHideCancelUndo = { bannerHidden++ },
            maxRecordingMs = { s.maxRecordingMs },
            recordingClockMs = { scheduler.currentTime },
            onRecordingTime = { accumulated, segmentStart -> timeAccumulated = accumulated; timeSegmentStart = segmentStart },
            concatAudioSegments = { segments, _ -> segments.first() },
            partialOnPause = { s.partialOnPause },
        )
        coordinatorRef = coordinator

        fun check(where: String) {
            val state = coordinator.currentState()
            val problems = buildList {
                if (recorder.startedTwice > 0) add("o gravador foi ligado duas vezes")
                // O microfone capta se e só se a tela diz que grava: nem em repouso, nem pausado, nem enviando.
                if (state == ButtonState.RECORDING && !recorder.capturing) add("RECORDING sem o microfone captar")
                if (state != ButtonState.RECORDING && recorder.capturing) add("microfone captando em $state")
                if (state in IDLE && recorder.recording) add("em repouso ($state) com uma gravação aberta")
                if (inseridoComDestino) add("o texto de uma sessão de fluxo foi inserido no campo")
                destinos.forEachIndexed { i, calls -> if (calls[0] > 1) add("o destino #$i foi avisado ${calls[0]} vezes") }
                if (destinos.size > 0 && coordinator.ditadoDeFluxo && destinos.last()[0] > 0) add("destino já avisado, mas ainda ativo")
                if (rendered.isNotEmpty() && rendered.last() != state) add("a tela mostra ${rendered.last()} mas o estado é $state")
                if (bannerShown - bannerHidden !in 0..1) add("aviso de desfazer mostrado $bannerShown e escondido $bannerHidden vezes")
                // O cronômetro só zera ao enviar ou voltar ao repouso; gravando, pausado ou no desfazer, nunca anda para trás nem
                // passa do tempo gravado de fato.
                val shown = shownTimeMs(state)
                if (state in RESETS_TIMER) {
                    recordedSinceResetMs = 0L
                } else {
                    if (shown < lastShownMs) add("o cronômetro voltou de ${lastShownMs} ms para ${shown} ms em $state")
                    if (shown > recordedSinceResetMs) add("o cronômetro mostra ${shown} ms, mas só ${recordedSinceResetMs} ms foram gravados")
                }
                lastShownMs = if (state in RESETS_TIMER) 0L else shown
            }
            if (problems.isNotEmpty()) fail("$where: $problems\npassos: $history\ncenário: ${s.copy(steps = emptyList())}")
        }

        for (st in s.steps) {
            history += "${st.action}${if (st.waitMs > 0) "+${st.waitMs}ms" else ""}"
            try {
                when (st.action) {
                    Action.TAP -> coordinator.onTap()
                    Action.HOLD -> coordinator.onHold()
                    Action.RELEASE -> coordinator.onRelease()
                    Action.START -> coordinator.onIntent(GestureIntent.START)
                    Action.STOP -> coordinator.onIntent(GestureIntent.STOP)
                    Action.PAUSE -> coordinator.onIntent(GestureIntent.PAUSE)
                    Action.RESUME -> coordinator.onIntent(GestureIntent.RESUME)
                    Action.CANCEL_INTENT -> coordinator.onIntent(GestureIntent.CANCEL)
                    Action.CANCEL_COMMIT_INTENT -> coordinator.onIntent(GestureIntent.CANCEL_COMMIT)
                    Action.CANCEL -> coordinator.onCancel()
                    Action.UNDO -> coordinator.onUndoCancel()
                    Action.COMMIT -> coordinator.onCommitCancel()
                    Action.TARGET_ON -> { target = true; coordinator.onTargetChanged(true) }
                    Action.TARGET_OFF -> { target = false; coordinator.onTargetChanged(false) }
                    Action.LIMIT_SEND -> coordinator.onLimitSend()
                    Action.LIMIT_DISCARD -> coordinator.onLimitDiscard()
                    Action.FLOW_START -> {
                        val calls = intArrayOf(0)
                        if (coordinator.iniciarDitadoDeFluxo(DestinoFluxo(null) { calls[0]++ })) destinos += calls
                    }
                }
                scheduler.runCurrent()
                if (st.waitMs > 0) {
                    // Por cima: o limite de tempo pode pausar no meio da espera, e aí conta a espera inteira.
                    if (coordinator.currentState() == ButtonState.RECORDING) recordedSinceResetMs += st.waitMs
                    scheduler.advanceTimeBy(st.waitMs)
                    scheduler.runCurrent()
                }
            } catch (t: Throwable) {
                fail("${st.action} lançou ${t.javaClass.simpleName}: ${t.message}\npassos: $history")
            }
            check("depois de ${st.action}")
        }

        // Fim: quem está gravando para; dez minutos depois (janela de desfazer, transcrição, aviso de erro, limite), repouso.
        target = true
        coordinator.onTargetChanged(true)
        if (coordinator.currentState() in listOf(ButtonState.RECORDING, ButtonState.PAUSED)) coordinator.onIntent(GestureIntent.STOP)
        scheduler.advanceTimeBy(10 * 60_000L)
        scheduler.runCurrent()
        check("no fim")
        val end = coordinator.currentState()
        if (end !in IDLE) fail("não voltou ao repouso: ficou em $end\npassos: $history\ncenário: $s")
        destinos.forEachIndexed { i, calls ->
            if (calls[0] != 1) fail("o destino #$i foi avisado ${calls[0]} vezes (devia ser uma)\npassos: $history\ncenário: $s")
        }
    }

    companion object {
        const val ITERATIONS = 3_000
        val IDLE = setOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD)
        /** Estados em que o cronômetro volta a zero (o áudio foi enviado ou descartado). */
        val RESETS_TIMER = IDLE + setOf(ButtonState.SENDING, ButtonState.ERROR, ButtonState.PENDING_PASTE)
    }
}
