package com.keyflow.speech.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechRequest
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    fun chamadasConcorrentesSemArquivoRespondemModelMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connection = SpeechConnection(context)
        val endpoint = AidlSpeechEndpoint(connection, ramEstimateMb = 256)
        try {
            val outcomes = withTimeout(15_000) {
                List(2) { n ->
                    async {
                        endpoint.speak(File("/nao/existe/model.onnx"), File("/nao/existe/tokens.txt"),
                            "fala $n", 1f) { _, _ -> }
                    }
                }.awaitAll()
            }
            assertEquals(2, outcomes.size)
            outcomes.forEach { assertEquals(SpeechProtocol.MODEL_MISSING, (it as SpeechOutcome.Err).kind) }
        } finally {
            connection.close()
        }
    }

}
