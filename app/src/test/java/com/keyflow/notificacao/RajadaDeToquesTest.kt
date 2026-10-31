package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** NTF-T4: a contagem de toques numa janela, com relógio virtual (NTF-09). */
class RajadaDeToquesTest {
    private var agora = 0L
    private val rajada = RajadaDeToques(relogio = { agora }, janelaMs = { 250 })

    @Test
    fun `um, dois, tres e cinco toques`() {
        for (n in listOf(1, 2, 3, 5)) {
            repeat(n) { rajada.toque(); agora += 100 }
            agora += 150
            assertThat(rajada.assentou()).isTrue()
            assertThat(rajada.fechar()).isEqualTo(n)
        }
    }

    @Test
    fun `a janela reinicia a cada toque`() {
        rajada.toque()
        agora += 200
        assertThat(rajada.faltaMs()).isEqualTo(50)
        rajada.toque()
        assertThat(rajada.faltaMs()).isEqualTo(250)
        agora += 249
        assertThat(rajada.assentou()).isFalse()
        agora += 1
        assertThat(rajada.assentou()).isTrue()
        assertThat(rajada.fechar()).isEqualTo(2)
    }

    @Test
    fun `fecha uma unica vez e sem toque nao ha janela`() {
        assertThat(rajada.faltaMs()).isNull()
        assertThat(rajada.assentou()).isFalse()
        rajada.toque(); rajada.toque()
        assertThat(rajada.fechar()).isEqualTo(2)
        assertThat(rajada.fechar()).isEqualTo(0)
        assertThat(rajada.faltaMs()).isNull()
    }

    @Test
    fun `a janela e lida a cada vez, valendo para a mudanca de configuracao`() {
        var janela = 250L
        val r = RajadaDeToques({ agora }, { janela })
        r.toque()
        janela = 400
        agora += 300
        assertThat(r.assentou()).isFalse()
    }
}
