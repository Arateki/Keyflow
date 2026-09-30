package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RTS-01..04: o que o botão Testar faz com o fluxo do motor — na JVM, com o relógio virtual do teste.
 * Atenção: o runner vive no `backgroundScope`, e o `advanceUntilIdle()` NÃO executa tarefas de fundo — por isso `runCurrent()`
 * e `advanceTimeBy` (foi o que fez 6 destes testes falharem na 1ª rodada).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestRunnerTest {

    private val request = ReasoningRequest(user = "oi")

    private class Scripted(private val script: suspend FlowCollector<ReasoningEvent>.() -> Unit) : ReasoningEngine {
        var cancelled = false
        var started = 0
        override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> =
            flow { started++; script() }.onCompletion { cause -> if (cause is CancellationException) cancelled = true }
    }

    private fun ready(engine: ReasoningEngine) = ReasoningResolution.Ready(engine)

    @Test
    fun `accumulates the deltas in order and finishes on done`() = runTest {
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(ready(Scripted { emit(ReasoningEvent.Delta("a")); emit(ReasoningEvent.Delta("b")); emit(ReasoningEvent.Done) }), request)
        runCurrent()

        val state = runner.state.value
        assertThat(state.text).isEqualTo("ab")
        assertThat(state.running).isFalse()
        assertThat(state.error).isNull()
    }

    @Test
    fun `records the time to the first delta and the total`() = runTest {
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(
            ready(Scripted { delay(100); emit(ReasoningEvent.Delta("a")); delay(50); emit(ReasoningEvent.Done) }),
            request,
        )
        advanceTimeBy(200) // 100 ms até o 1º trecho + 50 ms até o fim
        runCurrent()

        assertThat(runner.state.value.firstDeltaMs).isEqualTo(100L)
        assertThat(runner.state.value.totalMs).isEqualTo(150L)
    }

    @Test
    fun `while running there is no total yet`() = runTest {
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(ready(Scripted { emit(ReasoningEvent.Delta("a")); awaitCancellation() }), request)
        runCurrent()

        assertThat(runner.state.value.running).isTrue()
        assertThat(runner.state.value.totalMs).isNull()
        assertThat(runner.state.value.firstDeltaMs).isEqualTo(0L)
    }

    @Test
    fun `stop cancels the upstream and keeps the partial text`() = runTest {
        val engine = Scripted { emit(ReasoningEvent.Delta("par")); awaitCancellation() }
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(ready(engine), request)
        runCurrent()
        assertThat(runner.state.value.running).isTrue()

        runner.stop()
        runCurrent()

        assertThat(engine.cancelled).isTrue()
        assertThat(runner.state.value.running).isFalse()
        assertThat(runner.state.value.text).isEqualTo("par")
    }

    @Test
    fun `an error keeps the partial text and records the kind`() = runTest {
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(
            ready(Scripted { emit(ReasoningEvent.Delta("x")); emit(ReasoningEvent.Error(ReasoningError.NETWORK, "m")) }),
            request,
        )
        runCurrent()

        val state = runner.state.value
        assertThat(state.text).isEqualTo("x")
        assertThat(state.error).isEqualTo(ReasoningError.NETWORK)
        assertThat(state.running).isFalse()
    }

    /** Review Focus 4: Testar duas vezes seguidas — o primeiro é cancelado e o texto dele não vaza para o segundo. */
    @Test
    fun `starting twice cancels the first and does not leak its text`() = runTest {
        val first = Scripted { emit(ReasoningEvent.Delta("primeiro")); awaitCancellation() }
        val second = Scripted { emit(ReasoningEvent.Delta("2")); emit(ReasoningEvent.Done) }
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(ready(first), request)
        runCurrent()
        assertThat(runner.state.value.text).isEqualTo("primeiro")

        runner.start(ready(second), request)
        runCurrent()

        assertThat(first.cancelled).isTrue()
        assertThat(runner.state.value.text).isEqualTo("2")
        assertThat(runner.state.value.running).isFalse()
    }

    @Test
    fun `unavailable never starts and exposes the reason`() = runTest {
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL), request)
        runCurrent()

        assertThat(runner.state.value.running).isFalse()
        assertThat(runner.state.value.unavailable).isEqualTo(UnavailableReason.NO_LOCAL_MODEL)
        assertThat(runner.state.value.text).isEmpty()
    }

    /** Sair da tela / girar: o escopo morre e a geração vai junto — nada de motor ocupado por uma tela que não existe. */
    @Test
    fun `cancelling the scope cancels the engine`() = runTest {
        val engine = Scripted { emit(ReasoningEvent.Delta("a")); awaitCancellation() }
        val job = Job()
        val runner = TestRunner(CoroutineScope(StandardTestDispatcher(testScheduler) + job)) { currentTime }
        runner.start(ready(engine), request)
        runCurrent()
        assertThat(engine.cancelled).isFalse()

        job.cancel()
        runCurrent()

        assertThat(engine.cancelled).isTrue()
    }

    @Test
    fun `an engine that ends without done or error still stops running`() = runTest {
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(ready(Scripted { emit(ReasoningEvent.Delta("z")) }), request)
        runCurrent()

        assertThat(runner.state.value.running).isFalse()
        assertThat(runner.state.value.totalMs).isNotNull()
        assertThat(runner.state.value.text).isEqualTo("z")
    }

    @Test
    fun `a new start clears the previous error and text`() = runTest {
        val runner = TestRunner(backgroundScope) { currentTime }
        runner.start(ready(Scripted { emit(ReasoningEvent.Error(ReasoningError.OOM, null)) }), request)
        runCurrent()
        assertThat(runner.state.value.error).isEqualTo(ReasoningError.OOM)

        runner.start(ready(Scripted { emit(ReasoningEvent.Delta("ok")); emit(ReasoningEvent.Done) }), request)
        runCurrent()

        assertThat(runner.state.value.error).isNull()
        assertThat(runner.state.value.text).isEqualTo("ok")
    }

    // ── mensagens legíveis (RTS-02/04) ────────────────────────────────────────────────────────

    @Test
    fun `every error kind and every unavailable reason has its own readable message`() {
        val errors = ReasoningError.entries.map { ReasoningTestMessages.error(it) }
        val reasons = UnavailableReason.entries.map { ReasoningTestMessages.unavailable(it) }
        assertThat(errors.all { it.isNotBlank() }).isTrue()
        assertThat(reasons.all { it.isNotBlank() }).isTrue()
        assertThat(errors).containsNoDuplicates()
        assertThat(reasons).containsNoDuplicates()
    }

    @Test
    fun `no message leaks a stack trace or an exception name`() {
        val all = ReasoningError.entries.map { ReasoningTestMessages.error(it) } +
            UnavailableReason.entries.map { ReasoningTestMessages.unavailable(it) }
        all.forEach { text ->
            assertThat(text).doesNotContain("Exception")
            assertThat(text).doesNotContain("at com.")
        }
    }
}
