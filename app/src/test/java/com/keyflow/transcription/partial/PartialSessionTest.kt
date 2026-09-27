package com.keyflow.transcription.partial

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ErrorKind
import com.keyflow.core.TranscriptionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** NM-T16: buffer do parcial — ordem, edições como verdade, falha re-tentada, envio. */
@OptIn(ExperimentalCoroutinesApi::class)
class PartialSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun segment(name: String) = tmp.newFile("$name.m4a")

    /** Transcriber fake: cada arquivo responde o que [answers] mandar; [gates] seguram a resposta. */
    private class FakeTranscriber {
        val answers = mutableMapOf<String, MutableList<TranscriptionResult>>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val calls = mutableListOf<String>()

        suspend fun transcribe(file: File): TranscriptionResult {
            val key = file.nameWithoutExtension
            calls += key
            gates[key]?.await()
            val queue = answers.getValue(key)
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    private fun TestScope.session(fake: FakeTranscriber, changes: MutableList<PartialSnapshot> = mutableListOf()) =
        PartialSession(this, fake::transcribe) { changes += it }

    @Test
    fun `each pause appends its text in order`() = runTest {
        val fake = FakeTranscriber().apply {
            answers["a"] = mutableListOf(TranscriptionResult.Success("olá"))
            answers["b"] = mutableListOf(TranscriptionResult.Success(" tudo bem "))
        }
        val s = session(fake)

        s.enqueue(segment("a"))
        s.enqueue(segment("b"))
        advanceUntilIdle()

        assertThat(s.text).isEqualTo("olá tudo bem")
    }

    @Test
    fun `a slow first segment still lands before the second`() = runTest {
        val fake = FakeTranscriber().apply {
            answers["a"] = mutableListOf(TranscriptionResult.Success("primeiro"))
            answers["b"] = mutableListOf(TranscriptionResult.Success("segundo"))
            gates["a"] = CompletableDeferred()
        }
        val s = session(fake)

        s.enqueue(segment("a"))
        s.enqueue(segment("b"))
        advanceUntilIdle()
        assertThat(s.text).isEmpty()
        assertThat(s.snapshot().transcribing).isTrue()

        fake.gates.getValue("a").complete(Unit)
        advanceUntilIdle()

        assertThat(s.text).isEqualTo("primeiro segundo")
        assertThat(fake.calls).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `new text goes after the user's edits and never overwrites them`() = runTest {
        val fake = FakeTranscriber().apply {
            answers["a"] = mutableListOf(TranscriptionResult.Success("oi mundo"))
            answers["b"] = mutableListOf(TranscriptionResult.Success("mais"))
        }
        val s = session(fake)
        s.enqueue(segment("a"))
        advanceUntilIdle()

        s.edit("Oi, mundo!")
        s.enqueue(segment("b"))
        advanceUntilIdle()

        assertThat(s.text).isEqualTo("Oi, mundo! mais")
    }

    @Test
    fun `a failed segment holds the later ones and is retried at the next pause`() = runTest {
        val fake = FakeTranscriber().apply {
            answers["a"] = mutableListOf(TranscriptionResult.Error(ErrorKind.NETWORK, "x"), TranscriptionResult.Success("um"))
            answers["b"] = mutableListOf(TranscriptionResult.Success("dois"))
            answers["c"] = mutableListOf(TranscriptionResult.Success("três"))
        }
        val s = session(fake)

        s.enqueue(segment("a"))
        s.enqueue(segment("b"))
        advanceUntilIdle()
        assertThat(s.text).isEmpty()
        assertThat(s.snapshot().failed).isEqualTo(1)

        s.enqueue(segment("c"))
        advanceUntilIdle()

        assertThat(s.text).isEqualTo("um dois três")
        assertThat(s.snapshot().failed).isEqualTo(0)
    }

    @Test
    fun `finish retries failures and returns the whole text`() = runTest {
        val fake = FakeTranscriber().apply {
            answers["a"] = mutableListOf(TranscriptionResult.Error(ErrorKind.TIMEOUT, "x"), TranscriptionResult.Success("um"))
            answers["b"] = mutableListOf(TranscriptionResult.Success("dois"))
        }
        val s = session(fake)
        s.enqueue(segment("a"))
        advanceUntilIdle()

        s.enqueue(segment("b"))
        val result = async { s.finish() }
        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(TranscriptionResult.Success("um dois"))
    }

    @Test
    fun `finish reports a segment that keeps failing`() = runTest {
        val fake = FakeTranscriber().apply {
            answers["a"] = mutableListOf(TranscriptionResult.Error(ErrorKind.NETWORK, "fora do ar"))
        }
        val s = session(fake)
        s.enqueue(segment("a"))

        val result = async { s.finish() }
        advanceUntilIdle()

        assertThat(result.await()).isEqualTo(TranscriptionResult.Error(ErrorKind.NETWORK, "fora do ar"))
    }

    @Test
    fun `finish with nothing said is empty audio`() = runTest {
        val fake = FakeTranscriber().apply { answers["a"] = mutableListOf(TranscriptionResult.Success("  ")) }
        val s = session(fake)
        s.enqueue(segment("a"))

        val result = async { s.finish() }
        advanceUntilIdle()

        assertThat((result.await() as TranscriptionResult.Error).kind).isEqualTo(ErrorKind.EMPTY_AUDIO)
    }

    @Test
    fun `discard deletes the segments and clears the buffer`() = runTest {
        val fake = FakeTranscriber().apply { answers["a"] = mutableListOf(TranscriptionResult.Success("x")) }
        val s = session(fake)
        val file = segment("a")
        s.enqueue(file)
        advanceUntilIdle()

        s.discard()

        assertThat(file.exists()).isFalse()
        assertThat(s.isEmpty).isTrue()
    }

    @Test
    fun `changes are reported for the ui`() = runTest {
        val fake = FakeTranscriber().apply { answers["a"] = mutableListOf(TranscriptionResult.Success("oi")) }
        val changes = mutableListOf<PartialSnapshot>()
        val s = session(fake, changes)

        s.enqueue(segment("a"))
        advanceUntilIdle()

        assertThat(changes.first().transcribing).isTrue()
        assertThat(changes.last()).isEqualTo(PartialSnapshot(text = "oi", transcribing = false, failed = 0))
    }
}
