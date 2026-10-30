package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import com.keyflow.orbe.LugarDaOrbe
import org.junit.Test

/** Quais botões `RunFlow` da barra disparam (FLX-36, FLX-38): gatilho de barra para um fluxo que existe e é válido. */
class BotoesDeFluxoTest {
    private val valido = Fluxo("f1", "Ouvir e responder", etapas = listOf(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Inserir))
    private val incompleto = Fluxo("f2", "Só perguntar", etapas = listOf(Etapa.Perguntar()))

    @Test
    fun `only bar triggers of existing valid flows are enabled`() {
        val config = ConfigFluxos(
            fluxos = listOf(valido, incompleto),
            gatilhos = listOf(
                Gatilho(Superficie.Barra("aaaaaa"), "f1"),
                Gatilho(Superficie.Barra("bbbbbb"), "f2"), // incompleto
                Gatilho(Superficie.Barra("cccccc"), "sumiu"), // fluxo que não existe
                Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), "f1"), // não é de barra
            ),
        )
        assertThat(BotoesDeFluxo.validos(config)).containsExactly("aaaaaa", "f1")
        // O editor escreve o nome mesmo do fluxo incompleto; o que não tem fluxo fica sem nome.
        assertThat(BotoesDeFluxo.nomes(config)).containsExactly("aaaaaa", "Ouvir e responder", "bbbbbb", "Só perguntar")
    }

    @Test
    fun `no triggers means no enabled buttons`() {
        assertThat(BotoesDeFluxo.validos(ConfigFluxos(listOf(valido), emptyList()))).isEmpty()
    }
}
