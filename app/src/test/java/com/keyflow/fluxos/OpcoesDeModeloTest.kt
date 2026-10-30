package com.keyflow.fluxos

import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.core.TranscriptionSource
import com.keyflow.data.Settings
import org.junit.Assert.*
import org.junit.Test

class OpcoesDeModeloTest {
    private val settings = Settings(source = TranscriptionSource.LOCAL, reasoningSource = ReasoningSource.LOCAL, falaSource = FalaSource.SYSTEM)
    private val gemma = ModeloOferecido("gemma", "Gemma 4 E2B")
    private val qwen = ModeloOferecido("qwen", "Qwen3 0.6B")
    private val gpt = ModeloOferecido("gpt-4o", "gpt-4o")

    private fun opcoes(etapa: Etapa, instalados: List<ModeloOferecido> = emptyList(), conectados: Set<String> = emptySet(),
        catalogo: Map<String, List<ModeloOferecido>> = emptyMap()) = opcoesDeModelo(etapa, settings, instalados, conectados, catalogo)

    @Test fun primeiraEhDaAbaEListaSoInstaladosEConectadosNaOrdemDasAbas() {
        val o = opcoes(Etapa.Perguntar(), listOf(gemma, qwen), setOf("OPENAI"), mapOf("OPENAI" to listOf(gpt), "ANTHROPIC" to listOf(gpt)))
        assertTrue(o[0] is OpcaoDeModelo.DaAba && o[0].selecionada)
        val rotulos = o.drop(1).map { (it as? OpcaoDeModelo.Escolha)?.ref ?: (it as OpcaoDeModelo.Conectar).origemNome }
        assertEquals(
            listOf<Any>(ModeloRef("LOCAL", "gemma"), ModeloRef("LOCAL", "qwen"), ModeloRef("OPENAI", "gpt-4o"), "Anthropic", "Google", "Custom"),
            rotulos,
        )
        assertEquals(AbaDaEtapa.RACIOCINIO, (o.last() as OpcaoDeModelo.Conectar).aba)
    }

    @Test fun nuncaOfereceCreditsNemModeloNaoInstalado() {
        val o = opcoes(Etapa.Ditar(), instalados = emptyList())
        assertTrue(o.none { it is OpcaoDeModelo.Escolha && it.ref.origem == "LOCAL" })
        assertTrue(o.none { it is OpcaoDeModelo.Escolha && it.ref.origem == "CREDITS" })
        assertTrue(o.filterIsInstance<OpcaoDeModelo.Conectar>().map { it.origemNome }.containsAll(listOf("OpenAI", "Groq", "Custom")))
    }

    @Test fun vozDoSistemaEhSempreEscolhaSemModelo() {
        val o = opcoes(Etapa.Falar())
        val sistema = o.filterIsInstance<OpcaoDeModelo.Escolha>().single { it.ref.origem == "SYSTEM" }
        assertNull(sistema.ref.modelo); assertEquals(SeloDeModelo.NO_APARELHO, sistema.selo)
        assertTrue(o.filterIsInstance<OpcaoDeModelo.Conectar>().any { it.origemNome == "ElevenLabs" && it.aba == AbaDaEtapa.VOZ })
    }

    @Test fun etapaSelecionadaMarcaSoEla() {
        val etapa = Etapa.Perguntar(modelo = ModeloRef("OPENAI", "gpt-4o"))
        val o = opcoes(etapa, listOf(gemma), setOf("OPENAI"), mapOf("OPENAI" to listOf(gpt)))
        assertEquals(1, o.count { it.selecionada })
        assertEquals(ModeloRef("OPENAI", "gpt-4o"), (o.single { it.selecionada } as OpcaoDeModelo.Escolha).ref)
    }

    @Test fun modeloGravadoAusenteViraIndisponivelSemTrocar() {
        val etapa = Etapa.Perguntar(modelo = ModeloRef("LOCAL", "apagado"))
        val o = opcoes(etapa, listOf(gemma))
        assertEquals(OpcaoDeModelo.Indisponivel(ModeloRef("LOCAL", "apagado")), o.last())
        assertEquals(1, o.count { it.selecionada })
        assertFalse(o[0].selecionada)
        val provedorDesconectado = opcoes(Etapa.Visao(modelo = ModeloRef("ANTHROPIC", "x")), conectados = emptySet())
        assertTrue(provedorDesconectado.last() is OpcaoDeModelo.Indisponivel)
    }

    @Test fun daAbaMostraOEfetivoSemOModeloGravado() {
        val etapa = Etapa.Perguntar(modelo = ModeloRef("OPENAI", "gpt-4o"))
        val daAba = opcoes(etapa, listOf(gemma), setOf("OPENAI"), mapOf("OPENAI" to listOf(gpt)))[0] as OpcaoDeModelo.DaAba
        assertEquals("LOCAL", daAba.efetivo.origem)
        assertEquals(Onde.NoAparelho, daAba.efetivo.onde)
    }

    @Test fun semModeloNaoTemOpcoes() {
        assertTrue(opcoes(Etapa.Capturar).isEmpty()); assertTrue(opcoes(Etapa.Inserir).isEmpty())
        assertNull(abaDaEtapa(Etapa.Desconhecida("x")))
    }
}
