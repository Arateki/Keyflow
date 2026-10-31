package com.keyflow.computador.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressões da revisão: equivalências que o parser C reconhece e comparações literais não reconheciam. */
class DivergenciasDoParserTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun filtrar(s: String) = FiltroDaSaidaDoServidor().processar(bytes(s))

    @Test fun s8c1tNaoLigaRespostasEmOitoBits() {
        assertEquals(0, filtrar("\u001b G").size)
        assertEquals(0, filtrar("\u001b \u0000\u007fG").size)
    }

    @Test fun respostasC1SemEscTambemPassamPelaListaFechada() {
        for (s in listOf("\u0090\$r0m\u009c", "\u009b21t", "\u009b>0q", "\u009b?1;1\$y", "\u009d52;c;c2VncmVkbw==\u009c")) {
            assertEquals(s.toList().toString(), 0, FiltroDeRespostas().filtrar(bytes(s)).size)
        }
        assertArrayEquals(bytes("\u009b0n"), FiltroDeRespostas().filtrar(bytes("\u009b0n")))
        val utf8 = "ação ЛћÜ 日本".toByteArray()
        assertArrayEquals(utf8, FiltroDeRespostas().filtrar(utf8))
    }

    /** A libvterm do app fica sempre em UTF-8 (Terminal.cpp) e ignora `ESC % @`: 0x9c não fecha a string depois dele. */
    @Test fun escPercentArrobaNaoDesligaUtf8() {
        assertArrayEquals(bytes("\u001b%@"), filtrar("\u001b%@\u001b]52;c\u009c;?\u0007"))
        val titulos = mutableListOf<String>()
        FiltroDaSaidaDoServidor { titulos += it }.processar(bytes("\u001b%@\u001b]0;\u009cTITULO\u0007"))
        assertTrue(titulos.toString(), titulos.any { "TITULO" in it })
    }

    @Test fun risEDecstrVoltamAsSetasAoModoNormal() {
        for (reset in listOf("\u001bc", "\u001b[!p")) {
            val f = FiltroDaSaidaDoServidor()
            f.processar(bytes("\u001b[?1h"))
            assertTrue(f.modoAplicacaoDasSetas)
            f.processar(bytes(reset))
            assertFalse(reset, f.modoAplicacaoDasSetas)
        }
    }

    @Test fun parametrosNumericosSaoNormalizados() {
        for (s in listOf("\u001b[021t", "\u001b[00020t", "\u001b]052;c;?\u0007", "\u001b]021;?\u0007")) {
            assertEquals(s.toList().toString(), 0, filtrar(s).size)
        }
        val titulos = mutableListOf<String>()
        val f = FiltroDaSaidaDoServidor { titulos += it }
        val titulo = bytes("\u001b]00;servidor\u0007")
        assertArrayEquals(titulo, f.processar(titulo))
        assertEquals(listOf("servidor"), titulos)
        f.processar(bytes("\u001b[?01h"))
        assertTrue(f.modoAplicacaoDasSetas)
        f.processar(bytes("\u001b[?0001l"))
        assertFalse(f.modoAplicacaoDasSetas)
    }

    @Test fun seletoresDoClipboardNaoTemAtalhoQueLibereConsultaLonga() {
        for (n in listOf(30, 9_000, 100_000, 1_100_000)) {
            assertEquals(0, filtrar("\u001b]052;${"c".repeat(n)};?\u001b\\").size)
        }
    }

    @Test fun c0EDelNaoInterrompemCsiOuComandoDcs() {
        for (c in listOf('\u0000', '\u0001', '\u0005', '\n', '\u007f')) {
            assertEquals(0, filtrar("\u001b[02${c}1t").size)
            assertEquals(0, filtrar("\u001bP${c}\$qm\u001b\\").size)
            assertEquals(0, filtrar("\u001b]05${c}2;c;?\u0007").size)
        }
    }

    @Test fun cancelarTituloNaoPublicaTituloNemPrendeOProximoTexto() {
        for (c in listOf('\u0018', '\u001a')) {
            val titulos = mutableListOf<String>()
            val f = FiltroDaSaidaDoServidor { titulos += it }
            val saiu = f.processar(bytes("\u001b]0;velho${c}texto\u001b]00;novo\u0007"))
            assertEquals(listOf("novo"), titulos)
            assertTrue(String(saiu, Charsets.ISO_8859_1).contains("texto"))
        }
    }
    @Test fun controlesDoUsuarioIncluindoCtrlENaoSaoConsultasDoServidor() {
        for (codigo in (0..31).toList()+127) {
            val tecla = byteArrayOf(codigo.toByte())
            assertArrayEquals("Ctrl de código $codigo", tecla, FiltroDeRespostas().filtrar(tecla))
            val alt = byteArrayOf(27) + tecla
            assertArrayEquals("Alt+Ctrl de código $codigo", alt, FiltroDeRespostas().filtrar(alt))
        }
        assertEquals("ENQ do servidor continua bloqueado", 0, filtrar("\u0005").size)
        val altEsc = byteArrayOf(27, 27)
        assertArrayEquals(altEsc, FiltroDeRespostas().filtrar(altEsc))
        val altUnicode = byteArrayOf(27) + "ћ".toByteArray()
        assertArrayEquals(altUnicode, FiltroDeRespostas().filtrar(altUnicode))
    }
    @Test fun bytesIgnoradosDentroDeEscNaoEscondemComandos() {
        for (codigo in 128..255) {
            val ignorado = codigo.toChar()
            assertEquals("S8C1T com $codigo", 0, filtrar("\u001b${ignorado} G").size)
            assertEquals("CSI com $codigo", 0, filtrar("\u001b${ignorado}[021t").size)
        }
    }
}
