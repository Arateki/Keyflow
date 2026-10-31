package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.orbe.AlvoDeSubstituicao
import com.keyflow.orbe.Interacao
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.orbe.SuporteDeInteracoes
import org.junit.Test

/** NTF-T2: o modelo da notificação, a configuração efetiva ("copiar") e os avisos de substituição. */
class ModeloTest {

    private val alternar = Settings(
        overlayRecordingMode = RecordingMode.TOGGLE,
        shortcut1Tap = OrbShortcutAction.VISION_MODE, // no Alternar o 1 toque da flutuante é do gesto nativo: não vale
        shortcut2Tap = OrbShortcutAction.SWITCH_IME,
        shortcut3Tap = OrbShortcutAction.NONE,
        orbSkin = "aero",
    )
    private val ptt = alternar.copy(overlayRecordingMode = RecordingMode.PUSH_TO_TALK)

    @Test
    fun `a notificacao suporta os tres toques e declara as outras quatro com motivo`() {
        val l = LugarDaOrbe.NOTIFICACAO
        for (i in listOf(Interacao.TOQUE_1, Interacao.TOQUE_2, Interacao.TOQUE_3)) {
            assertThat(SuporteDeInteracoes.suporta(l, i)).isTrue()
            assertThat(SuporteDeInteracoes.motivo(l, i)).isNull()
        }
        val fora = listOf(Interacao.SEGURAR, Interacao.ARRASTAR_CIMA, Interacao.ARRASTAR_LADO, Interacao.ARRASTAR_BAIXO)
        assertThat(SuporteDeInteracoes.naoSuportadas(l)).containsExactlyElementsIn(fora).inOrder()
        for (i in fora) {
            assertThat(SuporteDeInteracoes.motivo(l, i)).isNotEmpty()
            assertThat(destinoNaNotificacao(i)).isNotEmpty()
        }
        assertThat(destinoNaNotificacao(Interacao.TOQUE_1)).isNull()
    }

    @Test
    fun `a flutuante suporta todas e nao tem motivo`() {
        for (i in Interacao.entries) {
            assertThat(SuporteDeInteracoes.suporta(LugarDaOrbe.FLUTUANTE, i)).isTrue()
            assertThat(SuporteDeInteracoes.motivo(LugarDaOrbe.FLUTUANTE, i)).isNull()
        }
    }

    @Test
    fun `padroes da configuracao`() {
        val c = ConfigNotificacao()
        assertThat(c.mostrar).isTrue()
        assertThat(c.permitirComTelaBloqueada).isFalse()
        assertThat(c.copiarDaOrbePrincipal).isTrue()
        assertThat(c.skin).isEqualTo("atelie")
        assertThat(c.acao1Toque).isEqualTo(AcaoDaOrbe.GravarEnviar)
        assertThat(c.acao2Toques).isEqualTo(AcaoDaOrbe.RetrairExpandirTeclado)
        assertThat(c.acao3Toques).isEqualTo(AcaoDaOrbe.TrocarTeclado)
    }

    @Test
    fun `copiar no Alternar traz gravar e enviar no 1 toque e os atalhos 2 e 3 e a skin`() {
        val e = efetiva(ConfigNotificacao(skin = "glitch"), alternar)
        assertThat(e.copiado).isTrue()
        assertThat(e.skin).isEqualTo("aero")
        assertThat(e.acao1).isEqualTo(AcaoDaOrbe.GravarEnviar)
        assertThat(e.acao2).isEqualTo(AcaoDaOrbe.TrocarTeclado)
        assertThat(e.acao3).isEqualTo(AcaoDaOrbe.Nenhuma)
        assertThat(e.iniciaGravacao).isTrue()
    }

    @Test
    fun `copiar no PTT traz o shortcut1Tap inclusive Nenhuma sem inventar gravacao`() {
        val e = efetiva(ConfigNotificacao(), ptt.copy(shortcut1Tap = OrbShortcutAction.NONE))
        assertThat(e.acao1).isEqualTo(AcaoDaOrbe.Nenhuma)
        assertThat(e.iniciaGravacao).isFalse()
        assertThat(efetiva(ConfigNotificacao(), ptt).acao1).isEqualTo(AcaoDaOrbe.LerTela)
    }

