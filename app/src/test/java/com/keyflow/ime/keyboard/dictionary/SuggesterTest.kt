package com.keyflow.ime.keyboard.dictionary

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Sugestões e troca no espaço sobre um dicionário pequeno, no formato dos `assets/dict/<idioma>.tsv`. */
class SuggesterTest {

    private val words = WordList.parse(
        sequenceOf(
            "de\t221", "não\t183", "está\t168", "esta\t160", "você\t139", "obrigado\t120", "obrigada\t95",
            "teclado\t90", "tecla\t70", "roma\t40", "Roma\t110", "Brasil\t130", "casa\t150", "caso\t140",
            "cada\t145", "sexo\t151\to", "crianças\t143\to", "palavra\t100", "paralelo\t60",
        ),
    )
    private val suggester = Suggester(words)

    @Test
    fun `a missing accent is put back and applied on space`() {
        val result = suggester.suggest("nao")
        assertThat(result.autoCorrect).isEqualTo("não")
        assertThat(result.words.first()).isEqualTo("não")
    }

    @Test
    fun `the typed word stays among the suggestions when it will be replaced`() {
        assertThat(suggester.suggest("nao").words).contains("nao")
    }

    @Test
    fun `a neighbor key typo is corrected`() {
        assertThat(suggester.suggest("teclsdo").autoCorrect).isEqualTo("teclado")
    }

    @Test
    fun `a word in the dictionary is never replaced`() {
        assertThat(suggester.suggest("esta").autoCorrect).isNull()
    }

    @Test
    fun `a valid word still offers the more frequent accented form`() {
        assertThat(suggester.suggest("esta").words).contains("está")
    }

    @Test
    fun `completions come from the prefix, most frequent first`() {
        assertThat(suggester.suggest("obri").words).containsAtLeast("obrigado", "obrigada").inOrder()
    }

    @Test
    fun `the typed capitalization is kept`() {
        assertThat(suggester.suggest("Nao").autoCorrect).isEqualTo("Não")
        assertThat(suggester.suggest("NAO").autoCorrect).isEqualTo("NÃO")
    }

    @Test
    fun `a proper noun known only capitalized gets its capital letter`() {
        assertThat(suggester.suggest("brasil").autoCorrect).isEqualTo("Brasil")
    }

    @Test
    fun `a word that exists in lowercase keeps it even if a capitalized form exists`() {
        assertThat(suggester.suggest("roma").autoCorrect).isNull()
    }

    @Test
    fun `offensive-flagged words are never offered as corrections or completions`() {
        assertThat(suggester.suggest("sexi").words).doesNotContain("sexo")
        assertThat(suggester.suggest("cria").words).doesNotContain("crianças")
    }

    @Test
    fun `offensive-flagged words still get their accent back`() {
        assertThat(suggester.suggest("criancas").autoCorrect).isEqualTo("crianças")
    }

    @Test
    fun `an ambiguous typo is suggested but not replaced`() {
        // "cas": "casa" e "caso" estão ambos a 1 letra, com frequências próximas; nenhum ganha com folga.
        val result = suggester.suggest("cas")
        assertThat(result.autoCorrect).isNull()
        assertThat(result.words).contains("casa")
    }

    @Test
    fun `one wrong key that clearly points to a frequent word is replaced`() {
        // "cass" → "casa" (1 tecla vizinha) ganha de "cada"/"caso" (2 toques) com folga.
        assertThat(suggester.suggest("cass").autoCorrect).isEqualTo("casa")
    }

    @Test
    fun `single letters and words with digits are left alone`() {
        assertThat(suggester.suggest("x").autoCorrect).isNull()
        assertThat(suggester.suggest("nao2").autoCorrect).isNull()
        assertThat(suggester.suggest("nao2").words).isEmpty()
    }

    @Test
    fun `at most three suggestions`() {
        assertThat(suggester.suggest("ca").words.size).isAtMost(3)
    }

    @Test
    fun `fold strips accents and case`() {
        assertThat(WordList.fold("Ação")).isEqualTo("acao")
        assertThat(WordList.fold("Niño")).isEqualTo("nino")
    }
}
