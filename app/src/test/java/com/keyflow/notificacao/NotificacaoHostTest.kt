package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.ShortcutOverflow
import com.keyflow.data.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import org.junit.Test

/** NTF-T10 (lado puro): o host consulta o `PortaoDeToque` antes de executar qualquer `Comando` e age conforme o design §5 (NTF-09..11, 14, 16, 48, 49). */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificacaoHostTest {
    private val escopo = TestScope()
    private var config = ConfigNotificacao()
    private var settings = Settings(shortcutOverflow = ShortcutOverflow.NEAREST_DOWN, shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD, shortcut3Tap = OrbShortcutAction.SWITCH_IME)
    private var bloqueada = false
    private var estado = ButtonState.IDLE_READY
    private var visao = false
    private var teclado = true
    private var sobreposicao = true
    private val chamadas = mutableListOf<String>()
    private val avisos = mutableListOf<AvisoDaNotificacao>()
    private var vibracoes = 0

    private val acoes = object : AcoesDaNotificacao {
        override fun gravarEnviar() { chamadas += "gravarEnviar" }
        override fun pausarRetomar() { chamadas += "pausarRetomar" }
        override fun descartar() { chamadas += "descartar" }
        override fun desfazer() { chamadas += "desfazer" }
        override fun executar(acao: AcaoDaOrbe) { chamadas += "executar:$acao" }
        override fun rodarFluxo(fluxoId: String) { chamadas += "fluxo:$fluxoId" }
    }

    private fun host() = NotificacaoHost(
        escopo = escopo, config = { config }, settings = { settings }, bloqueada = { bloqueada }, relogio = { escopo.currentTime },
        estado = { estado }, visaoAtiva = { visao }, tecladoVisivel = { teclado }, podeAbrirActivity = { sobreposicao },
        acoes = acoes, aviso = { avisos += it }, vibrar = { vibracoes++ },
    )

    private fun toques(h: NotificacaoHost, n: Int, passo: Long = 50) {
        repeat(n) { h.aoTocarNaOrbe(); escopo.advanceTimeBy(passo) }
        escopo.advanceUntilIdle()
    }

    @Test
    fun `1 toque grava e envia e o 2 e o 3 executam as acoes deles`() {
        val h = host()
        toques(h, 1)
        assertThat(chamadas).containsExactly("gravarEnviar")
        chamadas.clear(); toques(h, 2)
        assertThat(chamadas).containsExactly("executar:${AcaoDaOrbe.RetrairExpandirTeclado}")
        chamadas.clear(); toques(h, 3)
        assertThat(chamadas).containsExactly("executar:${AcaoDaOrbe.TrocarTeclado}")
    }

    @Test
    fun `a rajada so assenta depois da janela e conta uma vez`() {
        val h = host()
        h.aoTocarNaOrbe(); escopo.advanceTimeBy(100)
        h.aoTocarNaOrbe(); escopo.advanceTimeBy(249)
        assertThat(chamadas).isEmpty()
        assertThat(h.toquesPendentes).isEqualTo(2)
        escopo.advanceUntilIdle()
        assertThat(chamadas).hasSize(1)
        assertThat(h.toquesPendentes).isEqualTo(0)
    }

    @Test
    fun `remapear GravarEnviar para 2 ou 3 toques grava e o 1 toque em Nenhuma nao faz nada`() {
        config = ConfigNotificacao(copiarDaOrbePrincipal = false, acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.GravarEnviar, acao3Toques = AcaoDaOrbe.Nenhuma)
        val h = host()
        toques(h, 1)
        assertThat(chamadas).isEmpty()
        toques(h, 2)
        assertThat(chamadas).containsExactly("gravarEnviar")
        chamadas.clear(); toques(h, 3)
        assertThat(chamadas).isEmpty()
    }

    @Test
    fun `5 toques cai conforme o overflow`() {
        val h = host()
        toques(h, 5, passo = 40)
        assertThat(chamadas).containsExactly("executar:${AcaoDaOrbe.TrocarTeclado}")
        chamadas.clear(); settings = settings.copy(shortcutOverflow = ShortcutOverflow.CANCEL)
        toques(h, 5, passo = 40)
        assertThat(chamadas).isEmpty()
    }

    // ── o portão da tela bloqueada ───────────────────────────────────────────

    @Test
    fun `bloqueada com a opcao desligada nenhum toque e contado e nada age`() {
        bloqueada = true
        val h = host()
        toques(h, 1)
        assertThat(chamadas).isEmpty()
        assertThat(h.toquesPendentes).isEqualTo(0)
        assertThat(avisos).containsExactly(AvisoDaNotificacao.BLOQUEADA)
        assertThat(vibracoes).isEqualTo(1)
    }

    @Test
    fun `bloqueada com a opcao desligada nenhum botao age`() {
        bloqueada = true
        estado = ButtonState.RECORDING
        val h = host()
        for (b in Botao.entries) h.aoTocarNoBotao(b)
        assertThat(chamadas).isEmpty()
        assertThat(avisos).containsExactly(*Array(Botao.entries.size) { AvisoDaNotificacao.BLOQUEADA })
    }

    @Test
    fun `bloqueada com a opcao ligada a orbe e os botoes agem`() {
        bloqueada = true
        config = ConfigNotificacao(permitirComTelaBloqueada = true)
        estado = ButtonState.RECORDING
        val h = host()
        toques(h, 1)
        h.aoTocarNoBotao(Botao.PAUSAR)
        assertThat(chamadas).containsExactly("gravarEnviar", "pausarRetomar").inOrder()
        assertThat(avisos).isEmpty()
    }

    @Test
    fun `bloqueada e ligada, Ler a tela recusa com precisa do aparelho desbloqueado`() {
        bloqueada = true
        config = ConfigNotificacao(permitirComTelaBloqueada = true, copiarDaOrbePrincipal = false, acao2Toques = AcaoDaOrbe.LerTela)
        val h = host()
        toques(h, 2)
        assertThat(chamadas).isEmpty()
        assertThat(avisos).containsExactly(AvisoDaNotificacao.PRECISA_DESBLOQUEIO)
        // trocar o teclado (também Activity) idem; já o que não abre Activity (retrair) age
        avisos.clear()
        toques(h, 3)
        assertThat(avisos).containsExactly(AvisoDaNotificacao.PRECISA_DESBLOQUEIO)
    }

    @Test
    fun `sem sobreposicao as acoes de Activity avisam e nao executam`() {
        sobreposicao = false
        val h = host()
        toques(h, 3)
        assertThat(chamadas).isEmpty()
        assertThat(avisos).containsExactly(AvisoDaNotificacao.SEM_SOBREPOSICAO)
        toques(h, 2) // retrair não abre Activity
        assertThat(chamadas).containsExactly("executar:${AcaoDaOrbe.RetrairExpandirTeclado}")
    }

    @Test
    fun `o portao e lido a cada toque, nao guardado`() {
        val h = host()
        bloqueada = true
        toques(h, 1)
        assertThat(chamadas).isEmpty()
        bloqueada = false
        toques(h, 1)
        assertThat(chamadas).containsExactly("gravarEnviar")
    }

    // ── o portao vale no momento de agir ─────────────────────────────────────

    @Test
    fun `a tela bloqueia dentro da janela da rajada e o GravarEnviar nao executa`() {
        val h = host()
        h.aoTocarNaOrbe()
        escopo.advanceTimeBy(100)
        bloqueada = true
        escopo.advanceUntilIdle()
        assertThat(chamadas).isEmpty()
        assertThat(avisos).containsExactly(AvisoDaNotificacao.BLOQUEADA)
        assertThat(h.toquesPendentes).isEqualTo(0)
    }

    @Test
    fun `a tela bloqueia dentro da janela e a acao sem Activity tambem nao executa`() {
        val h = host()
        h.aoTocarNaOrbe(); escopo.advanceTimeBy(60)
        h.aoTocarNaOrbe(); escopo.advanceTimeBy(40)
        bloqueada = true
        escopo.advanceUntilIdle()
        assertThat(chamadas).isEmpty() // 2 toques = retrair/expandir o teclado
        assertThat(avisos).containsExactly(AvisoDaNotificacao.BLOQUEADA)
    }

    @Test
    fun `controle - com a opcao ligada, bloquear dentro da janela nao impede`() {
        config = ConfigNotificacao(permitirComTelaBloqueada = true)
        val h = host()
        h.aoTocarNaOrbe()
        escopo.advanceTimeBy(100)
        bloqueada = true
        escopo.advanceUntilIdle()
        assertThat(chamadas).containsExactly("gravarEnviar")
        assertThat(avisos).isEmpty()
    }

    @Test
    fun `controle - bloqueada antes de tocar ja recusava`() {
        bloqueada = true
        val h = host()
        h.aoTocarNaOrbe()
        escopo.advanceUntilIdle()
        assertThat(chamadas).isEmpty()
    }

    @Test
    fun `com a opcao ligada, a Activity ainda pede o desbloqueio ao agir`() {
        config = ConfigNotificacao(permitirComTelaBloqueada = true)
        val h = host()
        repeat(3) { h.aoTocarNaOrbe(); escopo.advanceTimeBy(30) }
        bloqueada = true
        escopo.advanceUntilIdle()
        assertThat(chamadas).isEmpty()
        assertThat(avisos).containsExactly(AvisoDaNotificacao.PRECISA_DESBLOQUEIO)
    }

    // ── os botoes ────────────────────────────────────────────────────────────

    @Test
    fun `cada botao chama o tratador dele e nao entra na rajada`() {
        val h = host()
        estado = ButtonState.RECORDING; h.aoTocarNoBotao(Botao.PAUSAR)
        estado = ButtonState.PAUSED; h.aoTocarNoBotao(Botao.RETOMAR); h.aoTocarNoBotao(Botao.DESCARTAR)
        estado = ButtonState.CANCELLING; h.aoTocarNoBotao(Botao.DESFAZER)
        visao = true; h.aoTocarNoBotao(Botao.ENCERRAR)
        assertThat(chamadas).containsExactly("pausarRetomar", "pausarRetomar", "descartar", "desfazer", "descartar").inOrder()
        assertThat(h.toquesPendentes).isEqualTo(0)
        escopo.advanceUntilIdle()
        assertThat(chamadas).hasSize(5)
    }

    @Test
    fun `um botao que chega depois de o estado mudar e ignorado sem erro`() {
        val h = host()
        estado = ButtonState.PAUSED
        h.aoTocarNoBotao(Botao.PAUSAR) // o botão era do estado gravando
        estado = ButtonState.IDLE_READY
        h.aoTocarNoBotao(Botao.DESCARTAR)
        h.aoTocarNoBotao(Botao.DESFAZER)
        assertThat(chamadas).isEmpty()
        assertThat(avisos).isEmpty()
    }

    @Test
    fun `com a Visao ativa so GravarEnviar age na orbe`() {
        visao = true
        val h = host()
        toques(h, 2)
        assertThat(chamadas).isEmpty()
        toques(h, 1)
        assertThat(chamadas).containsExactly("gravarEnviar")
    }

    @Test
    fun `trocar a configuracao vale no toque seguinte`() {
        val h = host()
        toques(h, 1)
        config = config.copy(copiarDaOrbePrincipal = false, acao1Toque = AcaoDaOrbe.Nenhuma)
        chamadas.clear()
        toques(h, 1)
        assertThat(chamadas).isEmpty()
    }
}
