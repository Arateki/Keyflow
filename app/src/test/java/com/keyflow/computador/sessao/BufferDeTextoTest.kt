package com.keyflow.computador.sessao

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferDeTextoTest {
    private fun BufferDeTexto.em(s: String) = alimentar(s.toByteArray(Charsets.UTF_8))

    @Test fun removeAnsiOscApcEDcs() {
        val b = BufferDeTexto()
        b.em("\u001b[1;31mvermelho\u001b[0m \u001b]0;titulo\u0007ok \u001b]8;;http://x\u001b\\link\u001b]8;;\u001b\\ \u001b_Ga=T;AAAA\u001b\\fim \u001bP1;2qDCS\u001b\\!\n")
        assertEquals(listOf("vermelho ok link fim !"), b.ultimasLinhas(5))
    }

    @Test fun retornoDeCarroEBackspaceSobrescrevem() {
        val b = BufferDeTexto()
        b.em("abcdef\rXY\n")
        b.em("123\b\bZ\n")
        assertEquals(listOf("XYcdef", "1Z3"), b.ultimasLinhas(5))
    }

    @Test fun apagamentoDeLinha() {
        val b = BufferDeTexto()
        b.em("0123456789\r\u001b[5C\u001b[K") // CSI C (cursor) é ignorado; K apaga do cursor (coluna 0 aqui)
        assertEquals("", b.ultimasLinhas(1).firstOrNull() ?: "")
        val c = BufferDeTexto()
        c.em("progresso 50%\r\u001b[2Kpronto\n")
        assertEquals(listOf("pronto"), c.ultimasLinhas(3))
    }

    @Test fun utf8PartidoEntrePacotes() {
        val b = BufferDeTexto()
        val bytes = "ação 😀 é\n".toByteArray()
        for (byte in bytes) b.alimentar(byteArrayOf(byte))
        assertEquals(listOf("ação 😀 é").map { it.codePoints().toArray().toList() }, b.ultimasLinhas(2).map { it.codePoints().toArray().toList() })
    }

    @Test fun escapePartidoEntrePacotes() {
        val b = BufferDeTexto()
        for (parte in listOf("a\u001b", "[3", "1mb\u001b]0;ti", "tulo\u0007c\n")) b.em(parte)
        assertEquals(listOf("abc"), b.ultimasLinhas(2))
    }

    @Test fun naoCresceAlemDoTeto() {
        val b = BufferDeTexto(maxLinhas = 100)
        repeat(1_000) { b.em("linha $it\n") }
        assertEquals(100, b.quantidadeDeLinhas())
        assertEquals("linha 999", b.ultimasLinhas(1).single())
        val larga = BufferDeTexto(maxColunas = 50)
        larga.em("x".repeat(500) + "\n")
        assertEquals(50, larga.ultimasLinhas(1).single().length)
    }

    @Test fun tetoPadraoESaoCincoMilLinhasPorQuatroMilColunas() {
        assertEquals(5_000, BufferDeTexto.MAX_LINHAS)
        assertEquals(4_096, BufferDeTexto.MAX_COLUNAS)
    }

    @Test fun lixoBinarioNaoLanca() {
        val r = java.util.Random(3)
        val b = BufferDeTexto()
        repeat(500) { b.alimentar(ByteArray(r.nextInt(200)).also(r::nextBytes)) }
        b.texto()
    }

    @Test fun neverGoesToDisk() {
        val tmp = File(System.getProperty("java.io.tmpdir")!!)
        val antes = tmp.list()?.toSet().orEmpty()
        val b = BufferDeTexto()
        repeat(2_000) { b.em("linha $it\n") }
        b.texto()
        assertEquals("o buffer não pode criar arquivos", antes, tmp.list()?.toSet().orEmpty())
        val fonte = File("src/main/java/com/keyflow/computador/sessao/BufferDeTexto.kt").readText()
        for (proibido in listOf("java.io.File", "FileOutputStream", "FileWriter", "RandomAccessFile", "java.nio.file")) assertFalse(proibido, fonte.contains(proibido))
        assertTrue(fonte.contains("ByteArrayOutputStream"))
    }
}
