package com.keyflow.onnx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Uma sessão de inferência de um `.onnx`. As entradas e saídas são posicionais, na ordem em que o modelo as declara
 * ([inputNames]/[outputNames]). Fecha liberando a memória nativa; não é seguro usar depois de [close].
 *
 * [run] pode ser chamado de várias threads (a ORT permite), mas o OCR roda numa só.
 */
interface OnnxSession : AutoCloseable {
    val inputNames: List<String>
    val outputNames: List<String>

    /** Executa o modelo. Falha de forma/tipo/memória nativa vira [OnnxException]. */
    fun run(inputs: List<Tensor>): List<Tensor>

    companion object {
        /** Abre [modelPath] na ORT do app. [threads] = threads de cálculo intra-operador. */
        fun open(modelPath: String, threads: Int = DEFAULT_THREADS): OnnxSession = OrtOnnxSession.open(modelPath, threads)

        const val DEFAULT_THREADS = 2
    }
}

/** A implementação sobre o `onnxruntime-android` (ponte JNI para a `libonnxruntime.so` do sherpa). */
internal class OrtOnnxSession private constructor(
    private val env: OrtEnvironment,
    private val options: OrtSession.SessionOptions,
    private val session: OrtSession,
) : OnnxSession {

    override val inputNames: List<String> = session.inputNames.toList()
    override val outputNames: List<String> = session.outputNames.toList()

    override fun run(inputs: List<Tensor>): List<Tensor> {
        if (inputs.size != inputNames.size) {
            throw OnnxException("o modelo pede ${inputNames.size} entrada(s), recebi ${inputs.size}")
        }
        val created = ArrayList<OnnxTensor>(inputs.size)
        try {
            val feed = LinkedHashMap<String, OnnxTensor>()
            inputs.forEachIndexed { i, tensor ->
                val onnx = when (tensor) {
                    is FloatTensor -> OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor.data), tensor.shape)
                    is LongTensor -> OnnxTensor.createTensor(env, LongBuffer.wrap(tensor.data), tensor.shape)
                }
                created += onnx
                feed[inputNames[i]] = onnx
            }
            session.run(feed).use { result ->
                return outputNames.map { name ->
                    val value = result.get(name).orElseThrow { OnnxException("o modelo não devolveu a saída $name") }
                    toTensor(name, value as? OnnxTensor ?: throw OnnxException("a saída $name não é um tensor"))
                }
            }
        } catch (e: OrtException) {
            throw OnnxException("a ONNX Runtime recusou a execução: ${e.message}", e)
        } finally {
            created.forEach { it.close() }
        }
    }

    private fun toTensor(name: String, tensor: OnnxTensor): Tensor {
        val shape = tensor.info.shape
        return when (tensor.info.type) {
            OnnxJavaType.FLOAT -> {
                val buffer = tensor.floatBuffer
                FloatTensor(shape, FloatArray(buffer.remaining()).also { buffer.get(it) })
            }
            OnnxJavaType.INT64 -> {
                val buffer = tensor.longBuffer
                LongTensor(shape, LongArray(buffer.remaining()).also { buffer.get(it) })
            }
            else -> throw OnnxException("a saída $name é do tipo ${tensor.info.type}; só Float e Long são suportados")
        }
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { options.close() }
    }

    companion object {
        fun open(modelPath: String, threads: Int): OnnxSession {
            val env = OnnxRuntime.environment()
            var options: OrtSession.SessionOptions? = null
            try {
                options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads.coerceAtLeast(1))
                    setInterOpNumThreads(1)
                    // Forma dinâmica (largura do texto, altura da faixa): o plano de memória por forma e a arena que só cresce só
                    // custam RAM no :vision (medido na VC-T6).
                    setMemoryPatternOptimization(false)
                    setCPUArenaAllocator(false)
                }
                return OrtOnnxSession(env, options, env.createSession(modelPath, options))
            } catch (e: OrtException) {
                runCatching { options?.close() }
                throw OnnxException("a ONNX Runtime não abriu $modelPath: ${e.message}", e)
            }
        }
    }
}
