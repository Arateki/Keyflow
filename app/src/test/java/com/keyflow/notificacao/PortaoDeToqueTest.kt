package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** NTF-T5: o portão da tela bloqueada (NTF-15..17, NTF-49, design §10). */
class PortaoDeToqueTest {
    private fun a(b: Boolean, p: Boolean, act: Boolean) = PortaoDeToque.avaliar(b, p, act)

    @Test
    fun `bloqueada com a opcao desligada recusa, com ou sem Activity`() {
        assertThat(a(true, false, false)).isEqualTo(Portao.RecusarBloqueado)
        assertThat(a(true, false, true)).isEqualTo(Portao.RecusarBloqueado)
    }

    @Test
    fun `bloqueada com a opcao ligada pede o desbloqueio se precisa de Activity`() {
        assertThat(a(true, true, true)).isEqualTo(Portao.RecusarPrecisaDesbloqueio)
    }

    @Test
    fun `bloqueada com a opcao ligada e sem Activity aceita`() {
        assertThat(a(true, true, false)).isEqualTo(Portao.Aceitar)
    }

    @Test
    fun `desbloqueada sempre aceita`() {
        for (p in listOf(true, false)) for (act in listOf(true, false)) assertThat(a(false, p, act)).isEqualTo(Portao.Aceitar)
    }
}
