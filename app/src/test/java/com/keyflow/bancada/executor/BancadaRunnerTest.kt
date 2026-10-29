package com.keyflow.bancada.executor

import com.google.common.truth.Truth.assertThat
import com.keyflow.bancada.corpus.Capacidade
import com.keyflow.bancada.corpus.Prompt
import com.keyflow.bancada.corpus.TesteDef
import com.keyflow.bancada.pontuacao.Pontuacao
import com.keyflow.bancada.pontuacao.Pontuador
import com.keyflow.bancada.pontuacao.caso
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.MotivoIncompleta
import com.keyflow.bancada.resultado.execucaoDeExemplo
import com.keyflow.reasoning.ReasoningEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** O `BancadaRunner`: uma execução por vez, no escopo dele (não no da tela), com Parar que cancela e entrega o medido como `incompleta`. */
@OptIn(ExperimentalCoroutinesApi::class)
class BancadaRunnerTest {

    private val motor = object : MotorDeCapacidade {
        override val capacidade = Capacidade.TEXTO
        override fun executar(entrada: EntradaDoMotor): Flow<ReasoningEvent> = flow {
            delay(500)
            emit(ReasoningEvent.Delta("1"))
            emit(ReasoningEvent.Done)
        }
    }

    private fun plano(n: Int): PlanoDeExecucao {
        val casos = (1..n).map { caso(id = "c-$it", entrada = "e$it", esperado = "x") }
        val def = TesteDef("pontuacao", 1, Capacidade.TEXTO, "d", Prompt(null, "{entrada}"), casos)
        return PlanoDeExecucao(execucaoDeExemplo(estado = EstadoExecucao.EM_ANDAMENTO).copy(testes = emptyList()), Origem.ONLINE, listOf(TesteNoPlano(def, casos)))
    }

    private fun kotlinx.coroutines.test.TestScope.executor() =
        ExecutorBancada(motor, { currentTime }, ConfigExecutor.ONLINE) { Pontuador { _, _ -> Pontuacao(1.0) } }

    @Test
    fun `a run goes from idle to running to finished and every step is written`() = runTest {
        val gravadas = mutableListOf<Execucao>()
        val runner = BancadaRunner(backgroundScope) { gravadas += it }
        assertThat(runner.estado.value).isEqualTo(EstadoBancada.Ocioso)

        assertThat(runner.iniciar(plano(2), executor())).isTrue()
        assertThat(runner.estado.value).isInstanceOf(EstadoBancada.Rodando::class.java)
        advanceTimeBy(600)
        runCurrent()
        val rodando = runner.estado.value as EstadoBancada.Rodando
        assertThat(rodando.progresso!!.casoAtual).isEqualTo(2)
        assertThat(rodando.parcial!!.testes.single().casos).hasSize(1)

        advanceTimeBy(600)
        runCurrent()
        val fim = runner.estado.value as EstadoBancada.Terminou
        assertThat(fim.execucao.estado).isEqualTo(EstadoExecucao.COMPLETA)
        assertThat(gravadas.last().estado).isEqualTo(EstadoExecucao.COMPLETA)
        runner.reconhecer()
        assertThat(runner.estado.value).isEqualTo(EstadoBancada.Ocioso)
    }

    @Test
    fun `a second run is refused while one is going`() = runTest {
        val runner = BancadaRunner(backgroundScope) { }
        assertThat(runner.iniciar(plano(2), executor())).isTrue()
        runCurrent()
        assertThat(runner.iniciar(plano(2), executor())).isFalse()
        assertThat(runner.rodando).isTrue()
    }

    @Test
    fun `stop cancels the engine and leaves what was measured as an incomplete run`() = runTest {
        val gravadas = mutableListOf<Execucao>()
        val runner = BancadaRunner(backgroundScope) { gravadas += it }
        runner.iniciar(plano(5), executor())
        advanceTimeBy(1_100)
        runCurrent()
        runner.parar()
        runCurrent()
        val fim = runner.estado.value as EstadoBancada.Terminou
        assertThat(fim.execucao.estado).isEqualTo(EstadoExecucao.INCOMPLETA)
        assertThat(fim.execucao.motivo).isEqualTo(MotivoIncompleta.CANCELADA)
        assertThat(fim.execucao.testes.single().casos).hasSize(2)
        assertThat(gravadas.last().motivo).isEqualTo(MotivoIncompleta.CANCELADA)
        assertThat(runner.rodando).isFalse()
        // e dá para começar outra
        assertThat(runner.iniciar(plano(1), executor())).isTrue()
    }

    @Test
    fun `an error in the writer becomes a message, not a crash`() = runTest {
        val runner = BancadaRunner(backgroundScope) { error("disco cheio: sk-segredo") }
        runner.iniciar(plano(1), executor())
        runCurrent()
        val falhou = runner.estado.value as EstadoBancada.Falhou
        assertThat(falhou.mensagem).isEqualTo("IllegalStateException")
    }
}
