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
        assertThat(renderedStates).containsAtLeast(
            ButtonState.RECORDING,
            ButtonState.SENDING,
            ButtonState.IDLE_READY,
        ).inOrder()
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
        advanceTimeBy(3001)

        assertThat(recorder.discarded).isEqualTo(1)
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
        cancelWindowMs: Long = 3000L,
        recordingClockMs: () -> Long = longEnoughClock(),
    ) = OverlayCoordinator(
        scope = scope,
        configProvider = { completeConfig() },
        recorder = recorder,
        transcriber = transcriber,
        insertOrHold = insertOrHold,
        flushPending = { false },
        targetAvailable = targetAvailable,
        render = render,
        onClipboard = onClipboard,
        onShowCancelUndo = onShowCancelUndo,
        onHideCancelUndo = onHideCancelUndo,
        cancelWindowMs = cancelWindowMs,
        recordingClockMs = recordingClockMs,
    )

    private fun completeConfig(): Config = Config(
        endpointUrl = "https://example.test/v1/audio/transcriptions",
        authHeaderValue = "Bearer token",
    )

    private class FakeRecorder(private val audioFile: File?) : OverlayAudioRecorder {
        var started = 0
        var stopped = 0
        var paused = 0
        var discarded = 0

        override fun start() { started++ }
        override fun stop(): File? { stopped++; return audioFile }
        override fun pause() { paused++ }
        override fun discard() { discarded++ }
    }

    private class FakeTranscriber(private val result: TranscriptionResult) : OverlayTranscriber {
        val audioFiles = mutableListOf<File>()

        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            audioFiles += audio
            return result
        }
    }

    private companion object {
        fun longEnoughClock(): () -> Long {
            var t = 0L
            return { t.also { t += 500L } }
        }
    }
}
