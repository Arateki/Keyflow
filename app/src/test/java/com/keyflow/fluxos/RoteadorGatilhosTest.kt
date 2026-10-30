package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.ShortcutOverflow
import com.keyflow.data.Settings
import com.keyflow.fluxos.Resolucao.AcaoDoLugar
import com.keyflow.fluxos.Resolucao.AcaoDoSettings
import com.keyflow.fluxos.Resolucao.Incompleto
import com.keyflow.fluxos.Resolucao.Nada
import com.keyflow.fluxos.Resolucao.Rodar
import com.keyflow.orbe.LugarDaOrbe
import org.junit.Test

/** Roteamento da rajada de toques para um fluxo ou para a ação do lugar (FLX-33..35, FLX-57, FLX-60). */
class RoteadorGatilhosTest {

    private val valido = Fluxo("ok", "Válido", etapas = listOf(Etapa.Ditar(), Etapa.Falar()))
    private val outro = Fluxo("ok2", "Outro válido", etapas = listOf(Etapa.Capturar, Etapa.Visao(), Etapa.Inserir))
    private val incompleto = Fluxo("ruim", "Incompleto", etapas = listOf(Etapa.Ditar(), Etapa.Visao()))

    private val settings = Settings(
        shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD,
        shortcut3Tap = OrbShortcutAction.SWITCH_IME,
        shortcutOverflow = ShortcutOverflow.NEAREST_DOWN,
    )

    private fun orbe(lugar: LugarDaOrbe, toques: Int, fluxoId: String) = Gatilho(Superficie.Orbe(lugar, toques), fluxoId)

    private fun config(vararg gatilhos: Gatilho) = ConfigFluxos(listOf(valido, outro, incompleto), gatilhos.toList())

    private fun flutuante(config: ConfigFluxos, toques: Int, s: Settings = settings) =
        RoteadorGatilhos.resolverToques(LugarDaOrbe.FLUTUANTE, toques, config, PoliticaDoLugar.daFlutuante(s), s)

    private fun doLugar(
        lugar: LugarDaOrbe,
        toques: Int,
        config: ConfigFluxos,
        copia: Boolean = false,
        overflow: ShortcutOverflow = ShortcutOverflow.NEAREST_DOWN,
        acaoPropria: Set<Int> = emptySet(),
    ) = RoteadorGatilhos.resolverToques(lugar, toques, config, PoliticaDoLugar(overflow, copia, { it in acaoPropria }), settings)

    // ── a orbe flutuante ────────────────────────────────────────────────────

