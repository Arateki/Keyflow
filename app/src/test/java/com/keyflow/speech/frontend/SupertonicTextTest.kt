package com.keyflow.speech.frontend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SupertonicTextTest {
    @Test fun indiceFalsoProduzIdsComIdiomaPtEDiacriticosDecompostos() {
        val frontend = SupertonicText(IntArray(65536) { it + 5 })
        val expected = "<pt>Ola\u0301, voz!</pt>".map { it.code.toLong() + 5 }.toLongArray()
        assertArrayEquals(expected, frontend.ids("Olá , voz! 😀"))
    }

    @Test fun normalizacaoSegueMarcadoresPontuacaoESimbolosDoOficial() {
        assertEquals("<pt>Olá.</pt>".replace("á", "a\u0301"), SupertonicText.normalize("  Olá  "))
        assertEquals("<pt>voz -'sim' at casa.</pt>", SupertonicText.normalize("voz — ‘’sim’’ @ casa"))

    }

    @Test fun parseDoIndiceFalsoPreservaMapeamentoDoArquivo() {
        val index = IntArray(65536) { -1 }.apply { "<pt>oi.</pt>".forEach { this[it.code] = it.code * 2 } }
        val json = index.joinToString(",", "[", "]")
        assertArrayEquals("<pt>oi.</pt>".map { (it.code * 2).toLong() }.toLongArray(), SupertonicText.parse(json).ids("oi"))
    }

    @Test fun separaParagrafosEPreservaAbreviacaoEFrase() {
        assertEquals(listOf("Olá.", "Tudo bem?", "Sr. Silva!"), SupertonicText.chunks("Olá. Tudo bem?\n\nSr. Silva!", 10))
    }
}
