package com.keyflow.computador.app

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** O filtro de entrada tem estado (a rede parte as sequências onde quiser): o resultado não pode depender de onde parte. */
class FiltroDaSaidaDoServidorTest {
    private fun inteiro(bytes: ByteArray) = FiltroDaSaidaDoServidor().processar(bytes)

    private fun emPedacos(bytes: ByteArray, sorteio: Random): ByteArray {
        val f = FiltroDaSaidaDoServidor()
        val saida = ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) {
            val n = 1 + sorteio.nextInt(7)
            val fim = minOf(bytes.size, i + n)
            saida.write(f.processar(bytes.copyOfRange(i, fim)))
            i = fim
        }
        return saida.toByteArray()
    }

    private val vocabulario: List<ByteArray> = Hostil.segmentos.map { it.second.toByteArray(Charsets.UTF_8) } + listOf(
        "texto simples\r\n", "\u001b[1;31mcor\u001b[0m", "\u001b[2J", "\u001b]8;;https://a.b/c\u001b\\", "\u001b[?1049h", "ação ✓ 日本",
        "\u001b[21", "\u001b]52;", "\u001bP", "\u001b", "\u0018", "\u0007", "t", "?", ";",
    ).map { it.toByteArray(Charsets.UTF_8) }

    @Test fun qualquerDivisaoDaOMesmoResultado() {
        val sorteio = Random(20261031)
        repeat(400) {
            val fluxo = ByteArrayOutputStream()
            repeat(1 + sorteio.nextInt(12)) { fluxo.write(vocabulario[sorteio.nextInt(vocabulario.size)]) }
            val bytes = fluxo.toByteArray()
            val esperado = inteiro(bytes)
            repeat(5) { assertArrayEquals("fluxo ${bytes.toList()}", esperado, emPedacos(bytes, sorteio)) }
        }
    }

    @Test fun bytesAleatoriosNuncaQuebramEUmByteDeCadaVez() {
        val sorteio = Random(7)
        repeat(300) {
            val bytes = ByteArray(sorteio.nextInt(1, 300)) { (if (sorteio.nextInt(3) == 0) 0x1b else sorteio.nextInt(256)).toByte() }
            val esperado = inteiro(bytes)
            assertArrayEquals(esperado, emPedacos(bytes, sorteio))
        }
    }

    @Test fun nadaProibidoSobraDepoisDoFiltroMesmoComRemocoesQueSeEmendam() {
        val sorteio = Random(99)
        val proibidos = listOf("\u001b[21t", "\u001b[20t", "\u001bP\$q", "\u001b]52;c;?", "\u001b]52;;?", "\u001b]21", "\u0005")
        repeat(600) {
            val fluxo = ByteArrayOutputStream()
            repeat(1 + sorteio.nextInt(14)) { fluxo.write(vocabulario[sorteio.nextInt(vocabulario.size)]) }
            val saiu = inteiro(fluxo.toByteArray())
            for (p in proibidos) assertFalse("sobrou ${p.replace("\u001b", "ESC")} em ${fluxo.toByteArray().toList()}", saiu.contem(p))
        }
    }

    @Test fun textoComumPassaIgual() {
        val comum = "ls -la\r\ntotal 0\r\n\u001b[1;32mok\u001b[0m ação\r\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(comum, inteiro(comum))
    }

    @Test fun reiniciarLimpaOQueEstavaSegurado() {
        val f = FiltroDaSaidaDoServidor()
        f.processar("\u001b[2".toByteArray())
        f.reiniciar()
        assertEquals("1t", String(f.processar("1t".toByteArray())))
    }
}
