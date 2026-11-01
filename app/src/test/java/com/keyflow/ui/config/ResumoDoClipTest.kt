package com.keyflow.ui.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CMP-12: só apaga a área de transferência se o clipe ainda for o colado — por resumo forte, não por hashCode. */
class ResumoDoClipTest {
    @Test fun mesmoTextoEMesmoClip() {
        assertTrue(mesmoClip("PRIVADA-FALSA-Aa", resumoDoClip("PRIVADA-FALSA-Aa")))
    }

    @Test fun textoTrocadoNaoEMesmoClip() {
        assertFalse(mesmoClip("outro texto copiado", resumoDoClip("PRIVADA-FALSA-Aa")))
    }

    @Test fun colisaoDeHashCodeNaoApagaClipeDiferente() {
        assertEquals("PRIVADA-FALSA-Aa".hashCode(), "PRIVADA-FALSA-BB".hashCode())
        assertFalse(mesmoClip("PRIVADA-FALSA-BB", resumoDoClip("PRIVADA-FALSA-Aa")))
    }
}
