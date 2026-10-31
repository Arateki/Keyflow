package com.keyflow.computador.chaves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometriaTest {
    private class Relogio(var ms: Long = 1_000_000L) : RelogioDeChaves { override fun agoraMs() = ms }

    @Test fun janelaDeCincoMinutos() {
        val r = Relogio()
        val j = JanelaDeBiometria(r)
        assertFalse("sem prompt ainda", j.valida())
        j.marcar()
        assertTrue(j.valida())
        r.ms += 299_999
        assertTrue("dentro dos 300 s", j.valida())
        r.ms += 1
        assertFalse("aos 300 s vence", j.valida())
    }

    @Test fun relogioParaTrasNaoRenovaAJanela() {
        val r = Relogio()
        val j = JanelaDeBiometria(r)
        j.marcar()
        r.ms -= 10_000
        assertFalse(j.valida())
    }

    @Test fun encerrarFechaAJanela() {
        val r = Relogio()
        val j = JanelaDeBiometria(r).also { it.marcar() }
        j.encerrar()
        assertFalse(j.valida())
    }

    @Test fun duracaoEDeTrezentosSegundos() = assertEquals(300, JanelaDeBiometria.DURACAO_S)

    @Test fun planoPorApi() {
        for (api in 24..29) assertEquals("API $api", PlanoDeAutenticacao.Validade(300), PlanoDeAutenticacao.para(api))
        for (api in 30..37) assertEquals("API $api", PlanoDeAutenticacao.Parametros(300, biometriaForte = true), PlanoDeAutenticacao.para(api))
    }

    private val spkiEd = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + ByteArray(32) { 1 }

    @Test fun sondaAprovaQuandoTudoBate() =
        assertEquals(AvaliadorDaSonda.Veredito.Aprovada, AvaliadorDaSonda.avaliar(34, spkiEd, true))

    @Test fun sondaReprovaPorApiMenorQue33() =
        assertTrue(AvaliadorDaSonda.avaliar(32, spkiEd, true) is AvaliadorDaSonda.Veredito.Reprovada)

    @Test fun sondaReprovaQuandoOProvedorDevolveP256() {
        val spkiP256 = ByteArray(91) { 2 } // SPKI de P-256 tem 91 bytes
        val v = AvaliadorDaSonda.avaliar(34, spkiP256, true) as AvaliadorDaSonda.Veredito.Reprovada
        assertTrue(v.motivo, v.motivo.contains("não é Ed25519"))
    }

    @Test fun sondaReprovaPorExcecaoPorFaltaDePublicaEPorAssinaturaInvalida() {
        assertTrue(AvaliadorDaSonda.avaliar(34, null, null, IllegalStateException("x")) is AvaliadorDaSonda.Veredito.Reprovada)
        assertTrue(AvaliadorDaSonda.avaliar(34, null, null) is AvaliadorDaSonda.Veredito.Reprovada)
        assertTrue(AvaliadorDaSonda.avaliar(34, spkiEd, false) is AvaliadorDaSonda.Veredito.Reprovada)
        assertTrue(AvaliadorDaSonda.avaliar(34, spkiEd, null) is AvaliadorDaSonda.Veredito.Reprovada)
    }

    @Test fun bytesDaPublicaSoParaSpkiEd25519() {
        assertEquals(32, AvaliadorDaSonda.bytesDaPublica(spkiEd)!!.size)
        assertEquals(null, AvaliadorDaSonda.bytesDaPublica(ByteArray(44)))
    }
}
