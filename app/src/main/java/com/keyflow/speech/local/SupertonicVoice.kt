package com.keyflow.speech.local

import com.keyflow.onnx.FloatTensor
import com.keyflow.onnx.LongTensor
import com.keyflow.onnx.OnnxOptions
import com.keyflow.onnx.OnnxSession
import com.keyflow.onnx.Tensor
import com.keyflow.speech.frontend.SupertonicText
import java.io.File
import java.util.Random
import org.json.JSONArray
import org.json.JSONObject

/** Arquivos irmãos do pin oficial; nenhuma sessão abre com uma instalação incompleta. */
object SupertonicFiles {
    val names = listOf("duration_predictor.onnx", "text_encoder.onnx", "vector_estimator.onnx", "vocoder.onnx",
        "unicode_indexer.json", "tts.json", "M1.json")
    fun complete(config: File, index: File): Boolean = config.name == "tts.json" && index.name == "unicode_indexer.json" &&
        config.parentFile == index.parentFile && names.all { File(config.parentFile, it).let { f -> f.isFile && f.length() > 0 } }
}

/** Pipeline oficial Supertone, py/helper.py (MIT; copyright e licença em app/licenses/MIT_supertonic.txt). */
internal class SupertonicVoice(
    private val open: (String) -> OnnxSession = { OnnxSession.open(it, OnnxOptions(threads = 2, cpuArena = false)) },
    private val gaussian: () -> Float = Random().let { random -> { random.nextGaussian().toFloat() } },
    private val steps: Int = 8,
) : LocalVoice {
    private var loadedKey: String? = null
    private val sessions = linkedMapOf<String, OnnxSession>()
    private var frontend: SupertonicText? = null
    private var styleTtl: FloatTensor? = null
    private var styleDp: FloatTensor? = null
    private var sampleRate = 44100
    private var blockSize = 3072
    private var latentDim = 144

    override fun available(model: File, tokens: File) = SupertonicFiles.complete(model, tokens)

    override fun synthesize(model: File, tokens: File, text: String, speed: Float,
                            cancelled: () -> Boolean, emit: (ByteArray, Int) -> Unit) {
        if (cancelled()) return
        ensure(model, tokens)
        val pace = if (speed.isFinite()) speed.coerceIn(0.75f, 1.5f) else 1f
        for ((position, chunk) in SupertonicText.chunks(text).withIndex()) {
            if (cancelled()) return
            val ids = frontend!!.ids(chunk)
            val textIds = LongTensor(longArrayOf(1, ids.size.toLong()), ids)
            val textMask = FloatTensor(longArrayOf(1, 1, ids.size.toLong()), FloatArray(ids.size) { 1f })
            val duration = run("duration_predictor", mapOf("text_ids" to textIds, "style_dp" to styleDp!!, "text_mask" to textMask))
            val samples = (duration.data.single() / pace * sampleRate).toInt()
            require(samples > 0) { "duração inválida do Supertonic" }
            if (cancelled()) return
            val embedding = run("text_encoder", mapOf("text_ids" to textIds, "style_ttl" to styleTtl!!, "text_mask" to textMask))
            val length = (samples + blockSize - 1) / blockSize
            var latent = FloatTensor(longArrayOf(1, latentDim.toLong(), length.toLong()), FloatArray(latentDim * length) { gaussian() })
            val mask = FloatTensor(longArrayOf(1, 1, length.toLong()), FloatArray(length) { 1f })
            for (step in 0 until steps) {
                if (cancelled()) return
                latent = run("vector_estimator", mapOf("noisy_latent" to latent, "text_emb" to embedding,
                    "style_ttl" to styleTtl!!, "text_mask" to textMask, "latent_mask" to mask,
                    "current_step" to FloatTensor(longArrayOf(1), floatArrayOf(step.toFloat())),
                    "total_step" to FloatTensor(longArrayOf(1), floatArrayOf(steps.toFloat()))))
            }
            if (cancelled()) return
            val wave = run("vocoder", mapOf("latent" to latent))
            if (cancelled()) return
            if (position > 0) emit(ByteArray((sampleRate * 0.3).toInt() * 2), sampleRate)
            // O exemplo oficial corta o padding do vocoder pela duração prevista, após aplicar speed.
            val pcm = VitsPcm.encode(wave.data.copyOfRange(0, minOf(samples, wave.data.size)))
            for (part in VitsPcm.chunks(pcm)) {
                if (cancelled()) return
                emit(part, sampleRate)
            }
        }
    }

    private fun run(name: String, values: Map<String, Tensor>): FloatTensor {
        val session = sessions.getValue(name)
        return session.run(session.inputNames.map { values.getValue(it) }).first() as FloatTensor
    }

    private fun ensure(configFile: File, index: File) {
        require(available(configFile, index)) { "arquivos do Supertonic ausentes" }
        val dir = configFile.parentFile!!
        val key = SupertonicFiles.names.joinToString { name -> File(dir, name).let { "${it.path}:${it.length()}:${it.lastModified()}" } }
        if (loadedKey == key) return
        close()
        try {
            val config = JSONObject(configFile.readText())
            val ae = config.getJSONObject("ae")
            val ttl = config.getJSONObject("ttl")
            sampleRate = ae.getInt("sample_rate")
            blockSize = ae.getInt("base_chunk_size") * ttl.getInt("chunk_compress_factor")
            latentDim = ttl.getInt("latent_dim") * ttl.getInt("chunk_compress_factor")
            require(sampleRate > 0 && blockSize > 0 && latentDim > 0 && steps > 0)
            frontend = SupertonicText.parse(index.readText())
            val style = JSONObject(File(dir, "M1.json").readText())
            styleTtl = styleTensor(style.getJSONObject("style_ttl"))
            styleDp = styleTensor(style.getJSONObject("style_dp"))
            for (name in listOf("duration_predictor", "text_encoder", "vector_estimator", "vocoder")) {
                sessions[name] = open(File(dir, "$name.onnx").path)
            }
            loadedKey = key
        } catch (error: Throwable) { close(); throw error }
    }

    private fun styleTensor(json: JSONObject): FloatTensor {
        val dims = json.getJSONArray("dims")
        val shape = LongArray(dims.length()) { dims.getLong(it) }
        val data = ArrayList<Float>()
        fun flatten(array: JSONArray) {
            for (i in 0 until array.length()) {
                val value = array.get(i)
                if (value is JSONArray) flatten(value) else data += (value as Number).toFloat()
            }
        }
        flatten(json.getJSONArray("data"))
        return FloatTensor(shape, data.toFloatArray())
    }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        loadedKey = null
        frontend = null
        styleTtl = null
        styleDp = null
    }
}

/** Um único motor residente: trocar o modelo fecha as sessões do anterior. */
internal class LocalVoiceRouter(
    private val vits: LocalVoice = VitsVoice(),
    private val supertonic: LocalVoice = SupertonicVoice(),
) : LocalVoice {
    private var active: LocalVoice? = null
    private fun voice(model: File): LocalVoice = if (model.name == "tts.json") supertonic else vits
    override fun available(model: File, tokens: File) = voice(model).available(model, tokens)
    override fun synthesize(model: File, tokens: File, text: String, speed: Float,
                            cancelled: () -> Boolean, emit: (ByteArray, Int) -> Unit) {
        val next = voice(model)
        if (active !== next) { active?.close(); active = next }
        next.synthesize(model, tokens, text, speed, cancelled, emit)
    }
    override fun close() { active?.close(); active = null }
}
