package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
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
        fun awaitFinish() = assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue()
    }

    private class FakeBackend(private val log: MutableList<String>, val id: String, override val requiresModelFile: Boolean = true) : LlmBackend {
        var loadThrows: Throwable? = null
        var generateThrows: Throwable? = null
        val hooks = ArrayDeque<() -> Unit>()
        @Volatile var cancelCalled = false
        override fun load(model: LlmModel) { log += "load:$id"; loadThrows?.let { throw it } }
        override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) {
            log += "generate:$id"
            hooks.removeFirstOrNull()?.invoke()
            generateThrows?.let { throw it }
            request.user.split(" ").forEach(onDelta)
        }
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
        sched: IdleScheduler = FakeScheduler(),
        executor: Executor = direct,
    ) = ReasoningServiceCore(
        backendFor = { backends.getValue(it.modelId) },
        fileExists = { exists },
        availableMb = { availMb },
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
}
