package com.keyflow.ime.keyboard.dictionary

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/** O dicionário de português que vai no app (`assets/dict/pt.tsv`), com erros de digitação de verdade. */
class RealDictionaryTest {

    private val suggester by lazy {
        val file = File("src/main/assets/dict/pt.tsv").takeIf { it.isFile } ?: File("app/src/main/assets/dict/pt.tsv")
        Suggester(file.bufferedReader().useLines { WordList.parse(it.toList().asSequence()) })
    }

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
    fun `the three dictionaries arrive already sorted, so the phone never sorts them`() {
        listOf("pt", "en", "es").forEach { lang ->
            val file = File("src/main/assets/dict/$lang.tsv").takeIf { it.isFile } ?: File("app/src/main/assets/dict/$lang.tsv")
            val words = file.bufferedReader().useLines { WordList.parse(it) }
            assertWithMessage(lang).that(words.arrivedSorted).isTrue()
            assertWithMessage(lang).that(words.size).isEqualTo(100_000)
        }
    }
}
