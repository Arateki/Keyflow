package com.keyflow.speech.local

import com.keyflow.onnx.FloatTensor
import com.keyflow.onnx.LongTensor
import com.keyflow.onnx.OnnxException
import com.keyflow.onnx.OnnxOptions
import com.keyflow.onnx.OnnxSession
import com.keyflow.onnx.Tensor
import com.keyflow.speech.frontend.VitsCharacterFrontend
import java.io.File

/** Velocidade da aba → `length_scale` do VITS (FLOC-04). 1,0 usa a escala do modelo; o resto é `1 / speed`. */
internal object VitsPace {
    const val MIN_SPEED = 0.75f
    const val MAX_SPEED = 1.5f

    fun lengthScale(speed: Float, modelScale: Float): Float {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        return if (clamped == 1f) modelScale else 1f / clamped
    }
}

/** PCM 16-bit mono, little-endian, a partir do float do modelo, cortado em [-1, 1]. */
internal object VitsPcm {
    fun encode(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (sample in samples) {
            val clipped = sample.coerceIn(-1f, 1f)
            val v = (clipped * 32767f).toInt()
            out[i++] = (v and 0xff).toByte()
            out[i++] = (v shr 8 and 0xff).toByte()
        }
        return out
    }

    /** Pedaços curtos: o binder não leva o áudio inteiro de uma vez. */
    fun chunks(pcm: ByteArray, samplesPerChunk: Int = 4096): List<ByteArray> {
        val bytes = samplesPerChunk * 2
        if (pcm.isEmpty()) return emptyList()
        if (pcm.size <= bytes) return listOf(pcm)
        val out = ArrayList<ByteArray>((pcm.size + bytes - 1) / bytes)
        var offset = 0
        while (offset < pcm.size) {
            val end = (offset + bytes).coerceAtMost(pcm.size)
            out += pcm.copyOfRange(offset, end)
            offset = end
        }
        return out
    }
}

/**
 * Tensores do VITS de caracteres, na ordem em que a sessão declara as entradas.
 * Este modelo pede `input`, `input_lengths`, `scales`, `sid` e `langid`. Sem `sid`/`langid`, não são criados.
 */
internal object VitsTensors {
    fun inputs(
        names: List<String>,
        ids: LongArray,
        lengthScale: Float,
        noiseScale: Float,
        noiseScaleW: Float,
    ): List<Tensor> {
        val byName = HashMap<String, Tensor>(8)
        val tokens = LongTensor(longArrayOf(1, ids.size.toLong()), ids)
        val lengths = LongTensor(longArrayOf(1), longArrayOf(ids.size.toLong()))
        val scales = FloatTensor(longArrayOf(3), floatArrayOf(noiseScale, lengthScale, noiseScaleW))
        val speaker = LongTensor(longArrayOf(1), longArrayOf(0))
        byName["input"] = tokens
        byName["x"] = tokens
        byName["input_lengths"] = lengths
        byName["x_length"] = lengths
        byName["x_lengths"] = lengths
        byName["scales"] = scales
        byName["sid"] = speaker
        byName["langid"] = speaker
        return names.map { name ->
            byName[name] ?: throw OnnxException("entrada $name do VITS não é do frontend de caracteres")
        }
    }
}

/**
 * VITS Coqui de caracteres no `:speech` (FAL-T11). O frontend é o da FAL-T2. Não chama modelo de fonemas
 * (o pt-BR fica de fora: `frontend` diferente de `characters` recusa a sessão). A ORT é a do app, a mesma
 * `libonnxruntime.so` do sherpa — esta classe não carrega outra.
 */
internal class VitsVoice(
    private val sentencesOf: (VitsCharacterFrontend, String) -> List<IntArray> = { frontend, text -> frontend.sentences(text) },
    private val open: (String) -> OnnxSession = { path ->
        OnnxSession.open(path, OnnxOptions(threads = OnnxSession.DEFAULT_THREADS, cpuArena = false))
    },
) : LocalVoice {
    private var session: OnnxSession? = null
    private var loadedPath: String? = null
    private var frontend: VitsCharacterFrontend? = null
    private var sampleRate = 22050
    private var modelScale = 1f
    private var noiseScale = 0.667f
    private var noiseScaleW = 0.8f

    override fun synthesize(
        model: File,
        tokens: File,
        text: String,
        speed: Float,
        cancelled: () -> Boolean,
        emit: (pcm: ByteArray, sampleRate: Int) -> Unit,
    ) {
        val active = ensure(model, tokens)
        val scale = VitsPace.lengthScale(speed, modelScale)
        val sentences = sentencesOf(frontend!!, text)
        for (ids in sentences) {
            if (cancelled()) return
            if (ids.isEmpty()) continue
            val audio = active.run(VitsTensors.inputs(active.inputNames, ids.map { it.toLong() }.toLongArray(), scale, noiseScale, noiseScaleW))
            val wave = (audio.firstOrNull() as? FloatTensor)?.data ?: throw OnnxException("o VITS não devolveu áudio float")
            if (wave.isEmpty()) continue
            for (chunk in VitsPcm.chunks(VitsPcm.encode(wave))) {
                if (cancelled()) return
                emit(chunk, sampleRate)
            }
        }
    }

    private fun ensure(model: File, tokens: File): OnnxSession {
        session?.let { if (loadedPath == model.path) return it }
        close()
        val opened = open(model.path)
        val meta = opened.metadata
        val frontendName = meta["frontend"]
        if (frontendName != null && frontendName != "characters") {
            opened.close()
            throw OnnxException("modelo com frontend $frontendName; esta voz só aceita caracteres")
        }
        val text = tokens.readText()
        val addBlank = meta["add_blank"] != "0"
        session = opened
        loadedPath = model.path
        frontend = VitsCharacterFrontend.parse(text, addBlank = addBlank, useEosBos = meta["use_eos_bos"] == "1")
        sampleRate = meta["sample_rate"]?.toIntOrNull()?.takeIf { it > 0 } ?: 22050
        modelScale = meta["length_scale"]?.toFloatOrNull() ?: 1f
        noiseScale = meta["noise_scale"]?.toFloatOrNull() ?: 0.667f
        noiseScaleW = meta["noise_scale_w"]?.toFloatOrNull() ?: 0.8f
        return opened
    }

    override fun close() {
        session?.let { runCatching { it.close() } }
        session = null
        loadedPath = null
        frontend = null
    }
}
