package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import com.keyflow.data.LocalBackendPrefs
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test

/**
 * As corridas da revisão do PR #49, com o núcleo do `:reasoning` de verdade (executor de uma thread, carga que demora) e a tabela de preferências
 * de verdade, sem Binder nem DataStore: uma carga na GPU leva minutos, e nesse tempo o usuário escolhe de novo ou apaga o modelo. O que a carga
 * antiga conta, quando acaba, não pode desfazer a escolha nova (CPU gravada por cima da GPU) nem recriar a linha de um modelo apagado.
 */
class BackendReportsRaceTest {

    private val pool = Executors.newSingleThreadExecutor()

    @After fun tearDown() { pool.shutdownNow() }

    private val qwen = "qwen3-0.6b-litertlm"

    /** A tabela do DataStore em memória: a mesma gravação atômica (uma mudança por vez sobre o valor atual). */
    private class MemoryPrefs {
        @Volatile var prefs = LocalBackendPrefs.EMPTY
        @Synchronized fun update(change: (LocalBackendPrefs) -> LocalBackendPrefs) { prefs = change(prefs) }
    }

    /** Um motor cuja carga espera [gate] e então falha ([failure]) ou passa. */
    private class SlowBackend(private val gate: CountDownLatch?, private val failure: Throwable?) : LlmBackend {
        val started = CountDownLatch(1)
        override fun load(model: LlmModel) {
            started.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            failure?.let { throw it }
        }
        override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) = Unit
        override fun cancel() = Unit
        override fun unload() = Unit
    }

    private class Harness(val pool: java.util.concurrent.Executor, gpuBackends: List<LlmBackend>, installed: MutableMap<String, String?>) {
        val store = MemoryPrefs()
        val reports = PersistedBackendReports(
            update = { change -> store.update(change) },
            installedVersion = { installed[it] },
            // Inline: o aviso é aplicado na thread que o entrega, o que deixa o teste determinístico.
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        private val gpus = ArrayDeque(gpuBackends)
        private val cpu = SlowBackend(null, null)
        val core = ReasoningServiceCore(
            backendFor = { model -> if (model.backend == ComputeBackend.GPU) gpus.removeFirst() else cpu },
            fileExists = { true },
            availableMb = { null },
            totalMb = { null },
            scheduler = object : IdleScheduler {
                override fun schedule(delayMs: Long, block: () -> Unit) = Cancellable { }
            },
            executor = pool,
        )

        /** O que o app faz com uma pré-carga: leva a escolha atual (backend + ficha) e repassa o que o `:reasoning` contar. */
        fun preload(version: String = "v1"): CountDownLatch {
            val choice = store.prefs
            val model = LlmModel(
                modelId = "qwen3-0.6b-litertlm", modelPath = "/models/qwen.litertlm", engineType = "LITERT_LM", idleUnloadMs = 0, ramEstimateMb = 0,
                version = version, backend = choice.backendFor("qwen3-0.6b-litertlm"), ticket = choice.ticketFor("qwen3-0.6b-litertlm"),
            )
            val report = LoadReport(model, reports)
            val done = CountDownLatch(1)
            core.preload(
                model,
                object : ReasoningServiceCore.Listener {
                    override fun onDelta(text: String) = Unit
                    override fun onDone() { report.onDone(); done.countDown() }
                    override fun onError(kind: ReasoningErrorKind, message: String) { done.countDown() }
                    override fun onBackendFallback(failed: ComputeBackend, reason: String) = report.onBackendFallback(failed.name, reason)
                },
            )
            return done
        }
    }

    private fun gate() = CountDownLatch(1)

    /**
     * Sonda 1 da revisão: carga GPU bloqueada → CPU e GPU de novo (e a nova carga na fila) → a carga antiga falha → a nova passa. Esperado: GPU
     * escolhida, sem aviso de volta, e a GPU pronta da carga nova.
     */
    @Test
    fun `an old fallback does not undo a newer explicit choice and the retry marks the GPU ready`() {
        val gate = gate()
        val old = SlowBackend(gate, IllegalStateException("OpenCL indisponível"))
        val retry = SlowBackend(null, null)
        val h = Harness(pool, listOf(old, retry), mutableMapOf(qwen to "v1"))
        h.store.update { it.choose(qwen, ComputeBackend.GPU, 1L) }
        val first = h.preload()
        assertThat(old.started.await(5, TimeUnit.SECONDS)).isTrue()

        h.store.update { it.choose(qwen, ComputeBackend.CPU, 2L) }
        h.store.update { it.choose(qwen, ComputeBackend.GPU, 3L) }
        val second = h.preload()
        gate.countDown()
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue()

        val prefs = h.store.prefs
        assertThat(prefs.backendFor(qwen)).isEqualTo(ComputeBackend.GPU)
        assertThat(prefs.fellBackFrom(qwen)).isNull()
        assertThat(prefs.gpuReady(qwen)).isTrue()
        assertThat(prefs.ticketFor(qwen)).isEqualTo(3L)
    }

    /** Sonda 2: apagar durante a carga e a carga antiga falha → o aviso não recria a linha `CPU/GPU/0` do modelo apagado. */
    @Test
    fun `a fallback that arrives after the model was deleted does not recreate its row`() {
        val gate = gate()
        val old = SlowBackend(gate, IllegalStateException("OpenCL indisponível"))
        val h = Harness(pool, listOf(old), mutableMapOf(qwen to "v1"))
        h.store.update { it.choose(qwen, ComputeBackend.GPU, 1L) }
        val first = h.preload()
        assertThat(old.started.await(5, TimeUnit.SECONDS)).isTrue()

        h.store.update { it.forget(qwen) }
        gate.countDown()
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(h.store.prefs).isEqualTo(LocalBackendPrefs.EMPTY)
        assertThat(h.store.prefs.encode()).isEmpty()
    }

    /** Sonda 3: apagar durante a carga e a carga antiga passa → o "GPU pronta" não recria a linha `CPU//1`. */
    @Test
    fun `a GPU ready that arrives after the model was deleted does not recreate its row`() {
        val gate = gate()
        val old = SlowBackend(gate, null)
        val h = Harness(pool, listOf(old), mutableMapOf(qwen to "v1"))
        h.store.update { it.choose(qwen, ComputeBackend.GPU, 1L) }
        val first = h.preload()
        assertThat(old.started.await(5, TimeUnit.SECONDS)).isTrue()

        h.store.update { it.forget(qwen) }
        gate.countDown()
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue()

        assertThat(h.store.prefs).isEqualTo(LocalBackendPrefs.EMPTY)
    }

    /** O mesmo com a ficha ainda valendo: o arquivo instalado já não existe (apagado, ainda sem limpar a tabela) ou é outro (atualizado). */
    @Test
    fun `a report of a file that is no longer the installed one is discarded even with a matching ticket`() {
        listOf<String?>(null, "v2").forEach { installedNow ->
            val gate = gate()
            val h = Harness(pool, listOf(SlowBackend(gate, IllegalStateException("falhou"))), mutableMapOf(qwen to installedNow))
            h.store.update { it.choose(qwen, ComputeBackend.GPU, 1L) }
            val first = h.preload(version = "v1")
            gate.countDown()
            assertThat(first.await(5, TimeUnit.SECONDS)).isTrue()

            assertThat(h.store.prefs.backendFor(qwen)).isEqualTo(ComputeBackend.GPU)
            assertThat(h.store.prefs.fellBackFrom(qwen)).isNull()
        }
    }

    /** Sem corrida nenhuma o caminho de sempre continua valendo: a GPU que falha vira CPU com o aviso, a que passa fica pronta. */
    @Test
    fun `without a newer choice the fallback and the ready still apply`() {
        val failing = Harness(pool, listOf(SlowBackend(null, IllegalStateException("falhou"))), mutableMapOf(qwen to "v1"))
        failing.store.update { it.choose(qwen, ComputeBackend.GPU, 1L) }
        assertThat(failing.preload().await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(failing.store.prefs.backendFor(qwen)).isEqualTo(ComputeBackend.CPU)
        assertThat(failing.store.prefs.fellBackFrom(qwen)).isEqualTo(ComputeBackend.GPU)
        assertThat(failing.store.prefs.gpuReady(qwen)).isFalse()

        val working = Harness(pool, listOf(SlowBackend(null, null)), mutableMapOf(qwen to "v1"))
        working.store.update { it.choose(qwen, ComputeBackend.GPU, 1L) }
        assertThat(working.preload().await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(working.store.prefs.backendFor(qwen)).isEqualTo(ComputeBackend.GPU)
        assertThat(working.store.prefs.gpuReady(qwen)).isTrue()
    }

    /** A CPU não conta "GPU pronta" nem cria linha: o padrão não ocupa a tabela. */
    @Test
    fun `a load on the CPU reports nothing to the table`() {
        val h = Harness(pool, emptyList(), mutableMapOf(qwen to "v1"))
        assertThat(h.preload().await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(h.store.prefs).isEqualTo(LocalBackendPrefs.EMPTY)
    }
}
