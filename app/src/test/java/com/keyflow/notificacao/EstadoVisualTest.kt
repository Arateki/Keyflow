package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.data.Settings
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.i18n.tr
import org.junit.After
import org.junit.Test

/** NTF-T4: a tabela de estados do design §6, uma linha por teste, com a descrição traduzida. */
class EstadoVisualTest {

    private val grava = efetiva(ConfigNotificacao(), Settings())
    private val semGravar = efetiva(ConfigNotificacao(copiarDaOrbePrincipal = false, acao1Toque = AcaoDaOrbe.Nenhuma, acao2Toques = AcaoDaOrbe.Nenhuma, acao3Toques = AcaoDaOrbe.Nenhuma), Settings())

    @After
    fun volta() { AppLocale.language = AppLanguage.PT }

    private fun v(e: ButtonState, visao: Boolean = false, ef: ConfigEfetiva = grava) = EstadoVisualDaNotificacao.para(e, visao, ef)

    private fun EstadoVisual.pt() = descricaoTraduzida { it }

    @Test
    fun `ocioso`() {
        for (e in listOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD)) {
            val x = v(e)
            assertThat(x.aparencia).isEqualTo(AparenciaDaOrbe.IDLE)
            assertThat(x.glifo).isNull()
            assertThat(x.cronometro).isEqualTo(CronometroDaNotificacao.OCULTO)
            assertThat(x.pt()).isEqualTo("KeyFlow pronto. Toque para gravar")
        }
    }

    @Test
    fun `gravando`() {
        val x = v(ButtonState.RECORDING)
        assertThat(x.aparencia).isEqualTo(AparenciaDaOrbe.LISTENING)
        assertThat(x.glifo).isNull()
        assertThat(x.cronometro).isEqualTo(CronometroDaNotificacao.CORRENDO_NO_CENTRO)
        assertThat(x.pt()).isEqualTo("KeyFlow gravando. Toque para enviar")
    }

    @Test
    fun `pausado`() {
        val x = v(ButtonState.PAUSED)
        assertThat(x.aparencia).isEqualTo(AparenciaDaOrbe.PAUSED)
        assertThat(x.glifo).isEqualTo(GlifoDaOrbe.PAUSA)
        assertThat(x.cronometro).isEqualTo(CronometroDaNotificacao.PARADO_MIUDO)
        assertThat(x.pt()).isEqualTo("KeyFlow pausado. Toque para enviar")
    }

    @Test
    fun `transcrevendo`() {
        val x = v(ButtonState.SENDING)
        assertThat(x.aparencia).isEqualTo(AparenciaDaOrbe.TRANSCRIBING)
        assertThat(x.glifo).isNull()
        assertThat(x.cronometro).isEqualTo(CronometroDaNotificacao.OCULTO)
        assertThat(x.pt()).isEqualTo("KeyFlow transcrevendo")
    }

    @Test
    fun `cancelando`() {
        val x = v(ButtonState.CANCELLING)
        assertThat(x.aparencia).isEqualTo(AparenciaDaOrbe.PAUSED)
        assertThat(x.glifo).isEqualTo(GlifoDaOrbe.LIXEIRA)
        assertThat(x.cronometro).isEqualTo(CronometroDaNotificacao.PARADO_MIUDO)
        assertThat(x.pt()).isEqualTo("Gravação descartada. Toque para descartar agora")
    }

    @Test
    fun `erro`() {
        val x = v(ButtonState.ERROR)
        assertThat(x.aparencia).isEqualTo(AparenciaDaOrbe.IDLE)
        assertThat(x.glifo).isEqualTo(GlifoDaOrbe.X)
        assertThat(x.cronometro).isEqualTo(CronometroDaNotificacao.OCULTO)
        assertThat(x.pt()).isEqualTo("KeyFlow com erro. Toque para dispensar")
    }

    @Test
    fun `pendente`() {
        val x = v(ButtonState.PENDING_PASTE)
        assertThat(x.aparencia).isEqualTo(AparenciaDaOrbe.IDLE)
        assertThat(x.glifo).isNull()
        assertThat(x.pt()).isEqualTo("Texto pendente. Toque para colar")
    }

    @Test
    fun `Visao ativa vale mais que o estado`() {
        for (e in ButtonState.entries) {
            val x = v(e, visao = true)
            assertThat(x.aparencia).isEqualTo(AparenciaDaOrbe.IDLE)
            assertThat(x.glifo).isEqualTo(GlifoDaOrbe.OLHO)
            assertThat(x.cronometro).isEqualTo(CronometroDaNotificacao.OCULTO)
            assertThat(x.pt()).isEqualTo("Ler a tela em andamento. Toque para encerrar")
        }
    }

    @Test
    fun `a descricao nunca promete o que o toque nao faz`() {
        assertThat(v(ButtonState.IDLE_READY, ef = semGravar).pt()).isEqualTo("KeyFlow pronto")
        assertThat(v(ButtonState.RECORDING, ef = semGravar).pt()).isEqualTo("KeyFlow gravando")
        assertThat(v(ButtonState.PAUSED, ef = semGravar).pt()).isEqualTo("KeyFlow pausado")
        assertThat(v(ButtonState.IDLE_READY, visao = true, ef = semGravar).pt()).isEqualTo("Ler a tela em andamento")
    }

    @Test
    fun `a descricao sai traduzida nos tres idiomas, sem texto ditado`() {
        val todas = ButtonState.entries.map { v(it) } + v(ButtonState.IDLE_READY, visao = true)
        for (idioma in listOf(AppLanguage.EN, AppLanguage.ES)) {
            AppLocale.language = idioma
            for (x in todas) {
                val saida = x.descricaoTraduzida { tr(it) }
                assertThat(saida).isNotEqualTo(x.pt())
            }
        }
        AppLocale.language = AppLanguage.PT
        assertThat(v(ButtonState.RECORDING).descricaoTraduzida { tr(it) }).isEqualTo("KeyFlow gravando. Toque para enviar")
    }
}
