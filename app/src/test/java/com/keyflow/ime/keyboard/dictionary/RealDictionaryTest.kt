package com.keyflow.ime.keyboard.dictionary

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/** Os dicionários que vão no app (`assets/dict/<idioma>.kfd`), com erros de digitação de verdade. */
class RealDictionaryTest {

    private fun text(lang: String): WordList =
        javaClass.getResourceAsStream("/dict/$lang.tsv")!!.bufferedReader().useLines { WordList.parse(it) }

    private fun binary(lang: String): WordList {
        val file = File("src/main/assets/dict/$lang.kfd").takeIf { it.isFile } ?: File("app/src/main/assets/dict/$lang.kfd")
        return WordList.readBinary(file.readBytes())
    }

    // O mesmo binário que o app carrega.
    private val suggester by lazy { Suggester(binary("pt")) }

    @Test
    fun `common portuguese slips are fixed on space`() {
        mapOf(
            "nao" to "não",
            "voce" to "você",
            "obrigdo" to "obrigado",
            "teclsdo" to "teclado",
            "entao" to "então",
            "tambem" to "também",
            "brasil" to "Brasil",
            "api" to "API",
        ).forEach { (typed, expected) ->
            assertWithMessage(typed).that(suggester.suggest(typed).autoCorrect).isEqualTo(expected)
        }
    }

    @Test
    fun `real words are left as typed`() {
        listOf("esta", "casa", "carro", "de", "teste", "linux", "você", "então").forEach { typed ->
            assertWithMessage(typed).that(suggester.suggest(typed).autoCorrect).isNull()
        }
    }

    @Test
    fun `one suggestion takes a few milliseconds`() {
        val typed = listOf("obrigdo", "teclsdo", "cadq", "pessosa", "trabslho", "paralelepipedo")
        typed.forEach { suggester.suggest(it) } // aquecimento do JIT
        val start = System.nanoTime()
        repeat(20) { typed.forEach { suggester.suggest(it) } }
        val perCallMs = (System.nanoTime() - start) / (20.0 * typed.size) / 1_000_000.0
        println("sugestão: %.2f ms por palavra (JVM do host)".format(perCallMs))
        assertThat(perCallMs).isLessThan(50.0)
    }

    @Test
    fun `the binary the app loads holds exactly the words of the text list, already sorted`() {
        listOf("pt", "en", "es").forEach { lang ->
            val text = text(lang)
            val bin = binary(lang)
            assertWithMessage("$lang ordenado").that(text.arrivedSorted).isTrue()
            assertWithMessage(lang).that(bin.size).isEqualTo(text.size)
            for (i in 0 until text.size) {
                if (text.word(i) != bin.word(i) || text.freq(i) != bin.freq(i) ||
                    text.foldedWord(i) != bin.foldedWord(i) || text.isOffensive(i) != bin.isOffensive(i)
                ) {
                    throw AssertionError("$lang[$i]: '${text.word(i)}' ≠ '${bin.word(i)}'")
                }
            }
        }
    }
}
