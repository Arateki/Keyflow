package com.keyflow.vision.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CtcDecoderTest {

    private val decoder = CtcDecoder(listOf("a", "b", "ç")) // classes: 0 branco, 1 a, 2 b, 3 ç, 4 espaço

    /** Um passo por classe escolhida, com [p] de probabilidade nela e o resto dividido (soma 1). */
    private fun oneHot(path: List<Int>, p: Float = 0.9f): FloatArray {
        val classes = decoder.classCount
        val out = FloatArray(path.size * classes)
        path.forEachIndexed { t, c ->
            for (k in 0 until classes) out[t * classes + k] = if (k == c) p else (1f - p) / (classes - 1)
        }
        return out
    }

    @Test
    fun colapsaRepeticoesETiraOBranco() {
        // a a _ a b b _ ç → "aabç": o branco separa o "a" repetido do colapsado.
        val line = decoder.decode(oneHot(listOf(1, 1, 0, 1, 2, 2, 0, 3)), 0, 8, decoder.classCount)
        assertEquals("aabç", line.text)
        assertEquals(0.9f, line.score, 1e-5f)
    }

    @Test
    fun espacoEAUltimaClasse() {
        val line = decoder.decode(oneHot(listOf(1, 4, 2)), 0, 3, decoder.classCount)
        assertEquals("a b", line.text)
    }

    @Test
    fun soBrancoNaoTemTextoNemPontuacao() {
        val line = decoder.decode(oneHot(listOf(0, 0, 0)), 0, 3, decoder.classCount)
        assertEquals("", line.text)
        assertEquals(0f, line.score, 0f)
    }

    @Test
    fun decodificaCadaItemDoLote() {
        val a = oneHot(listOf(1, 0, 2))
        val b = oneHot(listOf(3, 3, 4))
        val both = a + b
        assertEquals("ab", decoder.decode(both, 0, 3, decoder.classCount).text)
        assertEquals("ç ", decoder.decode(both, 1, 3, decoder.classCount).text)
    }

    @Test
    fun saidaQueNaoESoftmaxRecebeSoftmaxParaAPontuacao() {
        val classes = decoder.classCount
        val logits = FloatArray(classes) { if (it == 1) 4f else 0f } // logits crus, soma != 1
        val line = decoder.decode(logits, 0, 1, classes)
        assertEquals("a", line.text)
        val expected = (Math.exp(4.0) / (Math.exp(4.0) + (classes - 1))).toFloat()
        assertEquals(expected, line.score, 1e-5f)
    }

    @Test
    fun numeroDeClassesDiferenteDoDicionarioEErro() {
        try {
            decoder.decode(FloatArray(10), 0, 2, 5 + 1)
            fail("o modelo e o dicionário não batem")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("classes"))
        }
    }

    @Test
    fun dicionarioSoQuebraEmNovaLinhaENaoEmSeparadorUnicode() {
        val dict = CtcDecoder.fromDictionary("a\nb\n \nç\r\n")
        // 4 entradas (o U+2028 é uma entrada, não uma quebra; o \r do fim some; a linha vazia final não conta) + branco + espaço.
        assertEquals(4 + 2, dict.classCount)
    }

    @Test
    fun dicionarioDoPaddleOcrLatinoTem504Classes() {
        // O dict.txt real tem 502 linhas; o rec.onnx real tem 504 classes de saída (branco + 502 + espaço).
        val text = (1..502).joinToString("\n") { "x$it" } + "\n"
        assertEquals(504, CtcDecoder.fromDictionary(text).classCount)
    }
}
