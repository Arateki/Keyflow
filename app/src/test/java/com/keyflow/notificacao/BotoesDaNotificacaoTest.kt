package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.orbe.ArrastosDaOrbe
import com.keyflow.orbe.EfeitoDeArrasto
import org.junit.Test

/** NTF-T4: os botões da notificação derivados da tabela de arrastos (NTF-48..50, design §3.1). */
class BotoesDaNotificacaoTest {

    private fun para(e: ButtonState, visao: Boolean = false) = BotoesDaNotificacao.para(e, visao)

    @Test
    fun `gravando - Pausar e Descartar`() = assertThat(para(ButtonState.RECORDING)).isEqualTo(Botoes(Botao.PAUSAR, Botao.DESCARTAR))

    @Test
    fun `pausado - Retomar e Descartar`() = assertThat(para(ButtonState.PAUSED)).isEqualTo(Botoes(Botao.RETOMAR, Botao.DESCARTAR))

    @Test
    fun `transcrevendo - so Descartar`() = assertThat(para(ButtonState.SENDING)).isEqualTo(Botoes(null, Botao.DESCARTAR))

    @Test
    fun `cancelando - so Desfazer`() = assertThat(para(ButtonState.CANCELLING)).isEqualTo(Botoes(null, Botao.DESFAZER))

    @Test
    fun `Visao ativa - so Encerrar, qualquer que seja o estado`() {
        for (e in ButtonState.entries) assertThat(para(e, visao = true)).isEqualTo(Botoes(null, Botao.ENCERRAR))
    }

    @Test
    fun `ocioso, erro e pendente - nenhum`() {
        for (e in listOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD, ButtonState.ERROR, ButtonState.PENDING_PASTE)) {
            assertThat(para(e)).isEqualTo(Botoes.NENHUM)
        }
    }

    @Test
    fun `ABRIR_APP nao gera botao`() {
        assertThat(BotoesDaNotificacao.botaoDe(EfeitoDeArrasto.ABRIR_APP)).isNull()
    }

    /** "Todo arrasto com efeito ganha botão": um efeito novo na tabela sem botão faz este teste falhar. */
    @Test
    fun `todo efeito da tabela de arrastos, menos ABRIR_APP, produz botao`() {
        for (estado in ButtonState.entries) {
            val botoes = para(estado).todos
            for ((arrasto, efeito) in ArrastosDaOrbe.comEfeito(estado)) {
                if (efeito == EfeitoDeArrasto.ABRIR_APP) continue
                val esperado = BotoesDaNotificacao.botaoDe(efeito)
                assertThat(esperado).isNotNull()
                assertThat(botoes).contains(esperado)
            }
        }
    }

    @Test
    fun `cada botao tem descricao de acessibilidade`() {
        assertThat(Botao.entries.map { BotoesDaNotificacao.descricao(it) }).containsExactly(
            "Pausar gravação", "Retomar gravação", "Descartar gravação", "Desfazer descarte", "Encerrar a leitura da tela",
        ).inOrder()
    }
}
