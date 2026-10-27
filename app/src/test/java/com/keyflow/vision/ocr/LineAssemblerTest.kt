package com.keyflow.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class LineAssemblerTest {

    private fun item(text: String, x0: Int, y0: Int, x1: Int, y1: Int) = RecognizedBox(TextBox(x0, y0, x1, y1), text, 0.9f)

    @Test
    fun semCaixasOuSoEspacoNaoTemTexto() {
        assertEquals("", LineAssembler.assemble(emptyList()))
        assertEquals("", LineAssembler.assemble(listOf(item("  ", 0, 0, 50, 20))))
    }

    @Test
    fun caixasDaMesmaLinhaViramUmaLinhaOrdenadaPorX() {
        val text = LineAssembler.assemble(
            listOf(item("direita", 300, 102, 380, 122), item("esquerda", 20, 100, 120, 120)),
        )
        assertEquals("esquerda direita", text)
    }

    @Test
    fun linhasSeparadasPorQuebraEEmOrdemDeCima() {
        val text = LineAssembler.assemble(
            listOf(item("segunda", 20, 130, 120, 150), item("primeira", 20, 100, 120, 120), item("terceira", 20, 160, 120, 180)),
        )
        assertEquals("primeira\nsegunda\nterceira", text)
    }

    @Test
    fun lacunaMaiorQueUmaEMeiaAlturaViraLinhaEmBranco() {
        // altura 20 → 1,5x = 30; a lacuna de 50 px separa blocos.
        val text = LineAssembler.assemble(
            listOf(item("a", 20, 100, 60, 120), item("b", 20, 125, 60, 145), item("c", 20, 195, 60, 215)),
        )
        assertEquals("a\nb\n\nc", text)
    }

    @Test
    fun lacunaPequenaNaoViraBloco() {
        val text = LineAssembler.assemble(listOf(item("a", 20, 100, 60, 120), item("b", 20, 140, 60, 160)))
        assertEquals("a\nb", text)
    }

    @Test
    fun linhaVizinhaNaoEngoleALinhaDeBaixoPorCrescerAFaixa() {
        // Duas linhas de 20 px a 18 px uma da outra se sobrepõem 2 px (10%): linhas distintas.
        val text = LineAssembler.assemble(listOf(item("x", 0, 100, 40, 120), item("y", 0, 118, 40, 138)))
        assertEquals("x\ny", text)
    }

    @Test
    fun textoComEspacosNasPontasEhLimpo() {
        assertEquals("a b", LineAssembler.assemble(listOf(item(" a ", 0, 0, 20, 20), item("b ", 40, 0, 60, 20))))
    }
}
