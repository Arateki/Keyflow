package com.keyflow.computador

import java.util.Base64
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Base64PuroTest {
    @Test fun igualAoDaJdkParaTodosOsTamanhos() {
        val r = Random(7)
        for (n in 0..200) {
            val b = ByteArray(n).also { r.nextBytes(it) }
            assertEquals(Base64.getEncoder().encodeToString(b), Base64Puro.codificar(b))
            assertEquals(Base64.getEncoder().withoutPadding().encodeToString(b), Base64Puro.codificar(b, comPadding = false))
            assertArrayEquals(b, Base64Puro.decodificar(Base64.getEncoder().encodeToString(b)))
            assertArrayEquals(b, Base64Puro.decodificar(Base64.getEncoder().withoutPadding().encodeToString(b)))
        }
    }

    @Test fun textoInvalidoViraNulo() {
        for (t in listOf("@@@@", "A", "AAAAA", "AA=A", "AAA===", "é")) assertNull(t, Base64Puro.decodificar(t))
    }
}
