package com.keyflow.speech.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** O contrato no processo principal: arquivo ausente não chama o endpoint (não abre o :speech nem socket). */
class LocalSpeechEngineTest {

    private class FakeEndpoint : SpeechEndpoint {
        var calls = 0
        override suspend fun speak(
            model: File,
            tokens: File,
            text: String,
            speed: Float,
            onPcm: (pcm: ByteArray, sampleRate: Int) -> Unit,
        ): SpeechOutcome {
            calls++
            onPcm(ByteArray(4), 22050)
            return SpeechOutcome.Ok(7)
        }
    }

    @Test
    fun semArquivoNaoChamaOEndpoint() = runBlocking {
        val endpoint = FakeEndpoint()
        val engine = LocalSpeechEngine(files = { null }, endpoint = endpoint, nowMs = { 0L })
        val event = engine.speak(SpeechRequest("oi", 1f)).toList().single()
        val error = event as SpeechEvent.Error
        assertThat(error.kind).isEqualTo(SpeechError.MODEL_MISSING)
        assertThat(endpoint.calls).isEqualTo(0)
    }

    @Test
    fun comArquivoEmiteAudioFirstAudioEDone() = runBlocking {
        val dir = File.createTempFile("vits", "").apply { delete(); mkdirs() }
        val model = File(dir, "model.onnx").apply { writeBytes(byteArrayOf(1)) }
        val tokens = File(dir, "tokens.txt").apply { writeText("a 1\n") }
        var now = 0L
        val endpoint = FakeEndpoint()
        val engine = LocalSpeechEngine(files = { model to tokens }, endpoint = endpoint, nowMs = { now.also { now += 15 } })
        val events = engine.speak(SpeechRequest("oi", 1f)).toList()
        assertThat(events.map { it::class.simpleName }).containsExactly("Audio", "FirstAudio", "Done").inOrder()
        assertThat((events[1] as SpeechEvent.FirstAudio).latencyMs).isEqualTo(15)
        assertThat((events[2] as SpeechEvent.Done).durationMs).isEqualTo(2 * 1000 / 22050)
        assertThat(endpoint.calls).isEqualTo(1)
    }
}
