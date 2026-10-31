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

    private fun botoes(barras: com.keyflow.core.shortcuts.BarsConfig, expandido: Boolean) =
        barras.barsFor(expandido).first().buttons.filterIsInstance<com.keyflow.core.shortcuts.BarButton.RunFlow>().map { it.botaoId }

    @Test
    fun `adiciona o botao ao fim da barra principal dos dois modos quando nao espelhadas`() {
        val base = com.keyflow.core.shortcuts.BarsConfig.DEFAULT.copy(mirrored = false)
        val novo = BotoesDeFluxo.comBotao(base, "abc123")
        assertThat(botoes(novo, false)).containsExactly("abc123")
        assertThat(botoes(novo, true)).containsExactly("abc123")
        assertThat(novo.collapsed.first().buttons.last()).isEqualTo(com.keyflow.core.shortcuts.BarButton.RunFlow("abc123"))
        // só a barra principal ([0]) ganha o botão
        assertThat(novo.collapsed.drop(1)).isEqualTo(base.collapsed.drop(1))
    }

    @Test
    fun `espelhadas so a lista do retraido recebe o botao`() {
        val base = com.keyflow.core.shortcuts.BarsConfig.DEFAULT.copy(mirrored = true)
        val novo = BotoesDeFluxo.comBotao(base, "abc123")
        assertThat(novo.expanded).isEqualTo(base.expanded)
        assertThat(botoes(novo, true)).containsExactly("abc123") // o espelhamento faz o expandido ler o retraído
    }

    @Test
    fun `semBotoes tira so os ids pedidos dos dois modos`() {
        val base = BotoesDeFluxo.comBotao(BotoesDeFluxo.comBotao(com.keyflow.core.shortcuts.BarsConfig.DEFAULT.copy(mirrored = false), "aaaaaa"), "bbbbbb")
        val sem = BotoesDeFluxo.semBotoes(base, setOf("aaaaaa"))
        assertThat(sem.botoesDeFluxo()).containsExactly("bbbbbb")
        assertThat(BotoesDeFluxo.semBotoes(base, emptySet())).isSameInstanceAs(base)
    }

    @Test
    fun `id de botao tem seis hexadecimais`() {
        repeat(20) { assertThat(Ids.botao()).matches("[0-9a-f]{6}") }
    }
}
