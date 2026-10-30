package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.ShortcutOverflow
import com.keyflow.data.Settings
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.ui.config.shortcutActionLabel
import org.junit.Test

/** Ligar, desligar, excluir, limpar e o aviso de substituição (FLX-35, FLX-37, FLX-38, FLX-50, D17). */
class PoliticaGatilhosTest {

    private val a = Fluxo("a", "Fluxo A", etapas = listOf(Etapa.Ditar(), Etapa.Falar()))
    private val b = Fluxo("b", "Fluxo B", etapas = listOf(Etapa.Capturar, Etapa.Visao(), Etapa.Inserir))
    private val ruim = Fluxo("ruim", "Incompleto", etapas = listOf(Etapa.Ditar(), Etapa.Visao()))

    private val flut2 = Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2)
    private val flut3 = Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 3)
    private val notif2 = Superficie.Orbe(LugarDaOrbe.NOTIFICACAO, 2)
    private val barra = Superficie.Barra("b81f02")

    private fun config(gatilhos: List<Gatilho> = emptyList(), fluxos: List<Fluxo> = listOf(a, b, ruim)) = ConfigFluxos(fluxos, gatilhos)

    // ── ligar ───────────────────────────────────────────────────────────────

    @Test
    fun `ligar numa superficie livre acrescenta o gatilho`() {
        val c = PoliticaGatilhos.ligar(config(), "a", flut2)
        assertThat(c.gatilhos).containsExactly(Gatilho(flut2, "a"))
    }

    @Test
    fun `um fluxo pode ter varios atalhos`() {
        val c = PoliticaGatilhos.ligar(PoliticaGatilhos.ligar(PoliticaGatilhos.ligar(config(), "a", flut2), "a", notif2), "a", barra)
        assertThat(c.gatilhosDe("a")).hasSize(3)
    }

    @Test
    fun `ligar numa superficie ocupada tira do outro e nunca deixa dois`() {
        val c = PoliticaGatilhos.ligar(config(listOf(Gatilho(flut2, "a"), Gatilho(flut3, "a"))), "b", flut2)
        assertThat(c.gatilhos).containsExactly(Gatilho(flut2, "b"), Gatilho(flut3, "a")).inOrder()
        assertThat(c.gatilhos.count { it.superficie == flut2 }).isEqualTo(1)
    }

    @Test
    fun `ligar de novo o mesmo fluxo na mesma superficie nao muda nada`() {
        val inicial = config(listOf(Gatilho(flut2, "a"), Gatilho(barra, "b")))
        assertThat(PoliticaGatilhos.ligar(inicial, "a", flut2)).isEqualTo(inicial)
    }

    @Test
    fun `fluxo incompleto ou inexistente nao liga`() {
        val inicial = config(listOf(Gatilho(flut2, "a")))
        assertThat(PoliticaGatilhos.ligar(inicial, "ruim", flut3)).isSameInstanceAs(inicial)
        assertThat(PoliticaGatilhos.ligar(inicial, "nao-existe", flut3)).isSameInstanceAs(inicial)
        assertThat(PoliticaGatilhos.ligar(inicial, "ruim", flut2)).isSameInstanceAs(inicial) // nem tira o atual
        assertThat(PoliticaGatilhos.recusaDe(inicial, "ruim", flut3)).isEqualTo(RecusaDeLigar.FLUXO_INCOMPLETO)
        assertThat(PoliticaGatilhos.recusaDe(inicial, "nao-existe", flut3)).isEqualTo(RecusaDeLigar.FLUXO_INEXISTENTE)
        assertThat(PoliticaGatilhos.recusaDe(inicial, "b", flut3)).isNull()
    }

    @Test
    fun `no limite de gatilhos so se troca o dono de uma superficie, nao se cria outra`() {
        val cheio = config((1..LIMITES.GATILHOS).map { Gatilho(Superficie.Barra("b$it"), "a") })
        assertThat(PoliticaGatilhos.recusaDe(cheio, "a", flut2)).isEqualTo(RecusaDeLigar.LIMITE_DE_GATILHOS)
        assertThat(PoliticaGatilhos.ligar(cheio, "a", flut2)).isSameInstanceAs(cheio)
        val trocado = PoliticaGatilhos.ligar(cheio, "b", Superficie.Barra("b7"))
        assertThat(trocado.gatilhos).hasSize(LIMITES.GATILHOS)
        assertThat(trocado.gatilho(Superficie.Barra("b7"))!!.fluxoId).isEqualTo("b")
    }

    // ── desligar ────────────────────────────────────────────────────────────

    @Test
    fun `desligar tira so aquele gatilho`() {
        val c = PoliticaGatilhos.desligar(config(listOf(Gatilho(flut2, "a"), Gatilho(flut3, "a"), Gatilho(notif2, "b"))), flut2)
        assertThat(c.gatilhos).containsExactly(Gatilho(flut3, "a"), Gatilho(notif2, "b")).inOrder()
    }

    @Test
    fun `desligar uma superficie livre nao faz nada`() {
        val c = config(listOf(Gatilho(flut3, "a")))
        assertThat(PoliticaGatilhos.desligar(c, flut2)).isSameInstanceAs(c)
    }

    // ── excluir ─────────────────────────────────────────────────────────────

    @Test
    fun `excluir remove o fluxo e todos os gatilhos dele e devolve os botoes`() {
        val c = config(
            listOf(
                Gatilho(flut2, "a"), Gatilho(Superficie.Barra("x1"), "a"), Gatilho(flut3, "b"),
                Gatilho(notif2, "a"), Gatilho(Superficie.Barra("x2"), "a"), Gatilho(Superficie.Barra("y1"), "b"),
            ),
        )
        val e = PoliticaGatilhos.excluir(c, "a")
        assertThat(e.config.fluxos.map { it.id }).containsExactly("b", "ruim").inOrder()
        assertThat(e.config.gatilhos).containsExactly(Gatilho(flut3, "b"), Gatilho(Superficie.Barra("y1"), "b")).inOrder()
        assertThat(e.botoes).containsExactly("x1", "x2").inOrder()
    }

    @Test
    fun `excluir um fluxo sem atalhos so o tira da lista`() {
        val e = PoliticaGatilhos.excluir(config(listOf(Gatilho(flut2, "b"))), "a")
        assertThat(e.config.fluxos.map { it.id }).containsExactly("b", "ruim")
        assertThat(e.config.gatilhos).hasSize(1)
        assertThat(e.botoes).isEmpty()
    }

    @Test
    fun `excluir um id que nao existe nao muda a lista`() {
        val c = config(listOf(Gatilho(flut2, "a")))
        assertThat(PoliticaGatilhos.excluir(c, "zzz")).isEqualTo(Exclusao(c, emptyList()))
    }

    // ── limpar ──────────────────────────────────────────────────────────────

    @Test
    fun `limpar tira o gatilho de fluxo inexistente`() {
        val c = config(listOf(Gatilho(flut2, "a"), Gatilho(flut3, "sumiu"), Gatilho(notif2, "b")))
        assertThat(PoliticaGatilhos.limpar(c, null).gatilhos).containsExactly(Gatilho(flut2, "a"), Gatilho(notif2, "b")).inOrder()
    }

    @Test
    fun `limpar tira o gatilho de botao que nao existe mais na barra`() {
        val c = config(listOf(Gatilho(Superficie.Barra("x1"), "a"), Gatilho(Superficie.Barra("x2"), "a"), Gatilho(flut2, "b")))
        assertThat(PoliticaGatilhos.limpar(c, setOf("x2")).gatilhos).containsExactly(Gatilho(Superficie.Barra("x2"), "a"), Gatilho(flut2, "b")).inOrder()
        assertThat(PoliticaGatilhos.limpar(c, emptySet()).gatilhos).containsExactly(Gatilho(flut2, "b"))
    }

    @Test
    fun `sem saber os botoes da barra os gatilhos de barra ficam`() {
        val c = config(listOf(Gatilho(Superficie.Barra("x1"), "a"), Gatilho(Superficie.Barra("x2"), "sumiu")))
        assertThat(PoliticaGatilhos.limpar(c, null).gatilhos).containsExactly(Gatilho(Superficie.Barra("x1"), "a"))
    }

    @Test
    fun `limpar sem orfaos devolve a mesma config`() {
        val c = config(listOf(Gatilho(flut2, "a"), Gatilho(Superficie.Barra("x1"), "b")))
        assertThat(PoliticaGatilhos.limpar(c, setOf("x1"))).isSameInstanceAs(c)
    }

    // ── aviso de substituição ───────────────────────────────────────────────

    private val settings = Settings(shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD, shortcut3Tap = OrbShortcutAction.NONE)

    private fun politicaFlutuante(s: Settings = settings) = PoliticaDoLugar.daFlutuante(s, ::shortcutActionLabel)

    private fun politicaNotificacao(acao: Map<Int, String> = emptyMap()) =
        PoliticaDoLugar(ShortcutOverflow.NEAREST_DOWN, copiaDaPrincipal = false, temAcaoPropria = { it in acao }, rotuloDaAcaoPropria = { acao[it] })

    @Test
    fun `ligar num gesto com acao avisa o que sera substituido e o lugar`() {
        val aviso = PoliticaGatilhos.avisoDeSubstituicao(config(), flut2, "a", politicaFlutuante())
        assertThat(aviso).isEqualTo("Isto substitui “Retrair / expandir teclado” neste gesto da orbe flutuante.")
    }

    @Test
    fun `gesto livre ou acao igual nao geram aviso de perda`() {
        // 3 toques está em "Nenhuma"
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(config(), flut3, "a", politicaFlutuante())).isNull()
        // reatribuir o mesmo fluxo ao mesmo gesto não é substituição
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(config(listOf(Gatilho(flut2, "a"))), flut2, "a", politicaFlutuante())).isNull()
        // botão novo em espaço livre
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(config(), barra, "a", politicaFlutuante())).isNull()
    }

    @Test
    fun `superficie ocupada por outro fluxo nomeia o fluxo e nao a acao sombreada`() {
        val ocupada = config(listOf(Gatilho(flut2, "b")))
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(ocupada, flut2, "a", politicaFlutuante()))
            .isEqualTo("Isto substitui “Fluxo: Fluxo B” neste gesto da orbe flutuante.")
        val naBarra = config(listOf(Gatilho(barra, "b")))
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(naBarra, barra, "a", politicaFlutuante()))
            .isEqualTo("Isto substitui “Fluxo: Fluxo B” neste botão do teclado.")
    }

    @Test
    fun `o aviso de um padrao nao renomeado usa o nome exibido`() {
        val padrao = PadroesFluxo.semear()
        val c = ConfigFluxos(padrao, listOf(Gatilho(flut2, padrao[1].id)))
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(c, flut2, padrao[0].id, politicaFlutuante()))
            .isEqualTo("Isto substitui “Fluxo: Ler a tela e colar” neste gesto da orbe flutuante.")
    }

    @Test
    fun `o aviso nomeia o lugar da orbe`() {
        val p = politicaNotificacao(mapOf(2 to "Gravar", 3 to "Enviar"))
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(config(), notif2, "a", p))
            .isEqualTo("Isto substitui “Gravar” neste gesto da orbe da notificação.")
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(config(), Superficie.Orbe(LugarDaOrbe.TECLADO, 3), "a", p))
            .isEqualTo("Isto substitui “Enviar” neste gesto da orbe do teclado.")
    }

    @Test
    fun `gravar e uma acao como as outras, sem aviso proprio`() {
        // a ação própria do lugar pode ser gravar: o texto é o mesmo de qualquer outra ação
        val gravar = PoliticaGatilhos.avisoDeSubstituicao(config(), notif2, "a", politicaNotificacao(mapOf(2 to "Gravar / enviar")))
        val qualquer = PoliticaGatilhos.avisoDeSubstituicao(config(), notif2, "a", politicaNotificacao(mapOf(2 to "Trocar teclado")))
        assertThat(gravar!!.replace("Gravar / enviar", "X")).isEqualTo(qualquer!!.replace("Trocar teclado", "X"))
    }

    @Test
    fun `gatilho de fluxo que sumiu nao conta como ocupante`() {
        val c = config(listOf(Gatilho(flut2, "sumiu")))
        assertThat(PoliticaGatilhos.avisoDeSubstituicao(c, flut2, "a", politicaFlutuante()))
            .isEqualTo("Isto substitui “Retrair / expandir teclado” neste gesto da orbe flutuante.")
    }

    @Test
    fun `os tres lugares tem frases diferentes`() {
        val frases = LugarDaOrbe.entries.map { lugar ->
            val politica = PoliticaDoLugar(ShortcutOverflow.CANCEL, false, { true }, { "X" })
            PoliticaGatilhos.avisoDeSubstituicao(config(), Superficie.Orbe(lugar, 2), "a", politica)
        }
        assertThat(frases).containsNoDuplicates()
        assertThat(frases).doesNotContain(null)
    }
}
