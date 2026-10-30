package com.keyflow.orbe

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import org.junit.Test

/** NTF-T4: a tabela dos arrastos da orbe flutuante em Alternar (design §3.1). */
class ArrastosDaOrbeTest {

    @Test
    fun `gravando - cima pausa, lado descarta, baixo abre o app`() {
        assertThat(ArrastosDaOrbe.comEfeito(ButtonState.RECORDING)).containsExactly(
            Interacao.ARRASTAR_CIMA, EfeitoDeArrasto.PAUSAR,
            Interacao.ARRASTAR_LADO, EfeitoDeArrasto.DESCARTAR,
            Interacao.ARRASTAR_BAIXO, EfeitoDeArrasto.ABRIR_APP,
        )
    }

    @Test
    fun `pausado - cima retoma, lado descarta, baixo abre o app`() {
        assertThat(ArrastosDaOrbe.comEfeito(ButtonState.PAUSED)).containsExactly(
            Interacao.ARRASTAR_CIMA, EfeitoDeArrasto.RETOMAR,
            Interacao.ARRASTAR_LADO, EfeitoDeArrasto.DESCARTAR,
            Interacao.ARRASTAR_BAIXO, EfeitoDeArrasto.ABRIR_APP,
        )
    }

    @Test
    fun `transcrevendo so tem a lateral`() {
        assertThat(ArrastosDaOrbe.comEfeito(ButtonState.SENDING)).containsExactly(Interacao.ARRASTAR_LADO, EfeitoDeArrasto.DESCARTAR)
    }

    @Test
    fun `os demais estados nao tem trava de arrasto`() {
        for (e in listOf(ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD, ButtonState.CANCELLING, ButtonState.ERROR, ButtonState.PENDING_PASTE)) {
            assertThat(ArrastosDaOrbe.comEfeito(e)).isEmpty()
        }
    }

    @Test
    fun `so os tres arrastos aparecem na tabela`() {
        for (e in ButtonState.entries) {
            assertThat(ArrastosDaOrbe.comEfeito(e).keys).containsNoneOf(Interacao.TOQUE_1, Interacao.TOQUE_2, Interacao.TOQUE_3, Interacao.SEGURAR)
        }
    }
}
