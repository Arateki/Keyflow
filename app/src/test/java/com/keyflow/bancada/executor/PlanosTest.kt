package com.keyflow.bancada.executor

import com.google.common.truth.Truth.assertThat
import com.keyflow.bancada.corpus.CorpusLoader
import com.keyflow.bancada.corpus.DiretorioFonte
import com.keyflow.bancada.resultado.Aparelho
import com.keyflow.bancada.resultado.AppInfo
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.MotorInfo
import com.keyflow.bancada.resultado.ResultadoCaso
import com.keyflow.bancada.resultado.EstadoCaso
import com.keyflow.bancada.resultado.execucaoDeExemplo
import java.io.File
import org.junit.Test

/** O plano de uma rodada, a estimativa de custo antes da primeira chamada (BON-01) e a de duração (BNC-04). */
class PlanosTest {

    private val corpus = CorpusLoader.carregar(DiretorioFonte(File("src/main/assets/bancada/v1")))
    private val aparelho = Aparelho("samsung", "SM-A325M", null, "arm64-v8a", 3700, 33)
    private val app = AppInfo("1.0.0", 1, "debug", null)
    private val local = ModeloEscolhido(origem = "local", modelo = "qwen3-0.6b-litertlm", nome = "Qwen3 0.6B", sha256 = "c5f39b41", backend = "CPU")
    private val nuvem = ModeloEscolhido(origem = "openai", modelo = "gpt-4.1", nome = "gpt-4.1", backend = "nuvem")

    private fun plano(modelo: ModeloEscolhido, testes: List<String>, perfil: Perfil) =
        Planos.montar(corpus, testes, perfil, modelo, aparelho, app, MotorInfo("litert-lm", "0.16.1"), "id-1", "2026-10-28T14:03:11Z")

    @Test
    fun `the plan carries who runs it, the corpus version and the profile`() {
        val p = plano(local, listOf("pontuacao", "json"), Perfil.COMPLETO)
        assertThat(p.origem).isEqualTo(Origem.LOCAL)
        assertThat(p.testes.map { it.def.id }).containsExactly("pontuacao", "json").inOrder()
        assertThat(p.testes.map { it.casos.size }).containsExactly(12, 10).inOrder()
        assertThat(p.cabecalho.corpus).isEqualTo(1)
        assertThat(p.cabecalho.perfil).isEqualTo("completo")
        assertThat(p.cabecalho.estado).isEqualTo(EstadoExecucao.EM_ANDAMENTO)
        assertThat(p.cabecalho.modelo.id).isEqualTo("qwen3-0.6b-litertlm")
        assertThat(p.cabecalho.modelo.sha256).isEqualTo("c5f39b41")
        assertThat(p.opcoesBase.temperature).isEqualTo(0.0)
        assertThat(p.opcoesBase.seed).isEqualTo(42)
    }

    @Test
    fun `the reduced profile runs the first two cases of each test`() {
        val p = plano(nuvem, listOf("pontuacao", "acoes"), Perfil.FUMACA)
        assertThat(p.origem).isEqualTo(Origem.ONLINE)
        assertThat(p.testes.map { it.casos.map { c -> c.id } }).containsExactly(listOf("pt-01", "en-01"), listOf("ac-01", "ac-17")).inOrder()
        // a fumaça de `acoes` tem uma chamada e uma recusa
        assertThat(p.testes[1].casos.map { it.esperado == null || it.esperado.toString() == "null" }).containsExactly(false, true).inOrder()
        assertThat(p.cabecalho.perfil).isEqualTo("fumaca")
    }

    @Test
    fun `the cloud model id carries the provider`() {
        assertThat(nuvem.id).isEqualTo("openai/gpt-4.1")
        assertThat(nuvem.online).isTrue()
        assertThat(local.id).isEqualTo("qwen3-0.6b-litertlm")
        assertThat(local.online).isFalse()
        assertThat(nuvem.info().origem).isEqualTo("openai")
    }

    @Test
    fun `an unknown test is refused`() {
        val erro = runCatching { plano(local, listOf("traducao"), Perfil.COMPLETO) }.exceptionOrNull()
        assertThat(erro).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the cost estimate counts calls, estimated input tokens and the output cap before any call`() {
        val completo = EstimativaDeRodada.de(plano(nuvem, listOf("pontuacao"), Perfil.COMPLETO))
        assertThat(completo.chamadas).isEqualTo(12)
        assertThat(completo.tetoDeTokensDeSaida).isEqualTo(12 * 96)
        assertThat(completo.tokensEntradaEstimados).isEqualTo(corpus.teste("pontuacao")!!.casos.sumOf { it.tokensEntrada })
        val reduzido = EstimativaDeRodada.de(plano(nuvem, listOf("pontuacao"), Perfil.FUMACA))
        assertThat(reduzido.chamadas).isEqualTo(2)
        assertThat(reduzido.tokensEntradaEstimados).isLessThan(completo.tokensEntradaEstimados)
    }

    @Test
    fun `cases bigger than the window are not counted as calls`() {
        val comJanela = nuvem.copy(contextTokens = 1_024)
        val e = EstimativaDeRodada.de(plano(comJanela, listOf("acoes", "contexto"), Perfil.COMPLETO))
        assertThat(e.casosQueNaoSeAplicam).isGreaterThan(0)
        assertThat(e.chamadas + e.casosQueNaoSeAplicam).isEqualTo(20 + 12)
        assertThat(e.chamadas).isEqualTo(6) // só os documentos curtos de `contexto` cabem em 1024 tokens
    }

    @Test
    fun `the duration estimate comes from the last complete run of the same model, and is null without one`() {
        val p = plano(local, listOf("pontuacao"), Perfil.COMPLETO)
        assertThat(EstimativaDeRodada.duracaoMs(p, emptyList())).isNull()
        val casos = listOf(
            ResultadoCaso("a", EstadoCaso.OK, nota = 1.0, totalMs = 2_000),
            ResultadoCaso("b", EstadoCaso.OK, nota = 1.0, totalMs = 4_000),
            ResultadoCaso("c", EstadoCaso.INVALIDO, causa = "REDE", totalMs = 99_999),
        )
        val anterior = execucaoDeExemplo().let { it.copy(testes = it.testes.map { t -> t.copy(casos = casos, n = 2) }) }
        // média de 3 s por caso que contou × 12 casos
        assertThat(EstimativaDeRodada.duracaoMs(p, listOf(anterior))).isEqualTo(36_000L)
        // incompleta não serve de base
        val incompleta = anterior.copy(estado = EstadoExecucao.INCOMPLETA)
        assertThat(EstimativaDeRodada.duracaoMs(p, listOf(incompleta))).isNull()
        // um teste que nunca rodou deixa a estimativa em branco
        assertThat(EstimativaDeRodada.duracaoMs(plano(local, listOf("pontuacao", "json"), Perfil.COMPLETO), listOf(anterior))).isNull()
    }
}
