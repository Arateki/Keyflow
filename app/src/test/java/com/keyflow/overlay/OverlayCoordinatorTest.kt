package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayCoordinatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `toggle tap records then transcribes and inserts into active target`() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val recorder = FakeRecorder(audio)
        val transcriber = FakeTranscriber(TranscriptionResult.Success("hello from overlay"))
        val renderedStates = mutableListOf<ButtonState>()
        val inserted = mutableListOf<String>()

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            transcriber = transcriber,
            insertOrHold = { inserted += it; InsertOutcome.Inserted },
            targetAvailable = { true },
            render = { renderedStates += it },
        )

        coordinator.onTap()
        coordinator.onTap()
        advanceUntilIdle()

        assertThat(recorder.started).isEqualTo(1)
        assertThat(recorder.stopped).isEqualTo(1)
        assertThat(transcriber.audioFiles).containsExactly(audio)
        assertThat(inserted).containsExactly("hello from overlay")
        assertThat(audio.exists()).isFalse()
        assertThat(renderedStates).containsAtLeast(
            ButtonState.RECORDING,
            ButtonState.SENDING,
            ButtonState.IDLE_READY,
        ).inOrder()
    }

    @Test
    fun `audio is deleted after a failed transcription`() = runTest {
        val audio = temporaryFolder.newFile("audio-error.m4a").also { it.writeText("audio") }
        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audio),
            transcriber = FakeTranscriber(TranscriptionResult.Error(com.keyflow.core.ErrorKind.NETWORK, "offline")),
        )

        coordinator.onTap()
        coordinator.onTap()
        advanceUntilIdle()

        assertThat(audio.exists()).isFalse()
    }

    /**
     * Checagem proativa (Yan, 2026-07-04): sem RECORD_AUDIO, nem tenta `recorder.start()` — vai direto
     * pro erro certo, com [ErrorNavTarget.MIC] (antes só descobria a falta quando o recorder nativo
     * já tinha explodido, com uma mensagem genérica sem destino de navegação nenhum).
     */
    @Test
    fun `starting to record without microphone permission errors without touching the recorder`() = runTest {
        val recorder = FakeRecorder(null)
        val renderedStates = mutableListOf<ButtonState>()
        var lastMessage: String? = null
        var lastTarget: com.keyflow.core.ErrorNavTarget? = null

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onError = { message, target -> lastMessage = message; lastTarget = target },
            microphoneGranted = { false },
        )

        coordinator.onTap()
        runCurrent()

        assertThat(recorder.started).isEqualTo(0)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.ERROR)
        assertThat(lastTarget).isEqualTo(com.keyflow.core.ErrorNavTarget.MIC)
        assertThat(lastMessage).isEqualTo("Conceda permissão de microfone para usar essa função.")
    }

    @Test
    fun `starting without current disclosure acceptance opens disclosure and does not record`() = runTest {
        val recorder = FakeRecorder(null)
        var disclosureRequests = 0
        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            recordingConsentAccepted = { false },
            onDisclosureRequired = { disclosureRequests++ },
        )

        coordinator.onTap()
        coordinator.onIntent(GestureIntent.START)
        runCurrent()

        assertThat(recorder.started).isEqualTo(0)
        assertThat(coordinator.currentState()).isEqualTo(ButtonState.IDLE_READY)
        assertThat(disclosureRequests).isEqualTo(2)
    }

    @Test
    fun `successful transcription without target copies to clipboard and returns to idle`() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val renderedStates = mutableListOf<ButtonState>()
        val clipped = mutableListOf<String>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audio),
            transcriber = FakeTranscriber(TranscriptionResult.Success("clipboard text")),
            insertOrHold = { InsertOutcome.Held },
            targetAvailable = { false },
            render = { renderedStates += it },
            onClipboard = { clipped += it },
        )

        coordinator.onTap()
        coordinator.onTap()
        advanceUntilIdle()

        assertThat(clipped).containsExactly("clipboard text")
        assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun `clipboard is not called when insertion succeeds`() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val clipped = mutableListOf<String>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audio),
            transcriber = FakeTranscriber(TranscriptionResult.Success("inserted")),
            insertOrHold = { InsertOutcome.Inserted },
            targetAvailable = { true },
            onClipboard = { clipped += it },
        )

        coordinator.onTap()
        coordinator.onTap()
        advanceUntilIdle()

        assertThat(clipped).isEmpty()
    }

    @Test
    fun `empty audio renders error and skips transcription`() = runTest {
        val transcriber = FakeTranscriber(TranscriptionResult.Success("unused"))
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audioFile = null),
            transcriber = transcriber,
            targetAvailable = { true },
            render = { renderedStates += it },
        )

        coordinator.onTap()
        coordinator.onTap()
        runCurrent() // processa apenas coroutines imediatas, não avança além do delay de dismiss

        assertThat(transcriber.audioFiles).isEmpty()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.ERROR)
    }

    @Test
    fun `error auto-dismisses to idle after 3 seconds`() = runTest {
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audioFile = null),
            transcriber = FakeTranscriber(TranscriptionResult.Success("unused")),
            targetAvailable = { false },
            render = { renderedStates += it },
        )

        coordinator.onTap()
        coordinator.onTap()
        runCurrent()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.ERROR)

        advanceTimeBy(3001)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun `error dismisses immediately on next tap`() = runTest {
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audioFile = null),
            targetAvailable = { true },
            render = { renderedStates += it },
        )

        coordinator.onTap()
        coordinator.onTap()
        runCurrent()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.ERROR)

        coordinator.onTap()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `brief recording is cancelled without error and without transcription`() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val transcriber = FakeTranscriber(TranscriptionResult.Success("unused"))
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audio),
            transcriber = transcriber,
            targetAvailable = { false },
            render = { renderedStates += it },
            recordingClockMs = { 0L },
        )

        coordinator.onTap()
        coordinator.onTap()
        advanceUntilIdle()

        assertThat(transcriber.audioFiles).isEmpty()
        assertThat(renderedStates).doesNotContain(ButtonState.ERROR)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_NO_FIELD)
    }

    @Test
    fun `transcription that hangs past the configured timeout resolves to a timeout error`() = runTest {
        // Rede de segurança (Yan, 2026-07-01): mesmo que o transcriber trave por algum motivo fora do
        // controle do WhisperApi (ex.: bug num transcriber customizado), o coordinator não pode ficar
        // "transcrevendo" pra sempre — precisa desistir dentro do cfg.timeoutMs configurado.
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val transcriber = HangingTranscriber()
        val renderedStates = mutableListOf<ButtonState>()
        val errors = mutableListOf<String>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audio),
            transcriber = transcriber,
            targetAvailable = { true },
            render = { renderedStates += it },
            onError = { message, _ -> errors += message },
            configTimeoutMs = 1_000L,
        )

        coordinator.onTap() // IDLE → RECORDING
        coordinator.onTap() // RECORDING → SENDING
        // advanceUntilIdle() também avança além do auto-dismiss do erro (2s) — verifica ERROR ter
        // ocorrido, não ser o último estado (o próprio auto-dismiss é comportamento correto, não bug).
        advanceUntilIdle()

        assertThat(renderedStates).contains(ButtonState.ERROR)
        assertThat(errors).containsExactly("Tempo esgotado ao transcrever.")
    }

    @Test
    fun `with no time limit a slow transcription is waited for instead of timing out`() = runTest {
        // "∞" na aba Transcrição (Yan, 2026-09-28): a rede de segurança do coordinator não pode cortar
        // uma transcrição que demora além de qualquer prazo — a pessoa escolheu esperar.
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val slow = object : OverlayTranscriber {
            override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
                kotlinx.coroutines.delay(30 * 60_000L)
                return TranscriptionResult.Success("demorou")
            }
        }
        val inserted = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audio),
            transcriber = slow,
            insertOrHold = { inserted += it; InsertOutcome.Inserted },
            targetAvailable = { true },
            onError = { message, _ -> errors += message },
            recordingClockMs = longEnoughClock(),
            configTimeoutMs = Config.NO_TIMEOUT,
        )

        coordinator.onTap() // IDLE → RECORDING
        coordinator.onTap() // RECORDING → SENDING
        advanceUntilIdle()

        assertThat(errors).isEmpty()
        assertThat(inserted).containsExactly("demorou")
    }

    @Test
    fun `second tap in sending state does not trigger another transcription`() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val transcriber = FakeTranscriber(TranscriptionResult.Success("once"))
        val inserted = mutableListOf<String>()

        val coordinator = coordinator(
            scope = this,
            recorder = FakeRecorder(audio),
            transcriber = transcriber,
            insertOrHold = { inserted += it; InsertOutcome.Inserted },
            targetAvailable = { true },
            recordingClockMs = longEnoughClock(),
        )

        coordinator.onTap() // IDLE → RECORDING
        coordinator.onTap() // RECORDING → SENDING
        coordinator.onTap() // SENDING → ignored (state unchanged)
        advanceUntilIdle()

        assertThat(transcriber.audioFiles).hasSize(1)
        assertThat(inserted).containsExactly("once")
    }

    @Test
    fun `cancel pauses recorder renders cancelling and shows undo`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()
        var shown = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onShowCancelUndo = { shown++ },
        )

        coordinator.onTap() // IDLE → RECORDING
        coordinator.onCancel()
        runCurrent()

        assertThat(recorder.paused).isEqualTo(1)
        assertThat(recorder.discarded).isEqualTo(0)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.CANCELLING)
        assertThat(shown).isEqualTo(1)
    }

    @Test
    fun `undo cancel returns to paused without discarding`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()
        var hidden = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onHideCancelUndo = { hidden++ },
        )

        coordinator.onTap() // RECORDING
        coordinator.onCancel()
        coordinator.onUndoCancel()
        runCurrent()

        assertThat(renderedStates.last()).isEqualTo(ButtonState.PAUSED)
        assertThat(recorder.discarded).isEqualTo(0)
        assertThat(hidden).isEqualTo(1)
    }

    @Test
    fun `cancel window expiry discards recorder and returns to idle`() = runTest {
        val audio = temporaryFolder.newFile("a.m4a").also { it.writeText("a") }
        val recorder = FakeRecorder(audio)
        val renderedStates = mutableListOf<ButtonState>()
        var hidden = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onHideCancelUndo = { hidden++ },
            cancelWindowMs = 3000L,
        )

        coordinator.onTap() // RECORDING
        coordinator.onCancel()
        advanceTimeBy(3001)

        assertThat(recorder.discarded).isEqualTo(1)
        assertThat(audio.exists()).isFalse()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_READY)
        assertThat(hidden).isEqualTo(1)
    }

    @Test
    fun `commit cancel discards immediately and returns to idle`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()
        var hidden = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onHideCancelUndo = { hidden++ },
            cancelWindowMs = 3000L,
        )

        coordinator.onTap() // RECORDING
        coordinator.onCancel()
        coordinator.onCommitCancel()
        runCurrent()

        assertThat(recorder.discarded).isEqualTo(1)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_READY)
        assertThat(hidden).isEqualTo(1)

        // A janela não dispara um segundo descarte depois.
        advanceTimeBy(3001)
        assertThat(recorder.discarded).isEqualTo(1)
    }

    @Test
    fun `undo cancels the pending discard timer`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            cancelWindowMs = 3000L,
        )

        coordinator.onTap() // RECORDING
        coordinator.onCancel()
        coordinator.onUndoCancel()
        advanceTimeBy(3001)

        assertThat(recorder.discarded).isEqualTo(0)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.PAUSED)
    }

    @Test
    fun `recording paused automatically when max duration is reached`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()
        var shown = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onShowLimitReached = { shown++ },
            maxRecordingMs = { 5000L },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // IDLE → RECORDING
        advanceTimeBy(5001)
        runCurrent()

        assertThat(recorder.paused).isEqualTo(1)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.PAUSED)
        assertThat(shown).isEqualTo(1)
    }

    @Test
    fun `limit disabled by default does not pause automatically`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // IDLE → RECORDING
        advanceTimeBy(600_000)
        runCurrent()

        assertThat(renderedStates.last()).isEqualTo(ButtonState.RECORDING)
    }

    @Test
    fun `manual pause before the limit cancels the pending trigger`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        var shown = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            onShowLimitReached = { shown++ },
            maxRecordingMs = { 5000L },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(2000)
        coordinator.onIntent(GestureIntent.PAUSE)
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(shown).isEqualTo(0)
    }

    @Test
    fun `resuming while the limit decision is pending retriggers the banner instead of resuming`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()
        var shown = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onShowLimitReached = { shown++ },
            maxRecordingMs = { 5000L },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(5001)
        runCurrent()
        assertThat(shown).isEqualTo(1)

        coordinator.onIntent(GestureIntent.RESUME)
        advanceTimeBy(10_000)
        runCurrent()

        // reacende o banner (do zero) em vez de deixar a gravação continuar sem teto
        assertThat(shown).isEqualTo(2)
        assertThat(recorder.resumed).isEqualTo(0)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.PAUSED)
    }

    @Test
    fun `limit banner auto-dismisses after limitBannerDismissMs but the decision stays pending`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()
        var shown = 0
        var hidden = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onShowLimitReached = { shown++ },
            onHideLimitReached = { hidden++ },
            maxRecordingMs = { 5000L },
            limitBannerDismissMs = 3000L,
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(5001)
        runCurrent() // banner aparece
        assertThat(shown).isEqualTo(1)

        advanceTimeBy(3001)
        runCurrent() // some sozinho, sem decisão

        assertThat(hidden).isEqualTo(1)

        // "Descartar" ainda funciona depois do banner ter sumido sozinho — a decisão continuava pendente,
        // e agora abre a janela de desfazer unificada (não descarta na hora).
        coordinator.onLimitDiscard()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.CANCELLING)
        assertThat(recorder.discarded).isEqualTo(0)
    }

    @Test
    fun `retriggering the limit banner resets its dismiss timer`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        var shown = 0
        var hidden = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            onShowLimitReached = { shown++ },
            onHideLimitReached = { hidden++ },
            maxRecordingMs = { 5000L },
            limitBannerDismissMs = 3000L,
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(5001)
        runCurrent() // banner aparece (1)

        advanceTimeBy(2000)
        coordinator.onIntent(GestureIntent.RESUME) // reaciona antes dos 3s expirarem (2)
        advanceTimeBy(2000)
        runCurrent()

        // se o timer não tivesse resetado, já teria sumido em t=3000; com o reset, ainda não sumiu em t=4000
        assertThat(shown).isEqualTo(2)
        assertThat(hidden).isEqualTo(0)

        advanceTimeBy(1001)
        runCurrent()
        assertThat(hidden).isEqualTo(1)
    }

    @Test
    fun `send from limit banner transcribes the retained audio and hides the banner`() = runTest {
        val audio = temporaryFolder.newFile("a.m4a").also { it.writeText("a") }
        val recorder = FakeRecorder(audio)
        val transcriber = FakeTranscriber(TranscriptionResult.Success("done"))
        val inserted = mutableListOf<String>()
        var hidden = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            transcriber = transcriber,
            insertOrHold = { inserted += it; InsertOutcome.Inserted },
            targetAvailable = { true },
            onHideLimitReached = { hidden++ },
            maxRecordingMs = { 5000L },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(5001)
        runCurrent() // pausado pelo limite

        coordinator.onLimitSend()
        advanceUntilIdle()

        assertThat(recorder.stopped).isEqualTo(1)
        assertThat(inserted).containsExactly("done")
        assertThat(hidden).isEqualTo(1)
    }

    @Test
    fun `discard from limit banner opens the undo window instead of deleting immediately`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()
        var hidden = 0
        var cancelShown = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onHideLimitReached = { hidden++ },
            onShowCancelUndo = { cancelShown++ },
            maxRecordingMs = { 5000L },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(5001)
        runCurrent() // pausado pelo limite

        coordinator.onLimitDiscard()

        // Unificado com o cancelar comum (pedido do Yan, 2026-07-03): não descarta na hora, abre a
        // mesma janela de desfazer de 3s.
        assertThat(recorder.discarded).isEqualTo(0)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.CANCELLING)
        assertThat(cancelShown).isEqualTo(1)
        assertThat(hidden).isEqualTo(1) // a VIEW do banner de limite some ao entrar em CANCELLING

        coordinator.onCommitCancel()

        assertThat(recorder.discarded).isEqualTo(1)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `undoing a discard started from the limit banner still enforces the limit on resume`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val renderedStates = mutableListOf<ButtonState>()
        var shown = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            targetAvailable = { true },
            render = { renderedStates += it },
            onShowLimitReached = { shown++ },
            maxRecordingMs = { 5000L },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(5001)
        runCurrent() // pausado pelo limite, banner mostrado
        assertThat(shown).isEqualTo(1)

        coordinator.onLimitDiscard() // "Descartar" → CANCELLING
        coordinator.onUndoCancel() // "Desfazer" → volta a PAUSED, áudio preservado

        coordinator.onIntent(GestureIntent.RESUME)

        // Bug reportado pelo Yan (2026-07-03): antes desta correção, a decisão pendente era perdida no
        // cancelar, e o RESUME aqui retomava a gravação sem teto algum (recorder.resumed==1).
        assertThat(shown).isEqualTo(2)
        assertThat(recorder.resumed).isEqualTo(0)
        assertThat(renderedStates.last()).isEqualTo(ButtonState.PAUSED)
    }

    @Test
    fun `cancel during sending aborts the in-flight request and ignores a stale late result`() = runTest {
        val recorder = FakeRecorder(temporaryFolder.newFile("a.m4a").also { it.writeText("a") })
        val transcriber = HangingTranscriber()
        val renderedStates = mutableListOf<ButtonState>()
        var cancelShown = 0

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            transcriber = transcriber,
            targetAvailable = { true },
            render = { renderedStates += it },
            onShowCancelUndo = { cancelShown++ },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(500)
        coordinator.onTap() // RECORDING → SENDING (dispara a transcrição, que fica pendurada)
        runCurrent()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.SENDING)

        coordinator.onCancel() // arrasto lateral durante o envio

        assertThat(renderedStates.last()).isEqualTo(ButtonState.CANCELLING)
        assertThat(cancelShown).isEqualTo(1)
        assertThat(transcriber.cancelCalls).isEqualTo(1)

        // Só processa o que já está agendado (sem avançar o relógio virtual, senão a própria janela de
        // desfazer de 3s expiraria aqui e mascararia o que este teste quer provar).
        runCurrent()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.CANCELLING)
    }

    @Test
    fun `undoing a cancel started while sending resends the same retained audio without re-stopping the recorder`() =
        runTest {
            val audio = temporaryFolder.newFile("a.m4a").also { it.writeText("a") }
            val recorder = FakeRecorder(audio)
            val transcriber = CancelableTranscriber(TranscriptionResult.Success("done"))
            val inserted = mutableListOf<String>()
            val renderedStates = mutableListOf<ButtonState>()

            val coordinator = coordinator(
                scope = this,
                recorder = recorder,
                transcriber = transcriber,
                insertOrHold = { inserted += it; InsertOutcome.Inserted },
                targetAvailable = { true },
                render = { renderedStates += it },
                recordingClockMs = { testScheduler.currentTime },
            )

            coordinator.onTap() // RECORDING
            advanceTimeBy(500)
            coordinator.onTap() // RECORDING → SENDING (1ª chamada do fake, trava)
            runCurrent()
            assertThat(renderedStates.last()).isEqualTo(ButtonState.SENDING)

            coordinator.onCancel() // cancela durante o envio
            assertThat(renderedStates.last()).isEqualTo(ButtonState.CANCELLING)
            assertThat(transcriber.cancelCalls).isEqualTo(1)
            assertThat(recorder.stopped).isEqualTo(1)

            coordinator.onUndoCancel()
            assertThat(renderedStates.last()).isEqualTo(ButtonState.PAUSED)

            // Toque normal a partir do pause SEM resegurar: reenvio puro do MESMO arquivo, sem iniciar
            // uma sessão de gravação nova (ver teste de fusão abaixo pra quando o Yan resegura antes).
            coordinator.onIntent(GestureIntent.STOP)
            advanceUntilIdle()

            assertThat(recorder.started).isEqualTo(1) // não iniciou sessão nova
            assertThat(recorder.stopped).isEqualTo(1) // não chamou stop() de novo no gravador já liberado
            assertThat(transcriber.audioFiles).containsExactly(audio, audio) // reenviou o MESMO arquivo
            assertThat(inserted).containsExactly("done")
            assertThat(audio.exists()).isFalse()
            assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_READY)
        }

    @Test
    fun `resuming after undoing a cancel during sending continues recording and merges both segments on next stop`() =
        runTest {
            val segment1 = temporaryFolder.newFile("seg1.m4a").also { it.writeText("seg1") }
            val segment2 = temporaryFolder.newFile("seg2.m4a").also { it.writeText("seg2") }
            val merged = temporaryFolder.newFile("merged.m4a").also { it.writeText("merged") }
            val recorder = FakeRecorder(listOf(segment1, segment2))
            // A 1ª chamada (segmento 1, antes do cancelamento) precisa ficar pendurada — senão a
            // transcrição já teria concluído (voltando a IDLE) antes de dar tempo de cancelar/desfazer.
            val transcriber = CancelableTranscriber(TranscriptionResult.Success("done"))
            val inserted = mutableListOf<String>()
            val renderedStates = mutableListOf<ButtonState>()
            var concatCalledWith: List<File>? = null

            val coordinator = coordinator(
                scope = this,
                recorder = recorder,
                transcriber = transcriber,
                insertOrHold = { inserted += it; InsertOutcome.Inserted },
                targetAvailable = { true },
                render = { renderedStates += it },
                recordingClockMs = { testScheduler.currentTime },
                concatAudioSegments = { segments, _ -> concatCalledWith = segments; merged },
            )

            coordinator.onTap() // RECORDING
            advanceTimeBy(500)
            coordinator.onTap() // RECORDING → SENDING (segmento 1)
            runCurrent()

            coordinator.onCancel() // cancela durante o envio
            coordinator.onUndoCancel() // volta pro PAUSED, segmento 1 retido

            coordinator.onIntent(GestureIntent.RESUME) // continua gravando (segmento 2)
            assertThat(renderedStates.last()).isEqualTo(ButtonState.RECORDING)
            assertThat(recorder.started).isEqualTo(2)

            advanceTimeBy(500)
            coordinator.onIntent(GestureIntent.STOP) // funde os dois e envia
            advanceUntilIdle()

            assertThat(concatCalledWith).containsExactly(segment1, segment2).inOrder()
            assertThat(transcriber.audioFiles.last()).isEqualTo(merged)
            assertThat(inserted).containsExactly("done")
            assertThat(segment1.exists()).isFalse() // originais apagados depois de fundidos
            assertThat(segment2.exists()).isFalse()
            assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_READY)
        }

    @Test
    fun `resuming after a cancel during sending re-shows the limit banner if the retained segment already reached the max`() =
        runTest {
            val audio = temporaryFolder.newFile("a.m4a").also { it.writeText("a") }
            val recorder = FakeRecorder(audio)
            val transcriber = HangingTranscriber()
            val renderedStates = mutableListOf<ButtonState>()
            var shown = 0

            val coordinator = coordinator(
                scope = this,
                recorder = recorder,
                transcriber = transcriber,
                targetAvailable = { true },
                render = { renderedStates += it },
                onShowLimitReached = { shown++ },
                maxRecordingMs = { 1000L },
                recordingClockMs = { testScheduler.currentTime },
            )

            coordinator.onTap() // RECORDING
            advanceTimeBy(1000) // já bate o teto configurado dentro deste único segmento
            coordinator.onTap() // RECORDING → SENDING
            runCurrent()

            coordinator.onCancel()
            coordinator.onUndoCancel()

            coordinator.onIntent(GestureIntent.RESUME)

            // Reabre a decisão "enviar ou descartar" em vez de deixar continuar gravando sem teto (o
            // trecho já retido sozinho já esgotou o limite configurado).
            assertThat(shown).isEqualTo(1)
            assertThat(recorder.started).isEqualTo(1) // não iniciou uma sessão nova
            assertThat(renderedStates.last()).isEqualTo(ButtonState.PAUSED)
        }

    @Test
    fun `finalizing a cancel started while sending deletes the retained audio file`() = runTest {
        val audio = temporaryFolder.newFile("a.m4a").also { it.writeText("a") }
        val recorder = FakeRecorder(audio)
        val transcriber = HangingTranscriber()
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = coordinator(
            scope = this,
            recorder = recorder,
            transcriber = transcriber,
            targetAvailable = { true },
            render = { renderedStates += it },
            recordingClockMs = { testScheduler.currentTime },
        )

        coordinator.onTap() // RECORDING
        advanceTimeBy(500)
        coordinator.onTap() // RECORDING → SENDING
        runCurrent()

        coordinator.onCancel() // CANCELLING
        assertThat(audio.exists()).isTrue()

        coordinator.onCommitCancel() // "Concluir": descarta na hora

        assertThat(audio.exists()).isFalse()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.IDLE_READY)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun coordinator(
        scope: CoroutineScope,
        recorder: OverlayAudioRecorder = FakeRecorder(null),
        transcriber: OverlayTranscriber = FakeTranscriber(TranscriptionResult.Success("")),
        insertOrHold: (String) -> InsertOutcome = { InsertOutcome.Inserted },
        targetAvailable: () -> Boolean = { true },
        render: (ButtonState) -> Unit = {},
        onClipboard: (String) -> Unit = {},
        onShowCancelUndo: () -> Unit = {},
        onHideCancelUndo: () -> Unit = {},
        onShowLimitReached: () -> Unit = {},
        onHideLimitReached: () -> Unit = {},
        maxRecordingMs: () -> Long = { 0L },
        onError: (String, com.keyflow.core.ErrorNavTarget) -> Unit = { _, _ -> },
        cancelWindowMs: Long = 3000L,
        limitBannerDismissMs: Long = 3000L,
        recordingClockMs: () -> Long = longEnoughClock(),
        configTimeoutMs: Long = Config.DEFAULT_TIMEOUT_MS,
        concatAudioSegments: (List<File>, File) -> File = { segments, _ -> segments.first() },
        microphoneGranted: () -> Boolean = { true },
        recordingConsentAccepted: () -> Boolean = { true },
        onDisclosureRequired: () -> Unit = {},
    ) = OverlayCoordinator(
        scope = scope,
        configProvider = { completeConfig(configTimeoutMs) },
        recorder = recorder,
        transcriber = transcriber,
        insertOrHold = insertOrHold,
        flushPending = { false },
        targetAvailable = targetAvailable,
        render = render,
        onClipboard = onClipboard,
        onShowCancelUndo = onShowCancelUndo,
        onHideCancelUndo = onHideCancelUndo,
        onShowLimitReached = onShowLimitReached,
        onHideLimitReached = onHideLimitReached,
        maxRecordingMs = maxRecordingMs,
        limitBannerDismissMs = limitBannerDismissMs,
        onError = onError,
        cancelWindowMs = { cancelWindowMs },
        recordingClockMs = recordingClockMs,
        concatAudioSegments = concatAudioSegments,
        microphoneGranted = microphoneGranted,
        recordingConsentAccepted = recordingConsentAccepted,
        onDisclosureRequired = onDisclosureRequired,
    )

    private fun completeConfig(timeoutMs: Long = Config.DEFAULT_TIMEOUT_MS): Config = Config(
        endpointUrl = "https://example.test/v1/audio/transcriptions",
        authHeaderValue = "Bearer token",
        timeoutMs = timeoutMs,
    )

    /** [audioFiles] devolve um arquivo por ciclo start()/stop() (índices repetem o último se faltar). */
    private class FakeRecorder(private val audioFiles: List<File?>) : OverlayAudioRecorder {
        constructor(audioFile: File?) : this(listOf(audioFile))

        var started = 0
        var stopped = 0
        var paused = 0
        var resumed = 0
        var discarded = 0

        override fun start() { started++ }
        override fun stop(): File? {
            val file = audioFiles.getOrNull(stopped) ?: audioFiles.lastOrNull()
            stopped++
            return file
        }
        override fun pause() { paused++ }
        override fun resume() { resumed++ }
        override fun discard() {
            discarded++
            audioFiles.filterNotNull().forEach(File::delete)
        }
    }

    private class FakeTranscriber(private val result: TranscriptionResult) : OverlayTranscriber {
        val audioFiles = mutableListOf<File>()

        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            audioFiles += audio
            return result
        }
    }

    /** Nunca completa dentro do tempo de teste — só a rede de segurança (withTimeout) resolve isto. */
    private class HangingTranscriber : OverlayTranscriber {
        var cancelCalls = 0

        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            kotlinx.coroutines.delay(HANG_MS)
            return TranscriptionResult.Success("never gets here")
        }

        override fun cancelInFlight() { cancelCalls++ }
    }

    /** A 1ª chamada "trava" (simula rede em andamento, cancelável); as seguintes retornam na hora. */
    private class CancelableTranscriber(private val result: TranscriptionResult) : OverlayTranscriber {
        var cancelCalls = 0
        val audioFiles = mutableListOf<File>()
        private var callCount = 0

        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            audioFiles += audio
            callCount++
            if (callCount == 1) kotlinx.coroutines.delay(HANG_MS)
            return result
        }

        override fun cancelInFlight() { cancelCalls++ }
    }

    private companion object {
        const val HANG_MS = 60_000_000L

        fun longEnoughClock(): () -> Long {
            var t = 0L
            return { t.also { t += 500L } }
        }
    }
}
