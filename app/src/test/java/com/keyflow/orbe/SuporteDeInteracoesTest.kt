package com.keyflow.orbe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Suporte de interações por lugar da orbe (KBO-12, design §7). */
class SuporteDeInteracoesTest {

    private val todas = listOf(
        Interacao.TOQUE_1,
        Interacao.TOQUE_2,
        Interacao.TOQUE_3,
        Interacao.SEGURAR,
        Interacao.ARRASTAR_CIMA,
        Interacao.ARRASTAR_LADO,
        Interacao.ARRASTAR_BAIXO,
    )

    @Test
    fun `teclado e flutuante suportam as sete interacoes`() {
        for (lugar in listOf(LugarDaOrbe.TECLADO, LugarDaOrbe.FLUTUANTE)) {
            assertThat(SuporteDeInteracoes.suportadas(lugar)).containsExactlyElementsIn(todas)
            assertThat(SuporteDeInteracoes.naoSuportadas(lugar)).isEmpty()
            for (interacao in todas) {
                assertThat(SuporteDeInteracoes.suporta(lugar, interacao)).isTrue()
            }
        }
    }

    @Test
    fun `notificacao suporta apenas os tres toques`() {
        val toques = listOf(Interacao.TOQUE_1, Interacao.TOQUE_2, Interacao.TOQUE_3)
        val continuas = listOf(
            Interacao.SEGURAR,
            Interacao.ARRASTAR_CIMA,
            Interacao.ARRASTAR_LADO,
            Interacao.ARRASTAR_BAIXO,
        )
        assertThat(SuporteDeInteracoes.suportadas(LugarDaOrbe.NOTIFICACAO)).containsExactlyElementsIn(toques)
        assertThat(SuporteDeInteracoes.naoSuportadas(LugarDaOrbe.NOTIFICACAO)).containsExactlyElementsIn(continuas).inOrder()
        for (interacao in toques) {
            assertThat(SuporteDeInteracoes.suporta(LugarDaOrbe.NOTIFICACAO, interacao)).isTrue()
        }
        for (interacao in continuas) {
            assertThat(SuporteDeInteracoes.suporta(LugarDaOrbe.NOTIFICACAO, interacao)).isFalse()
        }
    }

    @Test
    fun `mapeamento de contagem de toques para Interacao`() {
        assertThat(Interacao.deToques(1)).isEqualTo(Interacao.TOQUE_1)
        assertThat(Interacao.deToques(2)).isEqualTo(Interacao.TOQUE_2)
        assertThat(Interacao.deToques(3)).isEqualTo(Interacao.TOQUE_3)
        assertThat(Interacao.deToques(0)).isNull()
        assertThat(Interacao.deToques(4)).isNull()
        assertThat(Interacao.SEGURAR.toques).isNull()
    }
}
