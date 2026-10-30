package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** NTF-T5: dispensar não muda configuração; a notificação volta quando necessária ou ao abrir a aba (NTF-18, design §9). */
class VisibilidadeDaNotificacaoTest {

    private fun em(p: Presenca): VisibilidadeDaNotificacao {
        val v = VisibilidadeDaNotificacao()
        when (p) {
            Presenca.PARADA -> Unit
            Presenca.VISIVEL -> v.aplicar(EventoPresenca.SessaoIniciou)
            Presenca.DISPENSADA -> { v.aplicar(EventoPresenca.SessaoIniciou); v.aplicar(EventoPresenca.Dispensou) }
        }
        assertThat(v.presenca).isEqualTo(p)
        return v
    }

    private fun linha(p: Presenca, e: EventoPresenca, nova: Presenca, posta: Boolean) {
        val v = em(p)
        assertThat(v.aplicar(e)).isEqualTo(posta)
        assertThat(v.presenca).isEqualTo(nova)
        assertThat(v.podePostar).isEqualTo(nova == Presenca.VISIVEL)
    }

    @Test fun `PARADA e SessaoIniciou - VISIVEL, posta`() = linha(Presenca.PARADA, EventoPresenca.SessaoIniciou, Presenca.VISIVEL, true)
    @Test fun `PARADA e Necessaria - VISIVEL, posta`() = linha(Presenca.PARADA, EventoPresenca.Necessaria, Presenca.VISIVEL, true)
    @Test fun `PARADA e Dispensou - PARADA, nao posta`() = linha(Presenca.PARADA, EventoPresenca.Dispensou, Presenca.PARADA, false)
    @Test fun `PARADA e AbaAberta - PARADA, a aba nunca inicia o servico`() = linha(Presenca.PARADA, EventoPresenca.AbaAberta, Presenca.PARADA, false)
    @Test fun `VISIVEL e Dispensou - DISPENSADA, nao posta`() = linha(Presenca.VISIVEL, EventoPresenca.Dispensou, Presenca.DISPENSADA, false)
    @Test fun `VISIVEL e Necessaria - VISIVEL, nao posta de novo`() = linha(Presenca.VISIVEL, EventoPresenca.Necessaria, Presenca.VISIVEL, false)
    @Test fun `VISIVEL e AbaAberta - VISIVEL, nao posta de novo`() = linha(Presenca.VISIVEL, EventoPresenca.AbaAberta, Presenca.VISIVEL, false)
    @Test fun `VISIVEL e SessaoIniciou - VISIVEL, nao posta de novo`() = linha(Presenca.VISIVEL, EventoPresenca.SessaoIniciou, Presenca.VISIVEL, false)
    @Test fun `DISPENSADA e Necessaria - VISIVEL, posta`() = linha(Presenca.DISPENSADA, EventoPresenca.Necessaria, Presenca.VISIVEL, true)
    @Test fun `DISPENSADA e AbaAberta - VISIVEL, posta`() = linha(Presenca.DISPENSADA, EventoPresenca.AbaAberta, Presenca.VISIVEL, true)
    @Test fun `DISPENSADA e Dispensou - DISPENSADA, nao posta`() = linha(Presenca.DISPENSADA, EventoPresenca.Dispensou, Presenca.DISPENSADA, false)

    @Test
    fun `DISPENSADA permanece num re-post comum - nao ha evento e podePostar e falso`() {
        val v = em(Presenca.DISPENSADA)
        assertThat(v.podePostar).isFalse()
        assertThat(v.presenca).isEqualTo(Presenca.DISPENSADA)
    }

    @Test
    fun `SessaoTerminou leva a PARADA de qualquer presenca`() {
        for (p in Presenca.entries) linha(p, EventoPresenca.SessaoTerminou, Presenca.PARADA, false)
    }

    private val eventos = listOf(
        EventoPresenca.SessaoIniciou, EventoPresenca.Necessaria, EventoPresenca.Dispensou, EventoPresenca.AbaAberta, EventoPresenca.SessaoTerminou,
    )

    @Test
    fun `propriedade - so se entra em DISPENSADA por Dispensou e so se sai por Necessaria ou AbaAberta`() = runBlocking<Unit> {
        checkAll(3_000, Arb.list(Arb.element(eventos), 0..30)) { sequencia ->
            val v = VisibilidadeDaNotificacao()
            for (e in sequencia) {
                val antes = v.presenca
                val posta = v.aplicar(e)
                if (v.presenca == Presenca.DISPENSADA && antes != Presenca.DISPENSADA) {
                    assertThat(e).isEqualTo(EventoPresenca.Dispensou)
                    assertThat(antes).isEqualTo(Presenca.VISIVEL)
                }
                if (antes == Presenca.DISPENSADA && v.presenca == Presenca.VISIVEL) {
                    assertThat(e == EventoPresenca.Necessaria || e == EventoPresenca.AbaAberta).isTrue()
                    assertThat(posta).isTrue()
                }
                if (e == EventoPresenca.SessaoTerminou) assertThat(v.presenca).isEqualTo(Presenca.PARADA)
                if (v.presenca == Presenca.DISPENSADA) assertThat(posta).isFalse()
                assertThat(v.podePostar).isEqualTo(v.presenca == Presenca.VISIVEL)
            }
        }
    }
}
