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
/**
 * Como abrir a sessão. Os padrões são os de menor RAM (sem arena nem plano de memória por forma): foram o ponto de partida do OCR, em que
 * a forma muda a cada chamada. [cpuArena] liga o alocador em arena da ORT (reaproveita os buffers entre operações: mais rápido, mais RSS);
 * [memoryPattern] liga o plano de memória por forma (só ajuda com forma fixa). A escolha do OCR vem da medição (STATE.md/licoes VC-T6).
 */
data class OnnxOptions(val threads: Int = 2, val cpuArena: Boolean = false, val memoryPattern: Boolean = false)

interface OnnxSession : AutoCloseable {
    val inputNames: List<String>
    val outputNames: List<String>

    /** Metadados custom do ONNX (o VITS traz `sample_rate`, `add_blank`, `frontend`). Vazio se o modelo não declara. */
    val metadata: Map<String, String> get() = emptyMap()

    /** Executa o modelo. Falha de forma/tipo/memória nativa vira [OnnxException]. */
    fun run(inputs: List<Tensor>): List<Tensor>

    companion object {
        /** Abre [modelPath] na ORT do app com as [options] dadas. */
        fun open(modelPath: String, options: OnnxOptions): OnnxSession = OrtOnnxSession.open(modelPath, options)

        /** Abre [modelPath] na ORT do app. [threads] = threads de cálculo intra-operador. */
        fun open(modelPath: String, threads: Int = DEFAULT_THREADS): OnnxSession = open(modelPath, OnnxOptions(threads))

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
    override val metadata: Map<String, String> = try {
        session.metadata.customMetadata.toMap()
    } catch (e: OrtException) {
        emptyMap()
    }

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
        fun open(modelPath: String, o: OnnxOptions): OnnxSession {
            val env = OnnxRuntime.environment()
            var options: OrtSession.SessionOptions? = null
            try {
                options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(o.threads.coerceAtLeast(1))
                    setInterOpNumThreads(1)
                    setMemoryPatternOptimization(o.memoryPattern)
                    setCPUArenaAllocator(o.cpuArena)
                }
                return OrtOnnxSession(env, options, env.createSession(modelPath, options))
            } catch (e: OrtException) {
                runCatching { options?.close() }
                throw OnnxException("a ONNX Runtime não abriu $modelPath: ${e.message}", e)
            }
        }
    }
}
