package com.keyflow.speech.local

import com.keyflow.onnx.FloatTensor
import com.keyflow.onnx.LongTensor
import com.keyflow.onnx.OnnxSession
import com.keyflow.onnx.Tensor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FAL-T11: frontend da T2, length_scale e PCM, sem o ONNX de 71 MB. */
class VitsVoiceTest {

    private class FakeSession(
        override val inputNames: List<String>,
        override val metadata: Map<String, String>,
        val runs: MutableList<List<Tensor>>,
    ) : OnnxSession {
        override val outputNames = listOf("output")
        override fun run(inputs: List<Tensor>): List<Tensor> {
            runs += inputs
            return listOf(FloatTensor(longArrayOf(2), floatArrayOf(0f, 1f)))
        }
        override fun close() = Unit
    }

    private fun tokens(): File {
        val file = File.createTempFile("tokens", ".txt")
        file.writeText("<PAD> 0\n<EOS> 1\n<BOS> 2\n<BLNK> 3\na 4\nA 4\n. 5\n")
        return file
    }

    @Test
    fun velocidadeUmUsaAEscalaDoModeloEOutraUsaOInverso() {
        assertEquals(1f, VitsPace.lengthScale(1f, 1f))
        assertEquals(0.8f, VitsPace.lengthScale(1f, 0.8f))
        assertEquals(1f / 1.5f, VitsPace.lengthScale(1.5f, 1f))
        assertEquals(1f / 0.75f, VitsPace.lengthScale(0.5f, 1f))
        assertEquals(1f / 1.5f, VitsPace.lengthScale(2f, 1f))
    }

    @Test
    fun pcmCortaNoIntervaloEParteEmPedaços() {
        val pcm = VitsPcm.encode(floatArrayOf(0f, 1f, -2f))
        assertEquals(0, pcm[0].toInt())
        assertEquals(0, pcm[1].toInt())
        assertEquals(0xff, pcm[2].toInt() and 0xff)
        assertEquals(0x7f, pcm[3].toInt() and 0xff)
        assertEquals(0x01, pcm[4].toInt() and 0xff)
        assertEquals(0x80.toByte(), pcm[5])
        // 1000 amostras = 2000 bytes; 8000 bytes viram 4 pedaços.
        assertEquals(4, VitsPcm.chunks(ByteArray(8000), samplesPerChunk = 1000).size)
    }

    @Test
    fun frontendEEscalaChegamNaSessao() {
        val runs = ArrayList<List<Tensor>>()
        val session = FakeSession(
            inputNames = listOf("input", "input_lengths", "scales", "sid", "langid"),
            metadata = mapOf("frontend" to "characters", "add_blank" to "1", "sample_rate" to "22050", "length_scale" to "1"),
            runs = runs,
        )
        val voice = VitsVoice { session }
        val model = File.createTempFile("model", ".onnx")
        voice.synthesize(model, tokens(), "a.", 1f, { false }) { _, rate ->
            assertEquals(22050, rate)
        }
        val scales = runs.single()[2] as FloatTensor
        assertEquals(listOf(0.667f, 1f, 0.8f), scales.data.toList())
        val ids = (runs.single()[0] as LongTensor).data.toList()
        assertEquals(listOf(3L, 4L, 3L, 5L, 3L), ids)
        voice.synthesize(model, tokens(), "a", 1.5f, { false }) { _, _ -> }
        val fast = runs[1][2] as FloatTensor
        assertEquals(1f / 1.5f, fast.data[1])
    }

    @Test
    fun modeloQueNaoEDeCaracteresNaoRoda() {
        val session = FakeSession(listOf("input"), mapOf("frontend" to "phonemes"), ArrayList())
        val voice = VitsVoice { session }
        var ran = false
        try {
            voice.synthesize(File.createTempFile("modelo", ".onnx"), tokens(), "a", 1f, { false }) { _, _ -> ran = true }
            throw AssertionError("devia recusar")
        } catch (e: com.keyflow.onnx.OnnxException) {
            assertTrue(e.message!!.contains("phonemes"))
        }
        assertTrue(!ran)
        assertTrue(session.runs.isEmpty())
    }

    @Test
    fun fraseVaziaNaoDescartaOResto() {
        val runs = ArrayList<List<Tensor>>()
        val session = FakeSession(
            inputNames = listOf("input", "input_lengths", "scales"),
            metadata = mapOf("frontend" to "characters", "add_blank" to "1", "sample_rate" to "22050"),
            runs = runs,
        )
        val voice = VitsVoice({ _, _ -> listOf(intArrayOf(), intArrayOf(3, 4, 3)) }) { session }
        var chunks = 0
        voice.synthesize(File.createTempFile("modelo", ".onnx"), tokens(), "a", 1f, { false }) { _, _ -> chunks++ }
        assertEquals(1, runs.size)
        assertTrue(chunks > 0)
    }
}
