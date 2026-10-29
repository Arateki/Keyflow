package com.keyflow.reasoning.local

import com.keyflow.core.ComputeBackend
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.Executor

/**
 * O núcleo do processo `:reasoning`, sem Android (RRUN-02/03/04/06): uma geração por vez, o modelo carrega sob demanda,
 * trocar de modelo descarrega o anterior, a guarda de RAM barra o que não cabe e um modelo ocioso é descarregado.
 * Backend, memória, relógio de ociosidade e executor entram por parâmetro — é isso que o torna testável na JVM.
 *
 * **Onde o modelo roda** ([LlmModel.backend]) é parte da identidade do que está carregado: pedir GPU com o modelo carregado na
 * CPU (ou o contrário) descarrega e recarrega. Se a carga na GPU falhar, o núcleo recarrega o mesmo modelo na CPU sem perder o pedido
 * em curso, avisa por [Listener.onBackendFallback] e lembra a falha (modelo, versão, backend) enquanto o processo vive, para os pedidos
 * seguintes que ainda trazem a escolha antiga não repetirem uma carga de minutos que já falhou; só a seleção explícita ([preload])
 * tenta a GPU de novo.
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
    private val totalMb: () -> Long? = { null },
    private val ramProbe: (() -> RamProbe)? = null,
) {
    /** Quem recebe a resposta. Depois de um [cancel], nada mais é entregue à geração cancelada. */
    interface Listener {
        fun onDelta(text: String)
        fun onMetrics(metrics: com.keyflow.reasoning.ReasoningEvent.Metrics) = Unit
        fun onDone()
        fun onError(kind: ReasoningErrorKind, message: String)

        /** A carga em [failed] falhou ([reason] é para o log) e o modelo seguiu na CPU; vem antes dos trechos da mesma geração. */
        fun onBackendFallback(failed: ComputeBackend, reason: String) = Unit
    }

    private var backend: LlmBackend? = null
    private var loadedId: String? = null
    private var loadedVersion: String = ""

    /** Onde o que está carregado de fato roda — pode ser a CPU mesmo com a GPU pedida (depois de uma volta). */
    private var loadedBackend: ComputeBackend = ComputeBackend.CPU

    /** Cargas que falharam num backend que não é a CPU: `modelo|versão|backend`. Só a seleção explícita limpa a entrada. */
    private val failedLoads = mutableSetOf<String>()
    private var busy = false
    private var idleTimer: Cancellable? = null

    /** Sobe a cada uso; um timer de ociosidade só vale se ainda tem o número da última vez que o modelo foi usado. */
    private var useToken = 0L

    @Volatile private var cancelled = false
    @Volatile private var running: LlmBackend? = null

    fun generate(model: LlmModel, request: ReasoningRequest, listener: Listener) {
        executor.execute { run(model, request, listener) }
    }

    /** Seleção explícita: carrega e aquece uma vez, antes do primeiro prompt. Mesma fila e guarda de RAM das gerações. */
    fun preload(model: LlmModel, listener: Listener) {
        executor.execute {
            busy = true
            idleTimer?.cancel()
            idleTimer = null
            useToken++
            try {
                // Seleção explícita (o usuário escolheu o modelo ou o backend agora): tenta de novo o que tinha falhado.
                failedLoads.remove(failureKey(model))
                val fresh = !isLoaded(model)
                val active = ensureLoaded(model, listener)
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

    fun unload() = shutdown()

    private fun run(model: LlmModel, request: ReasoningRequest, listener: Listener) {
        cancelled = false
        busy = true
        idleTimer?.cancel()
        idleTimer = null
        useToken++
        val probe = ramProbe?.invoke()?.also { it.start() }
        var peak: Int? = null
        try {
            val active = ensureLoaded(model, listener)
            if (cancelled) return
            running = active
            active.generate(request) { text -> if (!cancelled) listener.onDelta(text) }
            if (!cancelled) {
                peak = probe?.stop()
                val metrics = active.metrics() ?: if (!request.options.isDefault) com.keyflow.reasoning.ReasoningEvent.Metrics(ignored = request.options.requestedNames().toSet()) else null
                (metrics ?: peak?.let { com.keyflow.reasoning.ReasoningEvent.Metrics() })?.copy(peakMemoryMb = peak)?.let(listener::onMetrics)
                listener.onDone()
            }
        } catch (e: ReasoningFailure) {
            if (!cancelled) listener.onError(e.kind, e.message.orEmpty())
        } catch (e: OutOfMemoryError) {
            unloadCurrent()
            if (!cancelled) listener.onError(ReasoningErrorKind.OOM, "memória esgotada durante a geração")
        } catch (e: Exception) {
            if (!cancelled) listener.onError(ReasoningErrorKind.ENGINE, e.message ?: e.toString())
        } finally {
            if (peak == null) probe?.stop()
            running = null
            busy = false
            scheduleIdleUnload(model)
        }
    }

    private fun ensureLoaded(model: LlmModel, listener: Listener): LlmBackend {
        // Um pedido automático que ainda traz o backend que já falhou neste processo vai direto para a CPU (e quem pediu fica sabendo,
        // para a preferência gravada acompanhar a CPU e a geração não contar como "GPU pronta").
        val remembered = failureKey(model) in failedLoads
        val wanted = if (remembered) model.copy(backend = ComputeBackend.CPU) else model
        if (remembered) listener.onBackendFallback(model.backend, "a carga já falhou neste processo")
        backend?.let { if (isLoaded(wanted)) return it }
        // Libera o anterior antes de medir e carregar: a troca não precisa caber junto com ele.
        unloadCurrent()
        var target = wanted
        var candidate = open(target)
        try {
            load(candidate, target)
        } catch (failure: ReasoningFailure) {
            if (target.backend == ComputeBackend.CPU) throw failure
            // Falha na GPU: o pedido não se perde — o mesmo modelo recarrega na CPU, sob a guarda de RAM da CPU.
            failedLoads += failureKey(model)
            listener.onBackendFallback(target.backend, failure.message.orEmpty())
            target = target.copy(backend = ComputeBackend.CPU)
            candidate = open(target)
            load(candidate, target)
        }
        backend = candidate
        loadedId = target.modelId
        loadedVersion = target.version
        loadedBackend = target.backend
        return candidate
    }

    /** Cria o backend do [model] e confere o que o impede de carregar: o arquivo e a RAM do backend pedido. Nada é carregado aqui. */
    private fun open(model: LlmModel): LlmBackend {
        val candidate = backendFor(model)
        if (candidate.requiresModelFile && !fileExists(model.modelPath)) {
            throw ReasoningFailure(ReasoningErrorKind.MODEL_MISSING, "falta o arquivo do modelo ${model.modelId}")
        }
        val available = availableMb()
        val total = totalMb()
        if (!RamGuard.enoughFor(model.backend, available, model.ramEstimateMb, total)) {
            throw ReasoningFailure(
                ReasoningErrorKind.OOM,
                "memória insuficiente para ${model.modelId}: $total MB de RAM total e $available MB livres, estimativa de ${model.ramEstimateMb} MB",
            )
        }
        return candidate
    }

    /** Carrega; qualquer falha vira [ReasoningFailure] e deixa o [candidate] descarregado. */
    private fun load(candidate: LlmBackend, model: LlmModel) {
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
    }

    private fun failureKey(model: LlmModel) = "${model.modelId}|${model.version}|${model.backend.name}"

    private fun unloadCurrent() {
        backend?.let { runCatching { it.unload() } }
        backend = null
        loadedId = null
        loadedVersion = ""
        loadedBackend = ComputeBackend.CPU
    }

    /** O que está carregado é este modelo, desta versão do arquivo (mesmo id com outro SHA não serve), neste backend. */
    private fun isLoaded(model: LlmModel): Boolean =
        backend != null && loadedId == model.modelId && loadedVersion == model.version && loadedBackend == model.backend

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
