package com.keyflow.speech.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** O processo `:speech` sobe no aparelho. Sem arquivo, a resposta é MODEL_MISSING. */
@RunWith(AndroidJUnit4::class)
class SpeechProcessTest {

    @Test
    fun semArquivoOMotorNaoLigaOServico() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var acquired = false
        val endpoint = object : SpeechEndpoint {
            override suspend fun speak(
                model: File,
                tokens: File,
                text: String,
                speed: Float,
                onPcm: (pcm: ByteArray, sampleRate: Int) -> Unit,
            ): SpeechOutcome {
                acquired = true
                return SpeechOutcome.Err(SpeechProtocol.ENGINE, "não devia")
            }
        }
        val event = LocalSpeechEngine(files = { null }, endpoint = endpoint)
            .speak(SpeechRequest("oi", 1f)).first()
        val error = event as SpeechEvent.Error
        assertEquals(SpeechError.MODEL_MISSING, error.kind)
        assertTrue(!acquired)
        assertTrue(context.packageName.isNotEmpty())
    }

    @Test
    fun servicoSemArquivoRespondeModelMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = SpeechConnection(context)
        val service = withTimeout(15_000) { connection.acquire() }
        try {
            val kind = withTimeout(15_000) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val cb = object : ISpeechCallback.Stub() {
                        override fun onPcm(pcm: ByteArray, sampleRate: Int) = Unit
                        override fun onDone(peakRssKb: Long) {
                            if (cont.isActive) cont.resumeWith(Result.success(-1))
                        }
                        override fun onError(kind: Int, message: String) {
                            if (cont.isActive) cont.resumeWith(Result.success(kind))
                        }
                    }
                    service.speak("/nao/existe/model.onnx", "/nao/existe/tokens.txt", "oi", 1f, 256, cb)
                }
            }
            assertEquals(SpeechProtocol.MODEL_MISSING, kind)
        } finally {
            connection.close()
        }
    }
}
