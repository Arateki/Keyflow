package com.keyflow.reasoning.local

import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.Executor

/**
 * O núcleo do processo `:reasoning`, sem Android (RRUN-02/03/04/06): uma geração por vez, o modelo carrega sob demanda,
 * trocar de modelo descarrega o anterior, a guarda de RAM barra o que não cabe e um modelo ocioso é descarregado.
 * Backend, memória, relógio de ociosidade e executor entram por parâmetro — é isso que o torna testável na JVM.
 *
 * O estado (`backend`, `loadedId`, `busy`…) é tocado só na thread do [executor] (que precisa ser de uma thread só);
 * [cancel] é a única entrada de outra thread.
 */
internal class ReasoningServiceCore(
    private val backendFor: (LlmModel) -> LlmBackend,
    private val fileExists: (String) -> Boolean,
    private val availableMb: () -> Long?,
    private val scheduler: IdleScheduler,
    private val executor: Executor,
    private val onWarmed: (LlmModel, Long, Throwable?) -> Unit = { _, _, _ -> },
) {
    /** Quem recebe a resposta. Depois de um [cancel], nada mais é entregue à geração cancelada. */
    interface Listener {
        fun onDelta(text: String)
        fun onDone()
        fun onError(kind: ReasoningErrorKind, message: String)
    }

    private var backend: LlmBackend? = null
    private var loadedId: String? = null
    private var busy = false
    private var idleTimer: Cancellable? = null

    /** Sobe a cada uso; um timer de ociosidade só vale se ainda tem o número da última vez que o modelo foi usado. */
    private var useToken = 0L

    @Volatile private var cancelled = false
    @Volatile private var running: LlmBackend? = null

    fun generate(model: LlmModel, request: ReasoningRequest, listener: Listener) {
        executor.execute { run(model, request, listener) }
    }

    /** Seleção explícita: carrega e aquece uma vez, antes do primeiro prompt. Mesma fila das gerações. */
    fun preload(model: LlmModel, listener: Listener) {
        executor.execute {
            busy = true
            idleTimer?.cancel()
            idleTimer = null
            useToken++
            try {
                val fresh = backend == null || loadedId != model.modelId
                val active = ensureLoaded(model)
                if (fresh) {
                    val started = System.nanoTime()
                    val failure = try {
                        active.warmUp()
                        null
                    } catch (e: OutOfMemoryError) {
                        e
                    } catch (e: Exception) {
                        e
                    }
                    onWarmed(model, (System.nanoTime() - started) / 1_000_000L, failure)
                }
                listener.onDone()
            } catch (e: ReasoningFailure) {
                listener.onError(e.kind, e.message.orEmpty())
            } catch (e: OutOfMemoryError) {
                unloadCurrent()
                listener.onError(ReasoningErrorKind.OOM, "memória esgotada durante a pré-carga")
            } catch (e: Exception) {
                listener.onError(ReasoningErrorKind.ENGINE, e.message ?: e.toString())
            } finally {
                busy = false
                scheduleIdleUnload(model)
            }
        }
    }

    /** Interrompe a geração em curso (RRUN-06). Sem geração em curso, não faz nada além de silenciar a próxima já enfileirada. */
    fun cancel() {
        cancelled = true
        running?.cancel()
    }

    /** Descarrega o modelo na fila do executor: nunca solta o modelo no meio de uma geração. */
    fun shutdown() {
        executor.execute {
            idleTimer?.cancel()
            idleTimer = null
            unloadCurrent()
        }
    }

    private fun run(model: LlmModel, request: ReasoningRequest, listener: Listener) {
        cancelled = false
        busy = true
        idleTimer?.cancel()
        idleTimer = null
        useToken++
        try {
            val active = ensureLoaded(model)
            if (cancelled) return
            running = active
            active.generate(request) { text -> if (!cancelled) listener.onDelta(text) }
            if (!cancelled) listener.onDone()
        } catch (e: ReasoningFailure) {
            if (!cancelled) listener.onError(e.kind, e.message.orEmpty())
        } catch (e: OutOfMemoryError) {
            unloadCurrent()
            if (!cancelled) listener.onError(ReasoningErrorKind.OOM, "memória esgotada durante a geração")
        } catch (e: Exception) {
            if (!cancelled) listener.onError(ReasoningErrorKind.ENGINE, e.message ?: e.toString())
        } finally {
            running = null
            busy = false
            scheduleIdleUnload(model)
        }
    }

    private fun ensureLoaded(model: LlmModel): LlmBackend {
        backend?.let { if (loadedId == model.modelId) return it }
        // Libera o anterior antes de medir e carregar: a troca não precisa caber junto com ele.
        unloadCurrent()
        val candidate = backendFor(model)
        if (candidate.requiresModelFile && !fileExists(model.modelPath)) {
            throw ReasoningFailure(ReasoningErrorKind.MODEL_MISSING, "falta o arquivo do modelo ${model.modelId}")
        }
        val available = availableMb()
        if (!RamGuard.enough(available, model.ramEstimateMb)) {
            throw ReasoningFailure(
                ReasoningErrorKind.OOM,
                "memória insuficiente para ${model.modelId}: $available MB livres, precisa de ${model.ramEstimateMb} MB",
            )
        }
        try {
            candidate.load(model)
        } catch (e: OutOfMemoryError) {
            runCatching { candidate.unload() }
            throw ReasoningFailure(ReasoningErrorKind.OOM, "memória esgotada ao carregar ${model.modelId}", e)
        } catch (e: ReasoningFailure) {
            runCatching { candidate.unload() }
            throw e
        } catch (e: Exception) {
            runCatching { candidate.unload() }
            throw ReasoningFailure(ReasoningErrorKind.ENGINE, "o motor recusou ${model.modelId}: ${e.message}", e)
        }
        backend = candidate
        loadedId = model.modelId
        return candidate
    }

    private fun unloadCurrent() {
        backend?.let { runCatching { it.unload() } }
        backend = null
        loadedId = null
    }

    private fun scheduleIdleUnload(model: LlmModel) {
        if (model.idleUnloadMs <= 0 || backend == null) return
        val token = useToken
        idleTimer = scheduler.schedule(model.idleUnloadMs) { executor.execute { onIdle(token) } }
    }

    private fun onIdle(token: Long) {
        // Timer velho (o modelo foi usado de novo) ou geração em curso: não descarrega.
        if (token != useToken || busy) return
        unloadCurrent()
    }
}
