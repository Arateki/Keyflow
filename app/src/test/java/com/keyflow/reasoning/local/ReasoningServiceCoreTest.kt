package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import com.keyflow.reasoning.ReasoningRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Test

/**
 * RRUN-02/03/04/06: o núcleo do processo `:reasoning`, sem Android — backend, relógio de ociosidade e
 * memória entram por parâmetro. Concorrência de verdade (executor único) onde o comportamento depende dela.
 */
class ReasoningServiceCoreTest {

    private val log = CopyOnWriteArrayList<String>()
    private val pool = Executors.newSingleThreadExecutor()
    private val direct = Executor { it.run() }

    @After fun tearDown() { pool.shutdownNow() }

    private class Recorder : ReasoningServiceCore.Listener {
        val events = CopyOnWriteArrayList<String>()
        private val finished = CountDownLatch(1)
        override fun onDelta(text: String) { events += "delta:$text" }
        override fun onDone() { events += "done"; finished.countDown() }
        override fun onError(kind: ReasoningErrorKind, message: String) { events += "error:$kind"; finished.countDown() }
        override fun onBackendFallback(failed: ComputeBackend, reason: String) { events += "fallback:$failed" }
        fun awaitFinish() = assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
    }

    private class FakeBackend(private val log: MutableList<String>, val id: String, override val requiresModelFile: Boolean = true) : LlmBackend {
        var loadThrows: Throwable? = null
        var generateThrows: Throwable? = null
        var warmThrows: Throwable? = null
        var warmHook: () -> Unit = {}
        val hooks = ArrayDeque<() -> Unit>()
        @Volatile var cancelCalled = false
        override fun load(model: LlmModel) { log += "load:$id"; loadThrows?.let { throw it } }
        override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) {
            log += "generate:$id"
            hooks.removeFirstOrNull()?.invoke()
            generateThrows?.let { throw it }
            request.user.split(" ").forEach(onDelta)
        }
        override fun warmUp() { log += "warm:$id"; warmHook(); warmThrows?.let { throw it } }
        override fun cancel() { cancelCalled = true; log += "cancel:$id" }
        override fun unload() { log += "unload:$id" }
    }

    /** Guarda TODOS os blocos agendados — inclusive os já cancelados — para simular um timer que disparou em corrida. */
    private class FakeScheduler : IdleScheduler {
        val everScheduled = mutableListOf<Pair<Long, () -> Unit>>()
        var cancelledCount = 0
        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            everScheduled += delayMs to block
            return Cancellable { cancelledCount++ }
        }
        fun fireAll() = everScheduled.toList().forEach { it.second() }
        fun fireLast() = everScheduled.last().second()
    }

    private fun model(id: String, idleMs: Long = 120_000, ramMb: Int = 100) =
        LlmModel(id, "/models/$id/$id.litertlm", "LITERT_LM", idleMs, ramMb)

    private val request = ReasoningRequest(user = "um dois")

    private fun core(
        backends: Map<String, FakeBackend>,
        exists: Boolean = true,
        availMb: Long? = null,
        totalMb: Long? = null,
        sched: IdleScheduler = FakeScheduler(),
        executor: Executor = direct,
    ) = ReasoningServiceCore(
        backendFor = { backends.getValue(it.modelId) },
        fileExists = { exists },
        availableMb = { availMb },
        totalMb = { totalMb },
        scheduler = sched,
        executor = executor,
    )

    private fun run(core: ReasoningServiceCore, model: LlmModel): Recorder =
        Recorder().also { core.generate(model, request, it); it.awaitFinish() }

    @Test
    fun `streams the deltas in order and then done`() {
        val a = FakeBackend(log, "a")
        val rec = run(core(mapOf("a" to a)), model("a"))
        assertThat(rec.events).containsExactly("delta:um", "delta:dois", "done").inOrder()
    }

    @Test
    fun `loads once for consecutive generations of the same model`() {
        val a = FakeBackend(log, "a")
        val c = core(mapOf("a" to a))
        run(c, model("a")); run(c, model("a")); run(c, model("a"))
        assertThat(log.count { it == "load:a" }).isEqualTo(1)
        assertThat(log.count { it == "generate:a" }).isEqualTo(3)
    }

    /** Atualização do catálogo com o processo vivo: o mesmo id com outro SHA é outro arquivo, o motor residente não serve. */
    @Test
    fun `the same model id with another file version reloads the resident engine`() {
        val first = FakeBackend(log, "a1")
        val second = FakeBackend(log, "a2")
        val queue = ArrayDeque(listOf(first, second))
        val c = ReasoningServiceCore(
            backendFor = { queue.removeFirst() }, fileExists = { true }, availableMb = { null }, totalMb = { null },
            scheduler = FakeScheduler(), executor = direct,
        )
        run(c, model("a").copy(version = "sha-antigo"))
        run(c, model("a").copy(version = "sha-antigo"))
        assertThat(log.count { it == "load:a1" }).isEqualTo(1)

        val rec = run(c, model("a").copy(version = "sha-novo"))

        assertThat(rec.events).contains("done")
        assertThat(log).containsAtLeast("unload:a1", "load:a2", "generate:a2").inOrder()
    }

    @Test
    fun `switching model unloads the previous one before loading the next`() {
        val backends = mapOf("a" to FakeBackend(log, "a"), "b" to FakeBackend(log, "b"))
        val c = core(backends)
        run(c, model("a")); run(c, model("b"))
        assertThat(log).containsAtLeast("load:a", "unload:a", "load:b").inOrder()
        assertThat(log.indexOf("unload:a")).isLessThan(log.indexOf("load:b"))
    }

    @Test
    fun `a missing model file reports MODEL_MISSING without loading`() {
        val a = FakeBackend(log, "a")
        val rec = run(core(mapOf("a" to a), exists = false), model("a"))
        assertThat(rec.events).containsExactly("error:MODEL_MISSING")
        assertThat(log).doesNotContain("load:a")
    }

    /** O motor de eco (debug) não tem arquivo de modelo: `requiresModelFile = false` pula a checagem. */
    @Test
    fun `a backend that needs no file is not blocked by a missing path`() {
        val echo = FakeBackend(log, "echo", requiresModelFile = false)
        val rec = run(core(mapOf("echo" to echo), exists = false), model("echo"))
        assertThat(rec.events).contains("done")
    }

    @Test
    fun `insufficient RAM reports OOM without loading`() {
        val a = FakeBackend(log, "a")
        val rec = run(core(mapOf("a" to a), availMb = 50), model("a", ramMb = 2_048))
        assertThat(rec.events).containsExactly("error:OOM")
        assertThat(log).doesNotContain("load:a")
    }

    /** Com a RAM total conhecida ela decide: pouca RAM livre não barra o que cabe no aparelho (o Android libera apps ao carregar). */
    @Test
    fun `with the total known a low free memory does not block a model that fits the device`() {
        val a = FakeBackend(log, "a")
        val rec = run(core(mapOf("a" to a), availMb = 50, totalMb = 4_000), model("a", ramMb = 2_048))
        assertThat(rec.events).containsExactly("delta:um", "delta:dois", "done").inOrder()
    }

    @Test
    fun `with the total known a model that does not fit the device reports OOM without loading`() {
        val a = FakeBackend(log, "a")
        val rec = run(core(mapOf("a" to a), availMb = 6_000, totalMb = 3_000), model("a", ramMb = 2_048))
        assertThat(rec.events).containsExactly("error:OOM")
        assertThat(log).doesNotContain("load:a")
    }

    @Test
    fun `an OutOfMemoryError while loading reports OOM and unloads`() {
        val a = FakeBackend(log, "a").also { it.loadThrows = OutOfMemoryError("test") }
        val rec = run(core(mapOf("a" to a)), model("a"))
        assertThat(rec.events).containsExactly("error:OOM")
        assertThat(log).contains("unload:a")
    }

    @Test
    fun `an OutOfMemoryError while generating reports OOM keeping the partial text, and the next run reloads`() {
        val a = FakeBackend(log, "a")
        val c = core(mapOf("a" to a))
        a.hooks += { throw OutOfMemoryError("test") }
        val first = run(c, model("a"))
        assertThat(first.events).containsExactly("error:OOM")
        assertThat(log).contains("unload:a")
        val second = run(c, model("a"))
        assertThat(second.events).contains("done")
        assertThat(log.count { it == "load:a" }).isEqualTo(2)
    }

    @Test
    fun `a backend exception is an ENGINE error`() {
        val a = FakeBackend(log, "a").also { it.generateThrows = IllegalStateException("boom") }
        val rec = run(core(mapOf("a" to a)), model("a"))
        assertThat(rec.events).containsExactly("error:ENGINE")
    }

    @Test
    fun `cancel reaches the backend, silences the cancelled run and the core accepts the next generation`() {
        val a = FakeBackend(log, "a")
        val c = core(mapOf("a" to a), executor = pool)
        val started = CountDownLatch(1)
        a.hooks += { started.countDown(); while (!a.cancelCalled) Thread.sleep(5) }

        val first = Recorder()
        c.generate(model("a"), request, first)
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        c.cancel()

        val second = Recorder()
        c.generate(model("a"), request, second)
        second.awaitFinish()

        assertThat(a.cancelCalled).isTrue()
        assertThat(first.events).doesNotContain("done")            // a corrida cancelada não fala mais
        assertThat(first.events.filter { it.startsWith("error") }).isEmpty()
        assertThat(second.events).containsExactly("delta:um", "delta:dois", "done").inOrder()
    }

    @Test
    fun `a second generation while busy is queued and never interleaved with the first`() {
        val a = FakeBackend(log, "a")
        val c = core(mapOf("a" to a), executor = pool)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        a.hooks += { started.countDown(); release.await(5, TimeUnit.SECONDS) }

        val first = Recorder(); val second = Recorder()
        c.generate(model("a"), request, first)
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
        c.generate(model("a"), request, second)
        Thread.sleep(100)
        assertThat(second.events).isEmpty()                          // ainda na fila
        release.countDown()
        first.awaitFinish(); second.awaitFinish()
        assertThat(first.events.last()).isEqualTo("done")
        assertThat(second.events.last()).isEqualTo("done")
    }

    // ── descarga por ociosidade (R8) ────────────────────────────────────────────────────────

    @Test
    fun `idle unload happens after the idle time`() {
        val a = FakeBackend(log, "a"); val sched = FakeScheduler()
        val c = core(mapOf("a" to a), sched = sched)
        run(c, model("a", idleMs = 90_000))
        assertThat(sched.everScheduled.map { it.first }).containsExactly(90_000L)
        assertThat(log).doesNotContain("unload:a")
        sched.fireLast()
        assertThat(log).contains("unload:a")
    }

    @Test
    fun `using the model again resets the idle timer`() {
        val a = FakeBackend(log, "a"); val sched = FakeScheduler()
        val c = core(mapOf("a" to a), sched = sched)
        run(c, model("a")); run(c, model("a"))
        assertThat(sched.everScheduled).hasSize(2)
        assertThat(sched.cancelledCount).isAtLeast(1)                // o 1º timer foi cancelado
        sched.everScheduled.first().second()                         // …e mesmo que ele dispare em corrida:
        assertThat(log).doesNotContain("unload:a")                   // o token velho não descarrega um modelo em uso
    }

    @Test
    fun `a stale idle timer never unloads a model that is generating`() {
        val a = FakeBackend(log, "a"); val sched = FakeScheduler()
        val c = core(mapOf("a" to a), sched = sched)
        run(c, model("a"))
        a.hooks += { sched.fireAll() }                               // dispara o timer da 1ª corrida no meio da 2ª
        run(c, model("a"))
        assertThat(log.indexOf("unload:a")).isEqualTo(-1)
    }

    @Test
    fun `idleUnloadMs zero keeps the model loaded and schedules nothing`() {
        val a = FakeBackend(log, "a"); val sched = FakeScheduler()
        run(core(mapOf("a" to a), sched = sched), model("a", idleMs = 0))
        assertThat(sched.everScheduled).isEmpty()
    }

    @Test
    fun `shutdown unloads the loaded model`() {
        val a = FakeBackend(log, "a")
        val c = core(mapOf("a" to a))
        run(c, model("a"))
        c.shutdown()
        assertThat(log).contains("unload:a")
    }

    @Test
    fun `pre carga aquece uma vez sem entregar deltas e respeita troca de modelo`() {
        val a = FakeBackend(log, "a")
        val b = FakeBackend(log, "b")
        val c = core(mapOf("a" to a, "b" to b))
        repeat(2) {
            val rec = Recorder()
            c.preload(model("a"), rec)
            rec.awaitFinish()
            assertThat(rec.events).containsExactly("done")
        }
        val other = Recorder()
        c.preload(model("b"), other)
        other.awaitFinish()
        assertThat(log).containsExactly("load:a", "warm:a", "unload:a", "load:b", "warm:b").inOrder()
    }

    @Test
    fun `falha no aquecimento de texto inclusive OOM nao impede geracao nem repete tentativa`() {
        listOf<Throwable>(IllegalStateException("falso"), OutOfMemoryError("falso")).forEach { failure ->
            log.clear()
            val a = FakeBackend(log, "a").apply { warmThrows = failure }
            val c = core(mapOf("a" to a))
            repeat(2) {
                val rec = Recorder()
                c.preload(model("a"), rec)
                rec.awaitFinish()
                assertThat(rec.events).containsExactly("done")
            }
            assertThat(run(c, model("a")).events).containsExactly("delta:um", "delta:dois", "done").inOrder()
            assertThat(log).containsExactly("load:a", "warm:a", "generate:a").inOrder()
        }
    }

    @Test
    fun `geracao solicitada durante aquecimento de texto aguarda a mesma fila`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val a = FakeBackend(log, "a").apply {
            warmHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val c = core(mapOf("a" to a), executor = pool)
        try {
            val preload = Recorder()
            c.preload(model("a"), preload)
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            val real = Recorder()
            c.generate(model("a"), request, real)
            assertThat(real.events).isEmpty()
            release.countDown()
            preload.awaitFinish()
            real.awaitFinish()
            assertThat(real.events).containsExactly("delta:um", "delta:dois", "done").inOrder()
            assertThat(log).containsExactly("load:a", "warm:a", "generate:a").inOrder()
        } finally { release.countDown() }
    }

    @Test
    fun `texto carregado pelo pedido real nao paga aquecimento extra na pre carga seguinte`() {
        val a = FakeBackend(log, "a")
        val c = core(mapOf("a" to a))
        run(c, model("a"))
        val rec = Recorder()
        c.preload(model("a"), rec)
        rec.awaitFinish()
        assertThat(log).containsExactly("load:a", "generate:a").inOrder()
    }

    @Test
    fun `texto descarregado por ociosidade aquece novamente quando selecionado`() {
        val a = FakeBackend(log, "a")
        val scheduler = FakeScheduler()
        val c = core(mapOf("a" to a), sched = scheduler)
        repeat(2) {
            val rec = Recorder()
            c.preload(model("a"), rec)
            rec.awaitFinish()
            scheduler.fireLast()
        }
        assertThat(log).containsExactly("load:a", "warm:a", "unload:a", "load:a", "warm:a", "unload:a").inOrder()
    }

    @Test
    fun `pre carga com pouca RAM livre nao carrega nem aquece e o primeiro uso carrega normalmente`() {
        val a = FakeBackend(log, "a")
        // 1.500 MB livres: cabe na estimativa do catálogo (100), mas não no piso da pré-carga.
        val c = core(mapOf("a" to a), availMb = 1_500)
        val rec = Recorder()
        c.preload(model("a"), rec)
        rec.awaitFinish()
        assertThat(rec.events).containsExactly("error:OOM")
        assertThat(log).isEmpty()
        assertThat(run(c, model("a")).events).containsExactly("delta:um", "delta:dois", "done").inOrder()
        assertThat(log).containsExactly("load:a", "generate:a").inOrder()
    }

    @Test
    fun `pre carga com RAM folgada carrega e aquece`() {
        val a = FakeBackend(log, "a")
        val c = core(mapOf("a" to a), availMb = 3_000)
        val rec = Recorder()
        c.preload(model("a"), rec)
        rec.awaitFinish()
        assertThat(rec.events).containsExactly("done")
        assertThat(log).containsExactly("load:a", "warm:a").inOrder()
    }

    // ── onde o modelo roda (CPU/GPU): identidade do motor, volta para a CPU e guarda de RAM ─────────────────────────────

    private fun gpu(m: LlmModel) = m.copy(backend = ComputeBackend.GPU)

    /** Um backend por (modelo, backend): o log mostra qual deles carregou, descarregou e gerou. */
    private fun coreByBackend(
        cpu: FakeBackend,
        gpu: FakeBackend,
        availMb: Long? = null,
        totalMb: Long? = null,
        sched: IdleScheduler = FakeScheduler(),
    ) = ReasoningServiceCore(
        backendFor = { if (it.backend == ComputeBackend.GPU) gpu else cpu },
        fileExists = { true },
        availableMb = { availMb },
        totalMb = { totalMb },
        scheduler = sched,
        executor = direct,
    )

    @Test
    fun `the same model on the same backend loads once`() {
        val cpu = FakeBackend(log, "cpu"); val gpu = FakeBackend(log, "gpu")
        val c = coreByBackend(cpu, gpu)
        run(c, gpu(model("a"))); run(c, gpu(model("a"))); run(c, gpu(model("a")))
        assertThat(log.count { it == "load:gpu" }).isEqualTo(1)
        assertThat(log).doesNotContain("load:cpu")
    }

    /** Trocar de CPU para GPU no card: o motor residente na CPU não serve, descarrega e recarrega na GPU (e o contrário também). */
    @Test
    fun `switching the backend of the loaded model unloads it and reloads on the other one`() {
        val cpu = FakeBackend(log, "cpu"); val gpu = FakeBackend(log, "gpu")
        val c = coreByBackend(cpu, gpu)
        run(c, model("a"))
        val onGpu = run(c, gpu(model("a")))
        run(c, model("a"))
        assertThat(onGpu.events).containsExactly("delta:um", "delta:dois", "done").inOrder()
        assertThat(log.filter { it.startsWith("load:") || it.startsWith("unload:") })
            .containsExactly("load:cpu", "unload:cpu", "load:gpu", "unload:gpu", "load:cpu").inOrder()
    }

    @Test
    fun `the backend reaches the engine in the model it is asked to load`() {
        val seen = mutableListOf<ComputeBackend>()
        val loading = object : LlmBackend {
            override fun load(model: LlmModel) { seen += model.backend }
            override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) = Unit
            override fun cancel() = Unit
            override fun unload() = Unit
        }
        val c = ReasoningServiceCore({ loading }, { true }, { null }, FakeScheduler(), direct)
        run(c, model("a")); run(c, gpu(model("a")))
        assertThat(seen).containsExactly(ComputeBackend.CPU, ComputeBackend.GPU).inOrder()
    }

    @Test
    fun `a GPU load that fails falls back to the CPU in the same request, tells why and keeps the answer`() {
        val cpu = FakeBackend(log, "cpu")
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = IllegalStateException("OpenCL indisponível") }
        val rec = run(coreByBackend(cpu, gpu), gpu(model("a")))
        assertThat(rec.events).containsExactly("fallback:GPU", "delta:um", "delta:dois", "done").inOrder()
        assertThat(log).containsAtLeast("load:gpu", "unload:gpu", "load:cpu", "generate:cpu").inOrder()
        assertThat(log).doesNotContain("generate:gpu")
    }

    @Test
    fun `the fallback reason carries the engine message`() {
        val reasons = mutableListOf<String>()
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = IllegalStateException("OpenCL indisponível") }
        val c = coreByBackend(FakeBackend(log, "cpu"), gpu)
        val rec = object : ReasoningServiceCore.Listener {
            override fun onDelta(text: String) = Unit
            override fun onDone() = Unit
            override fun onError(kind: ReasoningErrorKind, message: String) = Unit
            override fun onBackendFallback(failed: ComputeBackend, reason: String) { reasons += reason }
        }
        c.generate(gpu(model("a")), request, rec)
        assertThat(reasons.single()).contains("OpenCL indisponível")
    }

    @Test
    fun `an out of memory error loading on the GPU also falls back to the CPU`() {
        val cpu = FakeBackend(log, "cpu")
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = OutOfMemoryError("gpu") }
        val rec = run(coreByBackend(cpu, gpu), gpu(model("a")))
        assertThat(rec.events).containsExactly("fallback:GPU", "delta:um", "delta:dois", "done").inOrder()
    }

    @Test
    fun `a CPU load that fails is an error, never a fallback`() {
        val cpu = FakeBackend(log, "cpu").also { it.loadThrows = IllegalStateException("boom") }
        val rec = run(coreByBackend(cpu, FakeBackend(log, "gpu")), model("a"))
        assertThat(rec.events).containsExactly("error:ENGINE")
        assertThat(log).doesNotContain("load:gpu")
    }

    @Test
    fun `when the CPU fallback fails too the request ends with the CPU error`() {
        val cpu = FakeBackend(log, "cpu").also { it.loadThrows = IllegalStateException("cpu falhou") }
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = IllegalStateException("gpu falhou") }
        val rec = run(coreByBackend(cpu, gpu), gpu(model("a")))
        assertThat(rec.events).containsExactly("fallback:GPU", "error:ENGINE").inOrder()
    }

    /** Depois da volta, a CPU carregada serve o pedido seguinte que ainda traz a GPU: não repete uma carga de minutos que já falhou. */
    @Test
    fun `after a fallback the next automatic request with the old GPU choice does not retry the GPU`() {
        val cpu = FakeBackend(log, "cpu")
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = IllegalStateException("falhou") }
        val c = coreByBackend(cpu, gpu)
        run(c, gpu(model("a")))
        log.clear()
        val again = run(c, gpu(model("a")))
        assertThat(again.events).containsExactly("fallback:GPU", "delta:um", "delta:dois", "done").inOrder()
        assertThat(log).containsExactly("generate:cpu")
    }

    @Test
    fun `an explicit selection tries the GPU again after it failed`() {
        val cpu = FakeBackend(log, "cpu")
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = IllegalStateException("falhou") }
        val c = coreByBackend(cpu, gpu)
        run(c, gpu(model("a")))
        gpu.loadThrows = null            // o driver foi atualizado, o usuário tenta de novo no card
        log.clear()
        val rec = Recorder()
        c.preload(gpu(model("a")), rec)
        rec.awaitFinish()
        assertThat(rec.events).containsExactly("done")
        assertThat(log).containsAtLeast("unload:cpu", "load:gpu", "warm:gpu").inOrder()
        assertThat(run(c, gpu(model("a"))).events).doesNotContain("fallback:GPU")
    }

    @Test
    fun `a preload on the GPU that fails warms the CPU engine and tells the listener`() {
        val cpu = FakeBackend(log, "cpu")
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = IllegalStateException("falhou") }
        val rec = Recorder()
        coreByBackend(cpu, gpu).preload(gpu(model("a")), rec)
        rec.awaitFinish()
        assertThat(rec.events).containsExactly("fallback:GPU", "done").inOrder()
        assertThat(log).containsAtLeast("load:cpu", "warm:cpu").inOrder()
    }

    /** O fallback não descarrega o que não é dele: o motor carregado é o da CPU e o próximo pedido na CPU o reaproveita. */
    @Test
    fun `a request on the CPU reuses the engine left by the fallback`() {
        val cpu = FakeBackend(log, "cpu")
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = IllegalStateException("falhou") }
        val c = coreByBackend(cpu, gpu)
        run(c, gpu(model("a")))
        log.clear()
        run(c, model("a"))
        assertThat(log).containsExactly("generate:cpu")
    }

    // RamGuard por backend: a CPU segue a regra de sempre; a GPU não é barrada pela estimativa de CPU (o Qwen3 4B só cabe na GPU).

    @Test
    fun `a model that does not fit the device on the CPU still loads when asked for the GPU`() {
        val cpu = FakeBackend(log, "cpu"); val gpu = FakeBackend(log, "gpu")
        val big = model("a", ramMb = 4_096)
        val c = coreByBackend(cpu, gpu, availMb = 500, totalMb = 3_000)
        assertThat(run(c, big).events).containsExactly("error:OOM")
        assertThat(log).doesNotContain("load:cpu")
        assertThat(run(c, gpu(big)).events).containsExactly("delta:um", "delta:dois", "done").inOrder()
        assertThat(log).contains("load:gpu")
    }

    @Test
    fun `the CPU fallback is still held by the CPU RAM guard`() {
        val cpu = FakeBackend(log, "cpu")
        val gpu = FakeBackend(log, "gpu").also { it.loadThrows = IllegalStateException("falhou") }
        val rec = run(coreByBackend(cpu, gpu, availMb = 500, totalMb = 3_000), gpu(model("a", ramMb = 4_096)))
        assertThat(rec.events).containsExactly("fallback:GPU", "error:OOM").inOrder()
        assertThat(log).doesNotContain("load:cpu")
    }

    @Test
    fun `the GPU preload keeps the free RAM floor and ignores the CPU estimate`() {
        val cpu = FakeBackend(log, "cpu"); val gpu = FakeBackend(log, "gpu")
        val big = model("a", ramMb = 4_096)
        val low = Recorder()
        coreByBackend(cpu, gpu, availMb = 1_500).preload(gpu(big), low)
        low.awaitFinish()
        assertThat(low.events).containsExactly("error:OOM")
        val enough = Recorder()
        coreByBackend(cpu, gpu, availMb = 2_100).preload(gpu(big), enough)
        enough.awaitFinish()
        assertThat(enough.events).containsExactly("done")
    }
}
