package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import com.keyflow.orbe.LugarDaOrbe
import org.junit.Assert.assertThrows
import org.junit.Test

/** Modelo de dados dos fluxos (FLX-13, FLX-33, FLX-57): tipos de entrada/saída das etapas, superfícies e limites. */
class ModeloTest {

    @Test
    fun `cada etapa declara a entrada e a saida da tabela da spec`() {
        assertThat(Etapa.Ditar().entrada).isEqualTo(Tipo.NADA)
        assertThat(Etapa.Ditar().saida).isEqualTo(Tipo.TEXTO)
        assertThat(Etapa.Capturar.entrada).isEqualTo(Tipo.NADA)
        assertThat(Etapa.Capturar.saida).isEqualTo(Tipo.IMAGEM)
        assertThat(Etapa.Visao().entrada).isEqualTo(Tipo.IMAGEM)
        assertThat(Etapa.Visao().saida).isEqualTo(Tipo.TEXTO)
        for (etapa in listOf(Etapa.Perguntar(), Etapa.Falar(), Etapa.Inserir, Etapa.Agente())) {
            assertThat(etapa.entrada).isEqualTo(Tipo.TEXTO)
            assertThat(etapa.saida).isEqualTo(Tipo.TEXTO)
        }
    }

    @Test
    fun `so as etapas que chamam um motor usam modelo`() {
        assertThat(listOf(Etapa.Ditar(), Etapa.Visao(), Etapa.Perguntar(), Etapa.Falar(), Etapa.Agente()).map { it.usaModelo })
            .containsExactly(true, true, true, true, true)
        assertThat(listOf(Etapa.Capturar, Etapa.Inserir, Etapa.Desconhecida("futura")).map { it.usaModelo })
            .containsExactly(false, false, false)
    }

    @Test
    fun `o modelo da etapa e o ModeloRef quando existe e nulo quando a etapa nao usa modelo`() {
        val ref = ModeloRef("OPENAI", "gpt-4o")
        assertThat(Etapa.Perguntar(modelo = ref).modelo).isEqualTo(ref)
        assertThat(Etapa.Perguntar().modelo).isNull()
        assertThat(Etapa.Capturar.modelo).isNull()
        assertThat(Etapa.Inserir.modelo).isNull()
    }

    @Test
    fun `cada etapa conhece o tipo e a chave do JSON`() {
        assertThat(Etapa.Ditar().tipo).isEqualTo(TipoEtapa.DITAR)
        assertThat(Etapa.Capturar.tipo).isEqualTo(TipoEtapa.CAPTURAR)
        assertThat(Etapa.Desconhecida("futura").tipo).isEqualTo(TipoEtapa.DESCONHECIDA)
        assertThat(TipoEtapa.entries.filter { it != TipoEtapa.DESCONHECIDA }.map { it.chave })
            .containsExactly("ditar", "capturar", "visao", "perguntar", "falar", "inserir", "agente").inOrder()
    }

    @Test
    fun `a mesma rajada em lugares diferentes sao superficies diferentes`() {
        val flutuante = Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2)
        val notificacao = Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 2)
        assertThat(flutuante).isNotEqualTo(notificacao)
        assertThat(flutuante).isEqualTo(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2))
        assertThat(Superficie.Orbe(LugarDaOrbe.TECLADO, 3)).isNotEqualTo(Superficie.Orbe(LugarDaOrbe.TECLADO, 2))
        assertThat(Superficie.Barra("b81f02")).isNotEqualTo(Superficie.Barra("b81f03"))
    }

    @Test
    fun `a orbe so aceita 2 ou 3 toques`() {
        assertThrows(IllegalArgumentException::class.java) { Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 1) }
        assertThrows(IllegalArgumentException::class.java) { Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 4) }
        Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2)
        Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 3)
    }

    @Test
    fun `os limites sao os da spec`() {
        assertThat(LIMITES.FLUXOS).isEqualTo(20)
        assertThat(LIMITES.ETAPAS).isEqualTo(8)
        assertThat(LIMITES.NOME).isEqualTo(40)
        assertThat(LIMITES.TEXTO_DA_ETAPA).isEqualTo(500)
        assertThat(LIMITES.GATILHOS).isEqualTo(64)
    }

    @Test
    fun `a config acha o fluxo por id e o gatilho por superficie`() {
        val a = Fluxo("a", "A", etapas = listOf(Etapa.Ditar()))
        val b = Fluxo("b", "B", etapas = listOf(Etapa.Capturar))
        val gatilho = Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "b")
        val config = ConfigFluxos(listOf(a, b), listOf(gatilho))
        assertThat(config.fluxo("b")).isSameInstanceAs(b)
        assertThat(config.fluxo("zzz")).isNull()
        assertThat(config.gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2))).isSameInstanceAs(gatilho)
        assertThat(config.gatilho(Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 2))).isNull()
        assertThat(config.gatilhosDe("b")).containsExactly(gatilho)
        assertThat(config.gatilhosDe("a")).isEmpty()
    }
}
