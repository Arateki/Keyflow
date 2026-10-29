package com.keyflow.bancada.executor

import com.google.common.truth.Truth.assertThat
import com.keyflow.bancada.corpus.Capacidade
import com.keyflow.bancada.corpus.Caso
import com.keyflow.bancada.corpus.Prompt
import com.keyflow.bancada.corpus.TesteDef
import com.keyflow.bancada.pontuacao.Pontuacao
import com.keyflow.bancada.pontuacao.Pontuador
import com.keyflow.bancada.pontuacao.caso
import com.keyflow.bancada.resultado.Aparelho
import com.keyflow.bancada.resultado.AppInfo
import com.keyflow.bancada.resultado.CausaFalha
import com.keyflow.bancada.resultado.CausaInvalido
import com.keyflow.bancada.resultado.EstadoCaso
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.ModeloInfo
import com.keyflow.bancada.resultado.MotivoIncompleta
import com.keyflow.bancada.resultado.MotorInfo
import com.keyflow.bancada.resultado.OpcoesRegistradas
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * BNC-T11: o executor com motor falso e relógio virtual. Cobre o caso OK; OOM, queda e timeout locais (falha real, nota 0); rede e limite online
 * (inválido, fora da média); a pausa e o encerramento por térmico e bateria; o cancelamento que chega ao motor; e a saída divergente que liga
 * `nao_deterministico` e a mediana de 3. O "pontuador" aqui lê a própria saída como nota: o que se testa é o executor, não a pontuação.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutorBancadaTest {

    private val pontuadorDeNota = Pontuador { _, saida -> Pontuacao(saida.trim().toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0) }

    private val cabecalho = Execucao(
        id = "e1", iniciada = "2026-10-28T14:03:11Z", estado = EstadoExecucao.EM_ANDAMENTO,
        aparelho = Aparelho("samsung", "SM-A325M", null, "arm64-v8a", 3700, 33),
        app = AppInfo("1.0.0", 1, "debug", "abc1234"), motor = MotorInfo("litert-lm", "0.16.1"), corpus = 1, perfil = "completo",
        modelo = ModeloInfo("qwen3-0.6b-litertlm", "local", "c5f39b41", "CPU", 4, false),
        opcoes = OpcoesRegistradas(emptyMap(), emptySet()),
    )

    private fun casos(n: Int, tokensEntrada: Int = 10, maxTokens: Int = 96): List<Caso> = (1..n).map { i ->
        val c = caso(id = "c-%02d".format(i), entrada = "entrada $i", esperado = "x", maxTokensSaida = maxTokens)
        Caso(c.id, c.idioma, c.entrada, c.maxTokensSaida, tokensEntrada, c.bruto)
    }

    private fun plano(origem: Origem, casos: List<Caso>, janela: Int? = null): PlanoDeExecucao {
        val def = TesteDef("pontuacao", 1, Capacidade.TEXTO, "d", Prompt("sistema", "{entrada}"), casos)
        return PlanoDeExecucao(cabecalho, origem, listOf(TesteNoPlano(def, casos)), janelaContextoTokens = janela)
    }

    /** Resposta que chega em [primeiro] ms e termina em [total] ms. */
    private fun resposta(texto: String, primeiro: Long = 100, total: Long = 300): Flow<ReasoningEvent> = flow {
        delay(primeiro)
        emit(ReasoningEvent.Delta(texto))
        delay(total - primeiro)
        emit(ReasoningEvent.Done)
    }

    private fun erro(tipo: ReasoningError, depoisDeMs: Long = 10): Flow<ReasoningEvent> = flow {
        delay(depoisDeMs)
        emit(ReasoningEvent.Error(tipo, "falha $tipo"))
    }

    private class MotorFalso(val roteiro: (chamada: Int, entrada: EntradaDoMotor) -> Flow<ReasoningEvent>) : MotorDeCapacidade {
        var chamadas = 0
        val entradas = mutableListOf<EntradaDoMotor>()
        override val capacidade = Capacidade.TEXTO
        override fun executar(entrada: EntradaDoMotor): Flow<ReasoningEvent> = flow {
            chamadas++
            entradas += entrada
            emitAll(roteiro(chamadas, entrada))
        }
    }

    private fun TestScope.executor(
        motor: MotorDeCapacidade,
        config: ConfigExecutor,
        ambiente: AmbienteDoAparelho = AmbienteDoAparelho(),
        pausa: suspend (Long) -> Unit = { delay(it) },
    ) = ExecutorBancada(motor, { currentTime }, config, ambiente, pausa) { pontuadorDeNota }

    private val local = ConfigExecutor.LOCAL.copy(timeoutCasoMs = 1_000)
    private val online = ConfigExecutor.ONLINE.copy(timeoutCasoMs = 1_000)

    // ── caso OK ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `an ok case is scored, timed and carries the prompt options`() = runTest {
        val motor = MotorFalso { _, _ -> resposta("1") }
        val r = executor(motor, local).executar(plano(Origem.ONLINE, casos(2)))
        assertThat(r.estado).isEqualTo(EstadoExecucao.COMPLETA)
        val teste = r.testes.single()
        assertThat(teste.nota).isEqualTo(1.0)
        assertThat(teste.n).isEqualTo(2)
        val c = teste.casos.first()
        assertThat(c.estado).isEqualTo(EstadoCaso.OK)
        assertThat(c.primeiroTrechoMs).isEqualTo(100)
        assertThat(c.totalMs).isEqualTo(300)
        assertThat(c.saida).isEqualTo("1")
        assertThat(teste.primeiroTrechoMsP50).isEqualTo(100)
        assertThat(teste.totalMsP50).isEqualTo(300)
        // a bancada pede temperatura 0, semente fixa e o teto de saída do caso; o prompt vem montado do corpus
        val pedido = motor.entradas.first()
        assertThat(pedido.opcoes.temperature).isEqualTo(0.0)
        assertThat(pedido.opcoes.seed).isEqualTo(42)
        assertThat(pedido.opcoes.maxOutputTokens).isEqualTo(96)
        assertThat(pedido.prompt.system).isEqualTo("sistema")
        assertThat(pedido.prompt.user).isEqualTo("entrada 1")
    }

    @Test
    fun `tokens locais sem metricas sao estimados e marcados`() = runTest {
        val r = executor(MotorFalso { _, _ -> resposta("1234567890123456", primeiro = 100, total = 1_100) }, local.copy(timeoutCasoMs = 10_000))
            .executar(plano(Origem.LOCAL, casos(1)))
        val c = r.testes.single().casos.single()
        assertThat(c.tokensEstimados).isTrue()
        assertThat(c.tokensSaida).isEqualTo(4)
        assertThat(c.tokensPorSegundo).isWithin(1e-9).of(3.0) // (4-1) tokens em 1000 ms
    }

    @Test
    fun `tokens online ausentes permanecem nulos e nao inventam taxa`() = runTest {
        val r = executor(MotorFalso { _, _ -> resposta("1234567890123456", primeiro = 100, total = 1_100) }, local.copy(timeoutCasoMs = 10_000))
            .executar(plano(Origem.ONLINE, casos(1)))
        val c = r.testes.single().casos.single()
        assertThat(c.tokensEntrada).isNull()
        assertThat(c.tokensSaida).isNull()
        assertThat(c.tokensPorSegundo).isNull()
        assertThat(c.tokensEstimados).isFalse()
    }

    @Test
    fun `engine metrics win over the estimate and fill applied and ignored options`() = runTest {
        val metricas = ReasoningEvent.Metrics(
            applied = mapOf("temperature" to "0.0", "seed" to "42", "maxOutputTokens" to "96"), ignored = setOf("topK"),
            inputTokens = 55, outputTokens = 7, tokensPerSecond = 12.5,
        )
        val motor = MotorFalso { _, _ -> flow { emit(metricas); emitAll(resposta("1")) } }
        val r = executor(motor, local).executar(plano(Origem.ONLINE, casos(1)))
        val c = r.testes.single().casos.single()
        assertThat(c.tokensEstimados).isFalse()
        assertThat(c.tokensEntrada).isEqualTo(55)
        assertThat(c.tokensSaida).isEqualTo(7)
        assertThat(c.tokensPorSegundo).isEqualTo(12.5)
        assertThat(r.opcoes.aplicadas).containsExactly("temperature", "0.0", "seed", "42", "maxOutputTokens", "96")
        assertThat(r.opcoes.ignoradas).containsExactly("topK")
    }

    @Test
    fun `an engine that says nothing about the options has them all marked ignored - never silently`() = runTest {
        val r = executor(MotorFalso { _, _ -> resposta("1") }, local).executar(plano(Origem.ONLINE, casos(1)))
        assertThat(r.opcoes.aplicadas).isEmpty()
        assertThat(r.opcoes.ignoradas).containsExactly("temperature", "seed", "maxOutputTokens")
    }

    @Test
    fun `each case is written as it finishes and the last write is the complete run`() = runTest {
        val escritas = mutableListOf<Execucao>()
        executor(MotorFalso { _, _ -> resposta("1") }, local).executar(plano(Origem.ONLINE, casos(3)), aoMudar = { escritas += it })
        assertThat(escritas.map { it.estado }).containsExactly(
            EstadoExecucao.EM_ANDAMENTO, EstadoExecucao.EM_ANDAMENTO, EstadoExecucao.EM_ANDAMENTO, EstadoExecucao.EM_ANDAMENTO, EstadoExecucao.COMPLETA,
        ).inOrder()
        assertThat(escritas.dropLast(1).drop(1).map { it.testes.single().casos.size }).containsExactly(1, 2, 3).inOrder()
        assertThat(escritas.last().testes.single().casos).hasSize(3)
    }

    // ── falhas locais ────────────────────────────────────────────────────────────────────

    @Test
    fun `local OOM, crash and timeout are real failures with grade 0 and the run goes on`() = runTest {
        // O roteiro vai pelo caso, não pela ordem das chamadas: a verificação de determinismo (local) repete os dois primeiros.
        val motor = MotorFalso { _, entrada ->
            when (entrada.prompt.user) {
                "entrada 1" -> erro(ReasoningError.OOM)
                "entrada 2" -> erro(ReasoningError.LOCAL)
                "entrada 3" -> flow { delay(5_000); emit(ReasoningEvent.Done) } // passa do prazo de 1 s
                "entrada 4" -> erro(ReasoningError.ENGINE)
                else -> resposta("1")
            }
        }
        val r = executor(motor, local).executar(plano(Origem.LOCAL, casos(5)))
        val c = r.testes.single().casos
        assertThat(c.map { it.estado }).containsExactly(EstadoCaso.FALHA, EstadoCaso.FALHA, EstadoCaso.FALHA, EstadoCaso.FALHA, EstadoCaso.OK).inOrder()
        assertThat(c.map { it.causa }).containsExactly(CausaFalha.OOM.name, CausaFalha.QUEDA.name, CausaFalha.TIMEOUT.name, CausaFalha.ENGINE.name, null).inOrder()
        assertThat(c.take(4).map { it.nota }).containsExactly(0.0, 0.0, 0.0, 0.0)
        assertThat(r.testes.single().nota).isWithin(1e-9).of(0.2) // 4 falhas contam como 0, 1 acerto como 1
        assertThat(r.testes.single().n).isEqualTo(5)
        assertThat(r.estado).isEqualTo(EstadoExecucao.COMPLETA)
    }

    @Test
    fun `a missing local model ends the run as incomplete`() = runTest {
        val motor = MotorFalso { _, _ -> erro(ReasoningError.MODEL_MISSING) }
        val r = executor(motor, local).executar(plano(Origem.LOCAL, casos(4)))
        assertThat(r.estado).isEqualTo(EstadoExecucao.INCOMPLETA)
        assertThat(r.motivo).isEqualTo(MotivoIncompleta.MODELO_AUSENTE)
        assertThat(motor.chamadas).isEqualTo(1)
    }

    @Test
    fun `an engine that throws or ends without Done is an engine failure that keeps no message`() = runTest {
        val motor = MotorFalso { n, _ ->
            if (n == 1) flow { throw IllegalStateException("cofre: sk-segredo-que-nao-pode-vazar") } else flow { emit(ReasoningEvent.Delta("1")) }
        }
        val r = executor(motor, local).executar(plano(Origem.ONLINE, casos(2)))
        val c = r.testes.single().casos
        assertThat(c.map { it.estado }).containsExactly(EstadoCaso.FALHA, EstadoCaso.FALHA)
        assertThat(c.first().motivo).isEqualTo("o motor lançou IllegalStateException")
        assertThat(c.first().motivo).doesNotContain("segredo")
        assertThat(c[1].motivo).contains("sem Done")
    }

    // ── online ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `an online network error is retried once and a later success counts`() = runTest {
        val motor = MotorFalso { n, _ -> if (n == 1) erro(ReasoningError.NETWORK) else resposta("1") }
        val r = executor(motor, online).executar(plano(Origem.ONLINE, casos(1)))
        assertThat(r.testes.single().casos.single().estado).isEqualTo(EstadoCaso.OK)
        assertThat(motor.chamadas).isEqualTo(2)
    }

    @Test
    fun `a persistent online network error is an invalid case, out of the average but counted`() = runTest {
        val motor = MotorFalso { n, _ -> if (n <= 2) erro(ReasoningError.NETWORK) else resposta("0.5") }
        val r = executor(motor, online).executar(plano(Origem.ONLINE, casos(2)))
        val teste = r.testes.single()
        assertThat(teste.casos[0].estado).isEqualTo(EstadoCaso.INVALIDO)
        assertThat(teste.casos[0].causa).isEqualTo(CausaInvalido.REDE.name)
        assertThat(teste.casos[0].nota).isNull()
        assertThat(teste.invalidos).isEqualTo(1)
        assertThat(teste.n).isEqualTo(1)
        assertThat(teste.nota).isEqualTo(0.5)
        assertThat(r.estado).isEqualTo(EstadoExecucao.COMPLETA)
    }

    @Test
    fun `an online timeout is retried and then invalid, not a zero`() = runTest {
        val motor = MotorFalso { _, _ -> flow { delay(5_000); emit(ReasoningEvent.Done) } }
        val r = executor(motor, online).executar(plano(Origem.ONLINE, casos(1)))
        val c = r.testes.single().casos.single()
        assertThat(c.estado).isEqualTo(EstadoCaso.INVALIDO)
        assertThat(motor.chamadas).isEqualTo(2)
        assertThat(r.testes.single().nota).isNull()
    }

    @Test
    fun `an online rate limit ends the run as incomplete and stops calling`() = runTest {
        val motor = MotorFalso { n, _ -> if (n == 3) erro(ReasoningError.RATE_LIMIT) else resposta("1") }
        val r = executor(motor, online).executar(plano(Origem.ONLINE, casos(6)))
        assertThat(r.estado).isEqualTo(EstadoExecucao.INCOMPLETA)
        assertThat(r.motivo).isEqualTo(MotivoIncompleta.RATE_LIMIT)
        assertThat(motor.chamadas).isEqualTo(3)
        val teste = r.testes.single()
        assertThat(teste.casos.map { it.estado }).containsExactly(EstadoCaso.OK, EstadoCaso.OK, EstadoCaso.INVALIDO).inOrder()
        assertThat(teste.casos.last().causa).isEqualTo(CausaInvalido.LIMITE.name)
    }

    @Test
    fun `a refused credential ends the run as incomplete without retrying`() = runTest {
        for (tipo in listOf(ReasoningError.UNAUTHORIZED, ReasoningError.NOT_ELIGIBLE)) {
            val motor = MotorFalso { _, _ -> erro(tipo) }
            val r = executor(motor, online).executar(plano(Origem.ONLINE, casos(3)))
            assertThat(r.motivo).isEqualTo(MotivoIncompleta.CREDENCIAL)
            assertThat(motor.chamadas).isEqualTo(1)
            assertThat(r.testes.single().casos.single().causa).isEqualTo(CausaInvalido.CREDENCIAL.name)
        }
    }

    // ── janela de contexto ───────────────────────────────────────────────────────────────

    @Test
    fun `a case bigger than the model window does not apply and is never sent`() = runTest {
        val motor = MotorFalso { _, _ -> resposta("1") }
        val grandes = casos(2, tokensEntrada = 2_000, maxTokens = 48) + casos(1, tokensEntrada = 100, maxTokens = 48).map {
            Caso("pequeno", it.idioma, it.entrada, it.maxTokensSaida, it.tokensEntrada, it.bruto)
        }
        val r = executor(motor, online).executar(plano(Origem.ONLINE, grandes, janela = 1_024))
        val teste = r.testes.single()
        assertThat(teste.casos.map { it.estado }).containsExactly(EstadoCaso.NAO_SE_APLICA, EstadoCaso.NAO_SE_APLICA, EstadoCaso.OK).inOrder()
        assertThat(teste.naoSeAplica).isEqualTo(2)
        assertThat(teste.n).isEqualTo(1)
        assertThat(motor.chamadas).isEqualTo(1)
        val tudo = executor(MotorFalso { _, _ -> resposta("1") }, online).executar(plano(Origem.ONLINE, casos(2, tokensEntrada = 2_000), janela = 1_024))
        assertThat(tudo.testes.single().nota).isNull() // "não se aplica" nunca vira nota 0
    }

    // ── térmico e bateria ────────────────────────────────────────────────────────────────

    @Test
    fun `a hot device pauses the run until it cools down`() = runTest {
        var termico = 3
        val pausas = mutableListOf<Long>()
        val fases = mutableListOf<Progresso.Fase>()
        val ambiente = AmbienteDoAparelho(termico = { termico })
        val ex = executor(MotorFalso { _, _ -> resposta("1") }, local.copy(passoDaPausaMs = 15_000), ambiente, pausa = { pausas += it; if (pausas.size == 4) termico = 0; delay(it) })
        val r = ex.executar(plano(Origem.ONLINE, casos(2)), aoProgredir = { fases += it.fase })
        assertThat(pausas).containsExactly(15_000L, 15_000L, 15_000L, 15_000L)
        assertThat(fases).contains(Progresso.Fase.PAUSADO_TERMICO)
        assertThat(r.estado).isEqualTo(EstadoExecucao.COMPLETA)
        assertThat(r.testes.single().casos.map { it.termico }).containsExactly(0, 0)
    }

    @Test
    fun `a device that stays hot for ten minutes ends the run as incomplete`() = runTest {
        val pausas = mutableListOf<Long>()
        val motor = MotorFalso { _, _ -> resposta("1") }
        val ambiente = AmbienteDoAparelho(termico = { 3 })
        val r = executor(motor, local, ambiente, pausa = { pausas += it; delay(it) }).executar(plano(Origem.ONLINE, casos(3)))
        assertThat(r.estado).isEqualTo(EstadoExecucao.INCOMPLETA)
        assertThat(r.motivo).isEqualTo(MotivoIncompleta.TERMICO)
        assertThat(pausas.sum()).isEqualTo(10 * 60_000L)
        assertThat(motor.chamadas).isEqualTo(0)
        assertThat(currentTime).isEqualTo(10 * 60_000L)
    }

    @Test
    fun `a low battery that is not charging pauses and then ends the run, charging does not`() = runTest {
        val sem = executor(MotorFalso { _, _ -> resposta("1") }, local, AmbienteDoAparelho(bateria = { LeituraBateria(12, carregando = false) }))
            .executar(plano(Origem.ONLINE, casos(2)))
        assertThat(sem.motivo).isEqualTo(MotivoIncompleta.BATERIA)
        val carregando = executor(MotorFalso { _, _ -> resposta("1") }, local, AmbienteDoAparelho(bateria = { LeituraBateria(12, carregando = true) }))
            .executar(plano(Origem.ONLINE, casos(2)))
        assertThat(carregando.estado).isEqualTo(EstadoExecucao.COMPLETA)
        assertThat(carregando.testes.single().casos.map { it.bateriaPct }).containsExactly(12, 12)
    }

    @Test
    fun `the environment is checked again every ten cases`() = runTest {
        lateinit var motor: MotorFalso
        var recarregado = false
        var pausadoAposChamadas = -1
        motor = MotorFalso { _, _ -> resposta("1", primeiro = 10, total = 20) }
        val ambiente = AmbienteDoAparelho(bateria = { LeituraBateria(if (motor.chamadas >= 10 && !recarregado) 5 else 80, carregando = false) })
        val ex = executor(motor, local, ambiente, pausa = { pausadoAposChamadas = motor.chamadas; recarregado = true; delay(it) })
        val r = ex.executar(plano(Origem.ONLINE, casos(25)))
        assertThat(pausadoAposChamadas).isEqualTo(10)
        assertThat(r.estado).isEqualTo(EstadoExecucao.COMPLETA)
        assertThat(r.testes.single().casos).hasSize(25)
    }

    // ── cancelar ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `cancelling in the middle reaches the engine and keeps what was measured as incomplete`() = runTest {
        var motorCancelado = false
        val motor = MotorFalso { n, _ ->
            if (n == 3) flow { delay(60_000); emit(ReasoningEvent.Done) }.onCompletion { if (it is CancellationException) motorCancelado = true }
            else resposta("1")
        }
        val escritas = mutableListOf<Execucao>()
        var cancelou: Throwable? = null
        val job = launch {
            try {
                executor(motor, local.copy(timeoutCasoMs = 120_000)).executar(plano(Origem.ONLINE, casos(5)), aoMudar = { escritas += it })
            } catch (e: CancellationException) {
                cancelou = e
                throw e
            }
        }
        advanceTimeBy(1_000)
        runCurrent()
        job.cancel()
        runCurrent()
        assertThat(motorCancelado).isTrue()
        assertThat(cancelou).isNotNull()
        val ultima = escritas.last()
        assertThat(ultima.estado).isEqualTo(EstadoExecucao.INCOMPLETA)
        assertThat(ultima.motivo).isEqualTo(MotivoIncompleta.CANCELADA)
        assertThat(ultima.testes.single().casos).hasSize(2)
    }

    // ── determinismo ─────────────────────────────────────────────────────────────────────

    @Test
    fun `identical repeated outputs keep a single run per case`() = runTest {
        val motor = MotorFalso { _, _ -> resposta("1") }
        val r = executor(motor, local).executar(plano(Origem.LOCAL, casos(3)))
        assertThat(r.testes.single().naoDeterministico).isFalse()
        assertThat(motor.chamadas).isEqualTo(5) // 2 casos x 2 repetições da verificação + o 3º caso
        assertThat(r.testes.single().casos.all { it.notasRepeticoes.isEmpty() }).isTrue()
    }

    @Test
    fun `a diverging output turns on non determinism and the grade is the median of three`() = runTest {
        val saidas = mapOf("entrada 1" to listOf("1", "0", "1"), "entrada 2" to listOf("1", "1", "1"), "entrada 3" to listOf("0.5", "1", "0"))
        val vistas = HashMap<String, Int>()
        val motor = MotorFalso { _, entrada ->
            val i = vistas.merge(entrada.prompt.user, 1, Int::plus)!! - 1
            resposta(saidas.getValue(entrada.prompt.user)[i])
        }
        val r = executor(motor, local).executar(plano(Origem.LOCAL, casos(3)))
        val teste = r.testes.single()
        assertThat(teste.naoDeterministico).isTrue()
        assertThat(motor.chamadas).isEqualTo(9) // 4 da verificação + 1 + 1 extras nos dois casos verificados + 3 do terceiro
        assertThat(teste.casos.map { it.nota }).containsExactly(1.0, 1.0, 0.5).inOrder() // medianas de [1,0,1], [1,1,1], [0.5,1,0]
        assertThat(teste.casos[0].notasRepeticoes).containsExactly(1.0, 0.0, 1.0).inOrder()
        assertThat(teste.casos[2].notasRepeticoes).containsExactly(0.5, 1.0, 0.0).inOrder()
        assertThat(teste.nota).isWithin(1e-9).of(2.5 / 3)
    }

    @Test
    fun `online runs skip the determinism check`() = runTest {
        val motor = MotorFalso { _, _ -> resposta("1") }
        executor(motor, online).executar(plano(Origem.ONLINE, casos(3)))
        assertThat(motor.chamadas).isEqualTo(3)
    }

    // ── contrato ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `progress reports the case, the test and the phase`() = runTest {
        val vistos = mutableListOf<Progresso>()
        executor(MotorFalso { _, _ -> resposta("1") }, local).executar(plano(Origem.ONLINE, casos(2)), aoProgredir = { vistos += it })
        assertThat(vistos.map { it.casoAtual }).containsExactly(1, 2).inOrder()
        assertThat(vistos.first().totalDeCasos).isEqualTo(2)
        assertThat(vistos.first().teste).isEqualTo("pontuacao")
    }

    @Test
    fun `a test without a scorer or for another capability is refused up front`() = runTest {
        val semPontuador = ExecutorBancada(MotorFalso { _, _ -> resposta("1") }, { currentTime }, local, pontuadores = { null })
        val erro = runCatching { semPontuador.executar(plano(Origem.ONLINE, casos(1))) }.exceptionOrNull()
        assertThat(erro).isInstanceOf(IllegalArgumentException::class.java)
    }
}
