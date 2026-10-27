package com.keyflow.transcription.local

import com.keyflow.data.models.EngineType
import java.util.concurrent.Executor

/** Fronteira do reconhecedor nativo; permite provar a fila do serviço sem carregar ONNX na JVM. */
interface SpeechEngine {
    fun isLoaded(model: EngineModel): Boolean
    fun load(model: EngineModel)
    fun unload()
    fun transcribe(samples: FloatArray): String
}

/**
 * Fila do processo de voz, sem Android. Carga, inferência e descarga usam o mesmo executor de uma thread;
 * nenhum pedido binder espera por elas na thread da interface. Um modelo só é carregado se um cliente o pediu.
 */
internal class InferenceServiceCore(
    private val engine: SpeechEngine,
    private val executor: Executor,
    private val fileExists: (String) -> Boolean,
    private val availableBytes: () -> Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onLoaded: (EngineModel, Long, Long) -> Unit = { _, _, _ -> },
    private val onWarmed: (EngineModel, Long, Throwable?) -> Unit = { _, _, _ -> },
) {
    fun preload(model: () -> EngineModel, reply: (Result<String>) -> Unit) = submit(reply) {
        ensureLoaded(model(), warmUp = true)
        ""
    }

    fun transcribe(
        model: () -> EngineModel,
        reply: (Result<String>) -> Unit,
        work: (SpeechEngine, EngineModel) -> String,
    ) = submit(reply) {
        val requested = model()
        ensureLoaded(requested)
        work(engine, requested)
    }

    /** A descarga também espera os trabalhos anteriores, inclusive a inferência real. */
    fun shutdown() = executor.execute { engine.unload() }

    private fun ensureLoaded(model: EngineModel, warmUp: Boolean = false) {
        if (engine.isLoaded(model)) return
        val missing = model.files.filterNot(fileExists)
        if (missing.isNotEmpty()) {
            throw EngineFailure(EngineErrorKind.MODEL_MISSING, "faltam arquivos do ${model.modelId}: ${missing.map { java.io.File(it).name }}")
        }
        // A troca não precisa caber junto do modelo anterior.
        engine.unload()
        val available = availableBytes()
        if (!MemoryGuard.enoughFor(model.bytes, available)) {
            throw EngineFailure(
                EngineErrorKind.OOM,
                "memória insuficiente para ${model.modelId}: ${available shr 20} MB livres, precisa de " +
                    "${MemoryGuard.requiredBytes(model.bytes) shr 20} MB",
            )
        }
        try {
            val started = nowMs()
            engine.load(model)
            onLoaded(model, nowMs() - started, available)
        } catch (e: IllegalArgumentException) {
            throw EngineFailure(EngineErrorKind.ENGINE, "o engine recusou ${model.modelId}", e)
        }
        if (warmUp) warm(model)
    }

    /** Só a pré-carga tem folga antes do áudio real; Whisper acrescenta 10 s de padding por padrão e fica de fora. */
    private fun warm(model: EngineModel) {
        if (model.engineType != EngineType.TRANSDUCER) return
        val started = nowMs()
        val failure = try {
            engine.transcribe(FloatArray(16_000)) // 1 s, mono, 16 kHz. Texto descartado, stream liberado pelo engine.
            null
        } catch (e: OutOfMemoryError) {
            e
        } catch (e: Exception) {
            e
        }
        // Uma falha descartável não invalida a carga nem provoca novas tentativas a cada seleção.
        onWarmed(model, nowMs() - started, failure)
    }

    private fun submit(reply: (Result<String>) -> Unit, work: () -> String) {
        executor.execute {
            val outcome = try {
                Result.success(work())
            } catch (e: OutOfMemoryError) {
                engine.unload()
                Result.failure(EngineFailure(EngineErrorKind.OOM, "memória esgotada durante a transcrição", e))
            } catch (e: Exception) {
                Result.failure(e)
            }
            reply(outcome)
        }
    }
}