    @Test
    fun `2 e 3 toques ligados rodam o fluxo e nao a acao do Settings`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"), orbe(LugarDaOrbe.FLUTUANTE, 3, "ok2"))
        assertThat(flutuante(c, 2)).isEqualTo(Rodar(valido, 2))
        assertThat(flutuante(c, 3)).isEqualTo(Rodar(outro, 3))
    }

    @Test
    fun `sem gatilho a acao do Settings roda`() {
        val c = config()
        assertThat(flutuante(c, 2)).isEqualTo(AcaoDoSettings(OrbShortcutAction.TOGGLE_KEYBOARD))
        assertThat(flutuante(c, 3)).isEqualTo(AcaoDoSettings(OrbShortcutAction.SWITCH_IME))
    }

    @Test
    fun `ligar so o 2 toques nao muda o 3 toques`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"))
        assertThat(flutuante(c, 3)).isEqualTo(AcaoDoSettings(OrbShortcutAction.SWITCH_IME))
    }

    @Test
    fun `um toque nunca e gatilho, vai para a acao do Settings`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"), orbe(LugarDaOrbe.FLUTUANTE, 3, "ok"))
        val s = settings.copy(shortcut1Tap = OrbShortcutAction.VISION_MODE)
        assertThat(flutuante(c, 1, s)).isEqualTo(AcaoDoSettings(OrbShortcutAction.VISION_MODE))
        assertThat(flutuante(c, 0, s)).isEqualTo(AcaoDoSettings(OrbShortcutAction.NONE))
        assertThat(flutuante(c, -3, s)).isEqualTo(AcaoDoSettings(OrbShortcutAction.NONE))
    }

    @Test
    fun `4 toques com NEAREST_DOWN cai no 3 ligado a fluxo`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 3, "ok"))
        assertThat(flutuante(c, 4)).isEqualTo(Rodar(valido, 3))
        assertThat(flutuante(c, 9)).isEqualTo(Rodar(valido, 3))
    }

    @Test
    fun `4 toques cai no 2 ligado a fluxo se o 3 esta livre e sem acao`() {
        val s = settings.copy(shortcut3Tap = OrbShortcutAction.NONE)
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok2"))
        assertThat(flutuante(c, 4, s)).isEqualTo(Rodar(outro, 2))
    }

    @Test
    fun `4 toques cai na acao do 3 se ela existe, mesmo com o 2 ligado a fluxo`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"))
        assertThat(flutuante(c, 4)).isEqualTo(AcaoDoSettings(OrbShortcutAction.SWITCH_IME))
    }

    @Test
    fun `4 toques com os dois sem gatilho e sem acao nao faz nada`() {
        val s = settings.copy(shortcut2Tap = OrbShortcutAction.NONE, shortcut3Tap = OrbShortcutAction.NONE)
        assertThat(flutuante(config(), 4, s)).isEqualTo(Nada)
    }

    @Test
    fun `um gesto ligado a fluxo incompleto conta como ativo no overflow`() {
        val s = settings.copy(shortcut3Tap = OrbShortcutAction.NONE)
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 3, "ruim"), orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"))
        assertThat(flutuante(c, 4, s)).isEqualTo(Incompleto(3))
    }

    @Test
    fun `com CANCEL mais de 3 toques nao faz nada, ligado ou nao`() {
        val s = settings.copy(shortcutOverflow = ShortcutOverflow.CANCEL)
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 3, "ok"), orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"))
        assertThat(flutuante(c, 4, s)).isEqualTo(Nada)
        assertThat(flutuante(c, 7, s)).isEqualTo(Nada)
        assertThat(flutuante(c, 3, s)).isEqualTo(Rodar(valido, 3)) // até 3 toques o overflow não conta
    }

    @Test
    fun `fluxo invalido vira Incompleto e a acao sombreada nao roda`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ruim"))
        assertThat(flutuante(c, 2)).isEqualTo(Incompleto(2))
    }

    @Test
    fun `gatilho de fluxo que sumiu tambem e Incompleto e nao deixa a acao correr`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "sumiu"))
        assertThat(flutuante(c, 2)).isEqualTo(Incompleto(2))
    }

    @Test
    fun `sem nenhum gatilho o roteador concorda com Settings resolveShortcut`() {
        for (overflow in ShortcutOverflow.entries) for (a2 in OrbShortcutAction.entries) for (a3 in OrbShortcutAction.entries) {
            val s = Settings(shortcut2Tap = a2, shortcut3Tap = a3, shortcutOverflow = overflow, shortcut1Tap = OrbShortcutAction.TOGGLE_KEYBOARD)
            for (toques in -1..8) {
                val esperado = s.resolveShortcut(toques)
                val acao = when (val r = flutuante(config(), toques, s)) {
                    is AcaoDoSettings -> r.acao
                    Nada -> OrbShortcutAction.NONE
                    else -> error("sem gatilho não pode dar $r")
                }
                assertThat(acao).isEqualTo(esperado)
            }
        }
    }

    // ── os três lugares ─────────────────────────────────────────────────────

    @Test
    fun `o mesmo gesto em lugares diferentes sao gatilhos diferentes`() {
        val c = config(orbe(LugarDaOrbe.NOTIFICACAO, 2, "ok"), orbe(LugarDaOrbe.FLUTUANTE, 2, "ok2"), orbe(LugarDaOrbe.TECLADO, 2, "ok2"))
        assertThat(flutuante(c, 2)).isEqualTo(Rodar(outro, 2))
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 2, c)).isEqualTo(Rodar(valido, 2))
        assertThat(doLugar(LugarDaOrbe.TECLADO, 2, c)).isEqualTo(Rodar(outro, 2))
    }

    @Test
    fun `gatilho so na notificacao nao mexe no 2 toques da flutuante`() {
        val c = config(orbe(LugarDaOrbe.NOTIFICACAO, 2, "ok"))
        assertThat(flutuante(c, 2)).isEqualTo(AcaoDoSettings(OrbShortcutAction.TOGGLE_KEYBOARD))
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 2, c)).isEqualTo(Rodar(valido, 2))
    }

    @Test
    fun `notificacao e teclado sem gatilho devolvem a acao do lugar`() {
        for (lugar in listOf(LugarDaOrbe.NOTIFICACAO, LugarDaOrbe.TECLADO)) {
            val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok")) // o da flutuante não vale sem "copiar"
            assertThat(doLugar(lugar, 2, c)).isEqualTo(AcaoDoLugar(2))
            assertThat(doLugar(lugar, 3, c)).isEqualTo(AcaoDoLugar(3))
            assertThat(doLugar(lugar, 1, c)).isEqualTo(AcaoDoLugar(1))
        }
    }

    @Test
    fun `fluxo invalido na notificacao e Incompleto`() {
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 3, config(orbe(LugarDaOrbe.NOTIFICACAO, 3, "ruim")))).isEqualTo(Incompleto(3))
    }

    @Test
    fun `overflow nos outros lugares usa a acao propria do lugar e o overflow da principal`() {
        val c = config(orbe(LugarDaOrbe.NOTIFICACAO, 2, "ok"))
        // 3 toques sem gatilho nem ação própria: cai no 2, que tem gatilho
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 5, c)).isEqualTo(Rodar(valido, 2))
        // 3 toques com ação própria: cai no 3, que é a ação do lugar
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 5, c, acaoPropria = setOf(3))).isEqualTo(AcaoDoLugar(3))
        // CANCEL
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 5, c, overflow = ShortcutOverflow.CANCEL, acaoPropria = setOf(3))).isEqualTo(Nada)
        // nada ativo
        assertThat(doLugar(LugarDaOrbe.TECLADO, 5, config())).isEqualTo(Nada)
    }

    // ── copiar a configuração da orbe principal ─────────────────────────────

    @Test
    fun `com copiar ligado os gatilhos lidos sao os da flutuante`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"), orbe(LugarDaOrbe.NOTIFICACAO, 2, "ok2"), orbe(LugarDaOrbe.NOTIFICACAO, 3, "ok2"))
        // copiando: o 2 toques da flutuante vale; o gatilho próprio da notificação é ignorado
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 2, c, copia = true)).isEqualTo(Rodar(valido, 2))
        // o 3 toques da flutuante está livre: copiar não traz o gatilho próprio da notificação
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 3, c, copia = true)).isEqualTo(AcaoDoLugar(3))
        // sem copiar, valem os próprios
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 2, c, copia = false)).isEqualTo(Rodar(outro, 2))
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 3, c, copia = false)).isEqualTo(Rodar(outro, 3))
    }

    @Test
    fun `desligar copiar reativa o gatilho proprio e a lista de gatilhos nunca muda`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 3, "ok"), orbe(LugarDaOrbe.TECLADO, 3, "ok2"))
        val antes = c.gatilhos
        val sequencia = listOf(false, true, false, true, true, false).map { copia -> doLugar(LugarDaOrbe.TECLADO, 3, c, copia = copia) }
        assertThat(sequencia).containsExactly(
            Rodar(outro, 3), Rodar(valido, 3), Rodar(outro, 3), Rodar(valido, 3), Rodar(valido, 3), Rodar(outro, 3),
        ).inOrder()
        assertThat(c.gatilhos).isEqualTo(antes)
    }

    @Test
    fun `copiar nao afeta a propria flutuante`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"))
        val politica = PoliticaDoLugar.daFlutuante(settings).copy(copiaDaPrincipal = true) // inofensivo na flutuante
        assertThat(RoteadorGatilhos.resolverToques(LugarDaOrbe.FLUTUANTE, 2, c, politica, settings)).isEqualTo(Rodar(valido, 2))
    }

    @Test
    fun `no overflow, copiar conta os gatilhos da flutuante`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 3, "ok"), orbe(LugarDaOrbe.NOTIFICACAO, 2, "ok2"))
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 6, c, copia = true)).isEqualTo(Rodar(valido, 3))
        assertThat(doLugar(LugarDaOrbe.NOTIFICACAO, 6, c, copia = false)).isEqualTo(Rodar(outro, 2))
    }

    // ── acaoEfetivaDoGesto (Sobrepor e Tour) ────────────────────────────────

    @Test
    fun `a acao efetiva e o fluxo ligado ou a acao do Settings`() {
        val c = config(orbe(LugarDaOrbe.FLUTUANTE, 2, "ok"), orbe(LugarDaOrbe.FLUTUANTE, 3, "ruim"), orbe(LugarDaOrbe.NOTIFICACAO, 3, "ok2"))
        assertThat(RoteadorGatilhos.acaoEfetivaDoGesto(2, settings, c)).isEqualTo(AcaoEfetiva.DeFluxo("ok", "Válido", valido = true))
        assertThat(RoteadorGatilhos.acaoEfetivaDoGesto(3, settings, c)).isEqualTo(AcaoEfetiva.DeFluxo("ruim", "Incompleto", valido = false))
        assertThat(RoteadorGatilhos.acaoEfetivaDoGesto(1, settings, c)).isEqualTo(AcaoEfetiva.DaConfig(settings.shortcut1Tap))
        // o gatilho da notificação não aparece na descrição do gesto da flutuante
        assertThat(RoteadorGatilhos.acaoEfetivaDoGesto(3, settings, config(orbe(LugarDaOrbe.NOTIFICACAO, 3, "ok2"))))
            .isEqualTo(AcaoEfetiva.DaConfig(OrbShortcutAction.SWITCH_IME))
        assertThat(RoteadorGatilhos.acaoEfetivaDoGesto(2, settings, config(orbe(LugarDaOrbe.FLUTUANTE, 2, "sumiu"))))
            .isEqualTo(AcaoEfetiva.DeFluxo("sumiu", null, valido = false))
    }
}
