package com.keyflow.speech.local

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prova opt-in, fora do CI: só roda quando o `class` pede este método e o VITS já está em
 * `/data/local/tmp/vits-coqui-pt` (model.onnx + tokens.txt). Não baixa os 71 MB no teste.
 */
@RunWith(AndroidJUnit4::class)
class SpeechVitsProofTest {

    @Test
    fun fraseEmPortugues() = runBlocking {
        val args = InstrumentationRegistry.getArguments().getString("class").orEmpty()
        assumeTrue(
            "selecione SpeechVitsProofTest#fraseEmPortugues",
            args.split(',').any { it.endsWith("SpeechVitsProofTest#fraseEmPortugues") },
        )
        val staged = File("/data/local/tmp/vits-coqui-pt")
        val stagedModel = File(staged, "model.onnx")
        val stagedTokens = File(staged, "tokens.txt")
        assertTrue("falta ${stagedModel.path}", stagedModel.isFile && stagedModel.length() > 1_000_000)
        assertTrue("falta ${stagedTokens.path}", stagedTokens.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // O :speech é o mesmo uid do app. Copia para o cache: /data/local/tmp pode ser ilegível pelo domínio do app.
        val model = File(context.cacheDir, "vits-prova-model.onnx")
        val tokens = File(context.cacheDir, "vits-prova-tokens.txt")
        if (model.length() != stagedModel.length()) stagedModel.copyTo(model, overwrite = true)
        stagedTokens.copyTo(tokens, overwrite = true)
        var peak = 0L
        val endpoint = object : SpeechEndpoint {
            private val inner = AidlSpeechEndpoint(SpeechConnection.get(context), 256)
            override suspend fun speak(
                model: File,
                tokens: File,
                text: String,
                speed: Float,
                onPcm: (pcm: ByteArray, sampleRate: Int) -> Unit,
            ): SpeechOutcome {
                val outcome = inner.speak(model, tokens, text, speed, onPcm)
                if (outcome is SpeechOutcome.Ok) peak = outcome.peakRssKb
                return outcome
            }
        }
        val engine = LocalSpeechEngine(files = { model to tokens }, endpoint = endpoint)
        val start = System.nanoTime()
        val events = withTimeout(180_000) { engine.speak(SpeechRequest("Olá. Esta é a voz do KeyFlow.", 1f)).toList() }
        val wallMs = (System.nanoTime() - start) / 1_000_000
        val audio = events.filterIsInstance<SpeechEvent.Audio>()
        val first = events.filterIsInstance<SpeechEvent.FirstAudio>().single()
        val done = events.filterIsInstance<SpeechEvent.Done>().single()
        val pcm = audio.fold(ByteArray(0)) { acc, event -> acc + event.pcm }
        val wav = File(context.cacheDir, "vits-prova.wav")
        writeWav(wav, pcm, audio.first().sampleRate)
        val rtf = if (done.durationMs > 0) wallMs.toDouble() / done.durationMs else -1.0
        val line = "caso=ola; primeiro=${first.latencyMs}ms; duracao=${done.durationMs}ms; parede=${wallMs}ms; rtf=$rtf; " +
            "taxa=${audio.first().sampleRate}; amostras=${pcm.size / 2}; picoRssKb=$peak; wav=${wav.absolutePath}"
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nBancada vits: $line\n") })
        assertTrue(pcm.size > 4000)
        assertTrue(done.durationMs > 200)
        assertTrue(audio.first().sampleRate == 22050)
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        val header = ByteArray(44)
        val data = pcm.size
        val total = 36 + data
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        put32(header, 4, total)
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        put32(header, 16, 16)
        put16(header, 20, 1)
        put16(header, 22, 1)
        put32(header, 24, sampleRate)
        put32(header, 28, sampleRate * 2)
        put16(header, 32, 2)
        put16(header, 34, 16)
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        put32(header, 40, data)
        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(pcm)
        }
    }

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xff).toByte()
        b[o + 1] = (v shr 8 and 0xff).toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Int) {
        put16(b, o, v)
        put16(b, o + 2, v shr 16)
    }
}
