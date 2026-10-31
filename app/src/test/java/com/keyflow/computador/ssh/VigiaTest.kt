package com.keyflow.computador.ssh

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VigiaTest {
    @Test fun tresFalhasSeguidasDerrubamEmNoveSegundosDeTresIntervalos() = runTest {
        val v = Vigia(30_000, 3) { false }
        v.vigiar()
        assertEquals(90_000L, currentTime)
    }

    @Test fun respostaZeraAContagem() = runTest {
        val respostas = ArrayDeque(listOf(false, false, true, false, false, false))
        Vigia(30_000, 3) { respostas.removeFirst() }.vigiar()
        assertEquals(180_000L, currentTime)
    }

    @Test fun servidorSemPingNuncaECondenado() = runTest {
        val j = async { Vigia(30_000, 3) { null }.vigiar() }
        advanceTimeBy(3_600_000)
        assertFalse(j.isCompleted)
        j.cancel()
    }
}
