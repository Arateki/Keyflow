package com.keyflow.computador.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorDeCadastroTest {
    private class SentinelaFalsa : SentinelaKeystore {
        val existentes = mutableSetOf<String>()
        var invalida = false
        var criadas = 0
        override fun existe(id: String) = id in existentes
        override fun criar(id: String) { existentes += id; criadas++ }
        override fun invalidada(id: String) = invalida
        override fun apagar(id: String) { existentes -= id }
    }

    @Test fun sentinelaNovaSoNasceAteAApi29() {
        for (api in 24..29) SentinelaFalsa().also { DetectorDeCadastroKeystore(api, it).criar("k"); assertEquals("API $api", 1, it.criadas) }
        for (api in 30..36) SentinelaFalsa().also { DetectorDeCadastroKeystore(api, it).criar("k"); assertEquals("API $api", 0, it.criadas) }
    }

    @Test fun chaveCriadaNaApi29EUsadaNa30MaisConsultaASentinelaExistente() {
        val s = SentinelaFalsa()
        DetectorDeCadastroKeystore(api = 29, sentinelas = s).criar("k1")
        // OTA: o app agora roda na API 34, com a mesma sentinela no Keystore.
        val depoisDoOta = DetectorDeCadastroKeystore(api = 34, sentinelas = s)
        assertFalse(depoisDoOta.invalidada("k1"))
        s.invalida = true // outra digital cadastrada
        assertTrue("a sentinela antiga vale na API 34", depoisDoOta.invalidada("k1"))
    }

    @Test fun semSentinelaNaoHaInvalidacao() {
        val s = SentinelaFalsa().also { it.invalida = true }
        assertFalse(DetectorDeCadastroKeystore(api = 34, sentinelas = s).invalidada("sem-sentinela"))
        assertFalse(DetectorDeCadastroKeystore(api = 28, sentinelas = s).invalidada("sem-sentinela"))
    }

    @Test fun apagarRemoveASentinela() {
        val s = SentinelaFalsa()
        val d = DetectorDeCadastroKeystore(api = 29, sentinelas = s)
        d.criar("k"); d.apagar("k")
        assertFalse(s.existe("k"))
    }
}
