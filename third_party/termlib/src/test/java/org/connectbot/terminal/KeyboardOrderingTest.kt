package org.connectbot.terminal

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fila de produção real sob um comando retido: nenhuma suposição sobre "40 ms bastam". */
class KeyboardOrderingTest {
    private fun verificar(antes: String, depois: String, teclaPrimeiro: Boolean) {
        val fila = TerminalDispatcher()
        val entrou = CountDownLatch(1)
        val liberar = CountDownLatch(1)
        val terminou = CountDownLatch(1)
        val escritos = CopyOnWriteArrayList<ByteArray>()
        fila.execute { entrou.countDown(); check(liberar.await(5, TimeUnit.SECONDS)) }
        assertTrue(entrou.await(5, TimeUnit.SECONDS))
        try {
            if (teclaPrimeiro) {
                fila.keyboardInput(antes.toByteArray()) { escritos += it }
                fila.execute { escritos += depois.toByteArray() }
            } else {
                fila.execute { escritos += antes.toByteArray() }
                fila.keyboardInput(depois.toByteArray()) { escritos += it }
            }
            // Se a tecla sair por fora da fila, passa à frente do trabalho retido.
            assertEquals(0, escritos.size)
            fila.execute { terminou.countDown() }
        } finally { liberar.countDown() }
        assertTrue(terminou.await(5, TimeUnit.SECONDS))
        assertEquals(antes+depois, escritos.joinToString("") { String(it) })
    }

    @Test fun escAntesDeI() = verificar("\u001b", "i", true)
    @Test fun backspaceAntesDeLetra() = verificar("\u007f", "a", true)
    @Test fun textoAntesDeEnterMesmoComAFilaOcupada() = verificar("x".repeat(10_000), "\r", false)
}
