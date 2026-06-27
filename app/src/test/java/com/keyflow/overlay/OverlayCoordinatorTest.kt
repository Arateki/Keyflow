package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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

        val coordinator = OverlayCoordinator(
            scope = this,
            configProvider = { completeConfig() },
            recorder = recorder,
            transcriber = transcriber,
            insertOrHold = {
                inserted += it
                InsertOutcome.Inserted
            },
            flushPending = { false },
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
    fun `successful transcription without target renders pending paste`() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = OverlayCoordinator(
            scope = this,
            configProvider = { completeConfig() },
            recorder = FakeRecorder(audio),
            transcriber = FakeTranscriber(TranscriptionResult.Success("held text")),
            insertOrHold = { InsertOutcome.Held },
            flushPending = { false },
            targetAvailable = { false },
            render = { renderedStates += it },
        )

        coordinator.onTargetChanged(true)
        coordinator.onTap()
        coordinator.onTap()
        advanceUntilIdle()

        assertThat(renderedStates.last()).isEqualTo(ButtonState.PENDING_PASTE)
    }

    @Test
    fun `toggle tap without target still records and holds transcription as pending`() = runTest {
        val audio = temporaryFolder.newFile("audio.m4a").also { it.writeText("audio") }
        val recorder = FakeRecorder(audio)
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = OverlayCoordinator(
            scope = this,
            configProvider = { completeConfig() },
            recorder = recorder,
            transcriber = FakeTranscriber(TranscriptionResult.Success("pending text")),
            insertOrHold = { InsertOutcome.Held },
            flushPending = { false },
            targetAvailable = { false },
            render = { renderedStates += it },
        )

        coordinator.onTap()
        coordinator.onTap()
        advanceUntilIdle()

        assertThat(recorder.started).isEqualTo(1)
        assertThat(recorder.stopped).isEqualTo(1)
        assertThat(renderedStates).containsAtLeast(
            ButtonState.RECORDING,
            ButtonState.SENDING,
            ButtonState.PENDING_PASTE,
        ).inOrder()
    }

    @Test
    fun `empty audio renders error and skips transcription`() = runTest {
        val transcriber = FakeTranscriber(TranscriptionResult.Success("unused"))
        val renderedStates = mutableListOf<ButtonState>()

        val coordinator = OverlayCoordinator(
            scope = this,
            configProvider = { completeConfig() },
            recorder = FakeRecorder(audioFile = null),
            transcriber = transcriber,
            insertOrHold = { InsertOutcome.Inserted },
            flushPending = { false },
            targetAvailable = { true },
            render = { renderedStates += it },
        )

        coordinator.onTap()
        coordinator.onTap()
        advanceUntilIdle()

        assertThat(transcriber.audioFiles).isEmpty()
        assertThat(renderedStates.last()).isEqualTo(ButtonState.ERROR)
    }

    private fun completeConfig(): Config = Config(
        endpointUrl = "https://example.test/v1/audio/transcriptions",
        authHeaderValue = "Bearer token",
    )

    private class FakeRecorder(private val audioFile: File?) : OverlayAudioRecorder {
        var started = 0
        var stopped = 0

        override fun start() {
            started++
        }

        override fun stop(): File? {
            stopped++
            return audioFile
        }
    }

    private class FakeTranscriber(private val result: TranscriptionResult) : OverlayTranscriber {
        val audioFiles = mutableListOf<File>()

        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            audioFiles += audio
            return result
        }
    }
}
