package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.ErrorKind
import com.keyflow.core.InsertOutcome
import com.keyflow.core.TranscriptionResult
import com.keyflow.data.Config
import com.keyflow.transcription.partial.PartialSnapshot
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

/**
 * NM-T16/T18: o coordinator com transcrição parcial ao pausar — pausar transcreve o trecho para o buffer
 * sem entregar nada; enviar junta buffer (com edições) + último trecho e entrega uma vez; cancelar e
 * desfazer preservam o buffer; cancelar de vez apaga tudo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PartialCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Recorder segmentado fake, com as regras do real: cada pausa fecha um arquivo `seg-N`; pausar já
     * pausado não faz nada; parar pausado não devolve trecho novo.
     */
    private inner class SegmentedRecorder : OverlayAudioRecorder {
        var starts = 0
        var segmentedStarts = 0
        var discards = 0
        val closed = mutableListOf<File>()
        private var n = 0
        private var recording = false

        private fun close(): File = tmp.newFile("seg-${++n}.m4a").also { closed += it }

        override fun start() {
            starts++
            recording = true
        }

        override fun startSegmented() {
            segmentedStarts++
            recording = true
        }

        override fun resume() { recording = true }

        override fun stop(): File? = if (recording) close().also { recording = false } else null

        override fun pauseSegment(): File? = if (recording) close().also { recording = false } else null

        override fun discard() {
            discards++
            recording = false
            closed.forEach { it.delete() }
        }
    }

    /** Transcriber por nome de trecho; um trecho pode falhar N vezes antes de responder. */
    private class ScriptedTranscriber(private val texts: Map<String, String>) : OverlayTranscriber {
        val failuresLeft = mutableMapOf<String, Int>()
        val calls = mutableListOf<String>()

        override suspend fun transcribe(audio: File, cfg: Config): TranscriptionResult {
            val key = audio.nameWithoutExtension
            calls += key
            val left = failuresLeft[key] ?: 0
            if (left > 0) {
                failuresLeft[key] = left - 1
                return TranscriptionResult.Error(ErrorKind.NETWORK, "caiu")
            }
            return TranscriptionResult.Success(texts.getValue(key))
        }
    }

    private class Sink {
        val inserted = mutableListOf<String>()
        val clipboard = mutableListOf<String>()
        val states = mutableListOf<ButtonState>()
        val partials = mutableListOf<PartialSnapshot?>()
    }

    private fun coordinator(
        scope: CoroutineScope,
        recorder: OverlayAudioRecorder,
        transcriber: OverlayTranscriber,
        sink: Sink,
        partialOn: Boolean = true,
    ): OverlayCoordinator {
        var t = 0L
        return OverlayCoordinator(
            scope = scope,
            configProvider = { Config(endpointUrl = "https://example.test", authHeaderValue = "Bearer x") },
            recorder = recorder,
            transcriber = transcriber,
            insertOrHold = { sink.inserted += it; InsertOutcome.Inserted },
            flushPending = { false },
            targetAvailable = { true },
            render = { sink.states += it },
            onClipboard = { sink.clipboard += it },
            recordingClockMs = { t.also { t += 500L } },
            cancelWindowMs = 3_000L,
            partialOnPause = { partialOn },
            onPartial = { sink.partials += it },
        )
    }

    private val script = mapOf("seg-1" to "primeira parte", "seg-2" to "segunda parte", "seg-3" to "fim")

    @Test
    fun `pausing transcribes into the buffer and delivers nothing`() = runTest {
        val sink = Sink()
        val recorder = SegmentedRecorder()
        val c = coordinator(this, recorder, ScriptedTranscriber(script), sink)

        c.onIntent(GestureIntent.START)
        c.onIntent(GestureIntent.PAUSE)
        advanceUntilIdle()

        assertThat(recorder.segmentedStarts).isEqualTo(1)
        assertThat(recorder.starts).isEqualTo(0)
        assertThat(sink.partials.last()?.text).isEqualTo("primeira parte")
        assertThat(sink.inserted).isEmpty()
        assertThat(sink.clipboard).isEmpty()
        assertThat(c.currentState()).isEqualTo(ButtonState.PAUSED)
    }

    @Test
    fun `sending joins the edited buffer and the last segment and delivers once`() = runTest {
        val sink = Sink()
        val c = coordinator(this, SegmentedRecorder(), ScriptedTranscriber(script), sink)

        c.onIntent(GestureIntent.START)
        c.onIntent(GestureIntent.PAUSE)
        advanceUntilIdle()
        c.onPartialEdited("Primeira parte,")
        c.onIntent(GestureIntent.RESUME)
        c.onIntent(GestureIntent.PAUSE)
        advanceUntilIdle()
        c.onIntent(GestureIntent.RESUME)
        c.onIntent(GestureIntent.STOP)
        advanceUntilIdle()

        assertThat(sink.inserted).containsExactly("Primeira parte, segunda parte fim")
        assertThat(sink.partials.last()).isNull()
        assertThat(c.currentState()).isEqualTo(ButtonState.IDLE_READY)
    }

    @Test
    fun `a segment that failed at pause is retried on send`() = runTest {
        val sink = Sink()
        val transcriber = ScriptedTranscriber(script).apply { failuresLeft["seg-1"] = 1 }
        val c = coordinator(this, SegmentedRecorder(), transcriber, sink)

        c.onIntent(GestureIntent.START)
        c.onIntent(GestureIntent.PAUSE)
        advanceUntilIdle()
        assertThat(sink.partials.last()?.failed).isEqualTo(1)

        c.onIntent(GestureIntent.STOP)
        advanceUntilIdle()

        assertThat(sink.inserted).containsExactly("primeira parte")
    }

    @Test
    fun `cancel then undo keeps the buffer and the send still delivers everything`() = runTest {
        val sink = Sink()
        val c = coordinator(this, SegmentedRecorder(), ScriptedTranscriber(script), sink)

        c.onIntent(GestureIntent.START)
        c.onIntent(GestureIntent.PAUSE)
        advanceUntilIdle()
        c.onCancel()
        c.onUndoCancel()
        c.onIntent(GestureIntent.RESUME)
        c.onIntent(GestureIntent.STOP)
        advanceUntilIdle()

        assertThat(sink.inserted).containsExactly("primeira parte segunda parte")
    }

    @Test
    fun `cancel that expires discards the segments and the buffer`() = runTest {
        val sink = Sink()
        val recorder = SegmentedRecorder()
        val c = coordinator(this, recorder, ScriptedTranscriber(script), sink)

        c.onIntent(GestureIntent.START)
        c.onIntent(GestureIntent.PAUSE)
        advanceUntilIdle()
        c.onCancel()
        advanceTimeBy(3_001)
        runCurrent()

        assertThat(recorder.discards).isEqualTo(1)
        assertThat(recorder.closed.none { it.exists() }).isTrue()
        assertThat(sink.partials.last()).isNull()
        assertThat(sink.inserted).isEmpty()
    }

    @Test
    fun `cancel during the send then undo resumes a new segmented recording in the same session`() = runTest {
        val sink = Sink()
        val recorder = SegmentedRecorder()
        val c = coordinator(this, recorder, ScriptedTranscriber(script), sink)

        c.onIntent(GestureIntent.START)
        c.onIntent(GestureIntent.PAUSE)
        advanceUntilIdle()
        c.onIntent(GestureIntent.RESUME)
        c.onIntent(GestureIntent.STOP) // fecha seg-2 e começa a enviar
        c.onCancel() // cancela durante o envio
        c.onUndoCancel()
        c.onIntent(GestureIntent.RESUME) // recorder foi encerrado: nova gravação segmentada
        c.onIntent(GestureIntent.STOP) // seg-3
        advanceUntilIdle()

        assertThat(recorder.segmentedStarts).isEqualTo(2)
        assertThat(sink.inserted).containsExactly("primeira parte segunda parte fim")
    }

    @Test
    fun `with the toggle off the session is the usual single file`() = runTest {
        val sink = Sink()
        val recorder = SegmentedRecorder()
        val c = coordinator(this, recorder, ScriptedTranscriber(script), sink, partialOn = false)

        c.onIntent(GestureIntent.START)
        c.onIntent(GestureIntent.STOP)
        advanceUntilIdle()

        assertThat(recorder.starts).isEqualTo(1)
        assertThat(recorder.segmentedStarts).isEqualTo(0)
        assertThat(sink.inserted).containsExactly("primeira parte")
        assertThat(sink.partials.filterNotNull()).isEmpty()
    }
}