    @Test
    fun `naoTem lista segurar e os tres arrastos`() {
        val e = efetiva(ConfigNotificacao(), alternar)
        assertThat(e.naoTem).containsExactly(
            Interacao.SEGURAR, Interacao.ARRASTAR_CIMA, Interacao.ARRASTAR_LADO, Interacao.ARRASTAR_BAIXO,
        ).inOrder()
    }

    @Test
    fun `sem copiar devolve os valores proprios e qualquer toque pode gravar ou nao`() {
        val c = ConfigNotificacao(
            copiarDaOrbePrincipal = false, skin = "pack:x",
            acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.GravarEnviar, acao3Toques = AcaoDaOrbe.Nenhuma,
        )
        val e = efetiva(c, alternar)
        assertThat(e.copiado).isFalse()
        assertThat(e.skin).isEqualTo("pack:x")
        assertThat(listOf(e.acao1, e.acao2, e.acao3))
            .containsExactly(AcaoDaOrbe.Nenhuma, AcaoDaOrbe.GravarEnviar, AcaoDaOrbe.Nenhuma).inOrder()
        assertThat(e.iniciaGravacao).isTrue()
        val so = efetiva(c.copy(acao2Toques = AcaoDaOrbe.LerTela), alternar)
        assertThat(so.iniciaGravacao).isFalse()
        assertThat(efetiva(c.copy(acao3Toques = AcaoDaOrbe.GravarEnviar, acao2Toques = AcaoDaOrbe.GravarEnviar), alternar).iniciaGravacao).isTrue()
    }

    @Test
    fun `desligar a copia grava como proprio o que estava copiado`() {
        val depois = desligarCopia(ConfigNotificacao(mostrar = false), ptt)
        assertThat(depois.copiarDaOrbePrincipal).isFalse()
        assertThat(depois.mostrar).isFalse()
        assertThat(depois.skin).isEqualTo("aero")
        assertThat(depois.acao1Toque).isEqualTo(AcaoDaOrbe.LerTela)
        assertThat(depois.acao2Toques).isEqualTo(AcaoDaOrbe.TrocarTeclado)
        assertThat(depois.acao3Toques).isEqualTo(AcaoDaOrbe.Nenhuma)
    }

    @Test
    fun `mapa dos atalhos`() {
        assertThat(acaoDoAtalho(OrbShortcutAction.NONE)).isEqualTo(AcaoDaOrbe.Nenhuma)
        assertThat(acaoDoAtalho(OrbShortcutAction.TOGGLE_KEYBOARD)).isEqualTo(AcaoDaOrbe.RetrairExpandirTeclado)
        assertThat(acaoDoAtalho(OrbShortcutAction.SWITCH_IME)).isEqualTo(AcaoDaOrbe.TrocarTeclado)
        assertThat(acaoDoAtalho(OrbShortcutAction.VISION_MODE)).isEqualTo(AcaoDaOrbe.LerTela)
    }

    @Test
    fun `aviso quando a edicao sobrescreve uma acao e nenhum quando era Nenhuma ou igual`() {
        val antes = ConfigNotificacao(acao3Toques = AcaoDaOrbe.Nenhuma)
        val depois = antes.copy(acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.RetrairExpandirTeclado, acao3Toques = AcaoDaOrbe.LerTela)
        val avisos = avisosDeSubstituicao(antes, depois)
        assertThat(avisos.map { it.alvo }).containsExactly(AlvoDeSubstituicao.ToqueDaOrbe(1))
        assertThat(avisos.single().acaoSubstituida).isEqualTo(AcaoDaOrbe.GravarEnviar)
        assertThat(avisos.single().acaoNova).isEqualTo(AcaoDaOrbe.Nenhuma)
        assertThat(avisosDeSubstituicao(antes, antes)).isEmpty()
    }
}
