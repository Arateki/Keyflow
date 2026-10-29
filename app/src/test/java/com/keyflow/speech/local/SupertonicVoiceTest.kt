package com.keyflow.speech.local

import com.keyflow.onnx.FloatTensor
import com.keyflow.onnx.OnnxSession
import com.keyflow.onnx.Tensor
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class SupertonicVoiceTest {
    private fun files(): Pair<File, File> {
        val dir = File.createTempFile("supertonic", "").apply { delete(); mkdirs() }
        for (name in SupertonicFiles.names) File(dir, name).writeText("x")
        File(dir, "tts.json").writeText("""{"ae":{"sample_rate":1000,"base_chunk_size":2},"ttl":{"chunk_compress_factor":2,"latent_dim":1}}""")
        File(dir, "unicode_indexer.json").writeText(IntArray(65536) { it }.joinToString(",", "[", "]"))
        File(dir, "M1.json").writeText("""{"style_ttl":{"dims":[1,1,2],"data":[[[0.1,0.2]]]},"style_dp":{"dims":[1,1,1],"data":[[[0.3]]]}}""")
        return File(dir, "tts.json") to File(dir, "unicode_indexer.json")
    }

    private class Sessions {
        val calls = mutableListOf<Pair<String, Map<String, Tensor>>>()
        var opened = 0
        var closed = 0
        fun open(path: String): OnnxSession {
            opened++
            val name = File(path).nameWithoutExtension
            return object : OnnxSession {
                override val inputNames = when (name) {
                    "duration_predictor" -> listOf("style_dp", "text_mask", "text_ids")
                    "text_encoder" -> listOf("text_mask", "style_ttl", "text_ids")
                    "vector_estimator" -> listOf("total_step", "noisy_latent", "current_step", "latent_mask", "text_emb", "text_mask", "style_ttl")
                    else -> listOf("latent")
                }
                override val outputNames = listOf("output")
                override fun run(inputs: List<Tensor>): List<Tensor> {
                    val feed = inputNames.zip(inputs).toMap()
                    calls += name to feed
                    return listOf(when (name) {
                        "duration_predictor" -> FloatTensor(longArrayOf(1), floatArrayOf(0.024f))
                        "text_encoder" -> FloatTensor(longArrayOf(1, 1, 1), floatArrayOf(2f))
                        "vector_estimator" -> feed.getValue("noisy_latent")
                        else -> FloatTensor(longArrayOf(1, 64), FloatArray(64) { 0.5f })
                    })
                }
                override fun close() { closed++ }
            }
        }
    }

    @Test fun quatroOnnxOitoPassosIdiomaPtEVelocidadeAtingemOTensorReal() {
        val (config, index) = files()
        val sessions = Sessions()
        val voice = SupertonicVoice(sessions::open, { 1f })
        val audio = mutableListOf<ByteArray>()
        voice.synthesize(config, index, "Olá", 1.5f, { false }) { pcm, rate -> assertEquals(1000, rate); audio += pcm }
        assertEquals(4, sessions.opened)
        assertEquals(listOf("duration_predictor", "text_encoder") + List(8) { "vector_estimator" } + "vocoder", sessions.calls.map { it.first })
        val steps = sessions.calls.filter { it.first == "vector_estimator" }.map { it.second }
        assertEquals((0..7).map { it.toFloat() }, steps.map { (it.getValue("current_step") as FloatTensor).data.single() })
        assertTrue(steps.all { (it.getValue("total_step") as FloatTensor).data.single() == 8f })
        assertArrayEquals(longArrayOf(1, 2, 4), steps.first().getValue("noisy_latent").shape)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), (steps.first().getValue("latent_mask") as FloatTensor).data, 0f)
        val ids = (sessions.calls.first().second.getValue("text_ids") as com.keyflow.onnx.LongTensor).data
        assertArrayEquals("<pt>Ola\u0301.</pt>".map { it.code.toLong() }.toLongArray(), ids)
        assertEquals(16 * 2, audio.sumOf { it.size }) // 24 ms / 1,5; não é resampling do áudio.
        voice.close(); assertEquals(4, sessions.closed)
    }

    @Test fun cacheReutilizaEFechaTodasAsSessoes() {
        val (config, index) = files(); val sessions = Sessions(); val voice = SupertonicVoice(sessions::open)
        repeat(2) { voice.synthesize(config, index, "voz", 1f, { false }) { _, _ -> } }
        assertEquals(4, sessions.opened)
        voice.close(); voice.close(); assertEquals(4, sessions.closed)
    }

    @Test fun cancelamentoParaAntesDoProximoPassoESemPcm() {
        val (config, index) = files(); val sessions = Sessions(); val voice = SupertonicVoice(sessions::open)
        var pcm = 0
        voice.synthesize(config, index, "voz", 1f, { sessions.calls.size >= 3 }) { _, _ -> pcm++ }
        assertEquals(listOf("duration_predictor", "text_encoder", "vector_estimator"), sessions.calls.map { it.first })
        assertEquals(0, pcm)
        voice.close()
    }

    @Test fun arquivoAuxiliarAusenteViraModelMissingSemAbrirOrt() {
        val (config, index) = files(); File(config.parentFile, "vocoder.onnx").delete()
        val sessions = Sessions(); val voice = LocalVoiceRouter(supertonic = SupertonicVoice(sessions::open))
        val errors = mutableListOf<Int>()
        val core = SpeechServiceCore(voice, { 2000 }, object : SpeechIdleScheduler {
            override fun schedule(delayMs: Long, block: () -> Unit) = SpeechCancellable {}
        }, Executor { it.run() })
        core.speak(SpeechJob(config.path, index.path, "voz", 1f, 512), object : SpeechServiceCore.Listener {
            override fun onPcm(pcm: ByteArray, sampleRate: Int) { fail("não deve sintetizar") }
            override fun onDone(peakRssKb: Long) { fail("não deve concluir") }
            override fun onError(kind: Int, detail: String) { errors += kind }
        })
        assertEquals(listOf(SpeechProtocol.MODEL_MISSING), errors)
        assertEquals(0, sessions.opened)
    }

    @Test fun falhaAoAbrirFechaAsSessoesJaCriadas() {
        val (config, index) = files(); val sessions = Sessions()
        val voice = SupertonicVoice({ path -> if (path.endsWith("vocoder.onnx")) error("simulada") else sessions.open(path) })
        assertThrows(IllegalStateException::class.java) { voice.synthesize(config, index, "voz", 1f, { false }) { _, _ -> } }
        assertEquals(3, sessions.closed)
    }
}
