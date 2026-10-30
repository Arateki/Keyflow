package com.keyflow.fluxos.execucao

import com.google.common.truth.Truth.assertThat
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** O texto longo da Voz em trechos por frase (FLX-22). */
class DivisorDeFalaTest {

    private fun semEspacos(s: String) = s.filterNot { it.isWhitespace() }

    private fun conferir(texto: String, trechos: List<String>, max: Int) {
        assertThat(trechos.all { it.isNotBlank() }).isTrue()
        assertThat(trechos.all { it.length <= max }).isTrue()
        assertThat(semEspacos(trechos.joinToString(""))).isEqualTo(semEspacos(texto)) // nem perde nem repete caractere
        assertThat(trechos.all { it == it.trim() }).isTrue()
        for (t in trechos) {
            // nenhum par substituto (emoji) partido ao meio
            assertThat(t.first().isLowSurrogate()).isFalse()
            assertThat(t.last().isHighSurrogate()).isFalse()
        }
    }

    @Test
    fun `texto curto e um trecho so, mesmo com varias frases`() {
        assertThat(DivisorDeFala.dividir("Olá.")).containsExactly("Olá.")
        assertThat(DivisorDeFala.dividir("Oi. Tudo bem? Sim!")).containsExactly("Oi. Tudo bem? Sim!")
    }

    @Test
    fun `vazio ou so espacos e lista vazia`() {
        assertThat(DivisorDeFala.dividir("")).isEmpty()
        assertThat(DivisorDeFala.dividir("   \n\t  ")).isEmpty()
    }

    @Test
    fun `o espaco das pontas some`() {
        assertThat(DivisorDeFala.dividir("  \n Olá, mundo.  \n")).containsExactly("Olá, mundo.")
    }

    @Test
    fun `5 mil caracteres viram trechos de ate 800 que remontam o texto`() {
        val frase = "Esta é uma frase de tamanho médio para o teste de divisão. "
        val texto = frase.repeat(5000 / frase.length + 1).take(5000)
        val trechos = DivisorDeFala.dividir(texto)
        assertThat(trechos.size).isAtLeast(7)
        conferir(texto, trechos, 800)
    }

    @Test
    fun `corta nas frases e nao no meio delas quando da`() {
        val frase = "Ponto final aqui, nesta frase de uns quarenta e tantos. " // 56
        val texto = frase.repeat(30)
        val trechos = DivisorDeFala.dividir(texto, max = 200)
        conferir(texto, trechos, 200)
        assertThat(trechos.all { it.endsWith(".") }).isTrue() // todo trecho termina no fim de uma frase
    }

    @Test
    fun `reconhece ponto, exclamacao, interrogacao e quebra de linha`() {
        val trechos = DivisorDeFala.dividir("Primeira! Segunda? Terceira.\nQuarta sem ponto\nQuinta.", max = 18)
        conferir("Primeira! Segunda? Terceira.\nQuarta sem ponto\nQuinta.", trechos, 18)
        assertThat(trechos).containsExactly("Primeira! Segunda?", "Terceira.", "Quarta sem ponto", "Quinta.").inOrder()
    }

    @Test
    fun `ponto no meio de um numero nao e fim de frase`() {
        // Se o "3." valesse como fim de frase, o primeiro trecho acabaria nele (21 caracteres cabem em 21).
        val texto = "a".repeat(18) + " 3.14 fim"
        val trechos = DivisorDeFala.dividir(texto, max = 21)
        assertThat(trechos).containsExactly("a".repeat(18), "3.14 fim").inOrder()
    }

    @Test
    fun `limite invalido e recusado`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { DivisorDeFala.dividir("oi", max = 0) }
    }

    @Test
    fun `aspas e parenteses depois do ponto ficam na mesma frase`() {
        val trechos = DivisorDeFala.dividir("Ele disse: “Vamos!” Depois saiu (rápido). Fim.", max = 25)
        conferir("Ele disse: “Vamos!” Depois saiu (rápido). Fim.", trechos, 25)
        assertThat(trechos[0]).isEqualTo("Ele disse: “Vamos!”")
    }

    @Test
    fun `frase maior que o limite e cortada no espaco, sem partir palavra`() {
        val texto = "palavra ".repeat(300).trim() // 2399 caracteres, sem pontuação
        val trechos = DivisorDeFala.dividir(texto)
        conferir(texto, trechos, 800)
        assertThat(trechos.all { t -> t.split(" ").all { it == "palavra" } }).isTrue()
    }

    @Test
    fun `texto sem pontuacao e sem espaco e cortado no limite`() {
        val texto = "a".repeat(2000)
        val trechos = DivisorDeFala.dividir(texto)
        assertThat(trechos.map { it.length }).containsExactly(800, 800, 400).inOrder()
        assertThat(trechos.joinToString("")).isEqualTo(texto)
    }

    @Test
    fun `emoji nao e partido no meio`() {
        val texto = "😀".repeat(1000) // 2000 unidades UTF-16
        for (max in listOf(5, 7, 799, 800)) {
            val trechos = DivisorDeFala.dividir(texto, max)
            conferir(texto, trechos, max)
            assertThat(trechos.joinToString("")).isEqualTo(texto)
        }
    }

    @Test
    fun `acento combinado nao e separado da letra`() {
        val texto = "é".repeat(500) // "é" decomposto, 2 unidades cada
        val trechos = DivisorDeFala.dividir(texto, max = 7)
        assertThat(trechos.joinToString("")).isEqualTo(texto)
        assertThat(trechos.none { it.first() == '́' }).isTrue()
    }

    @Test
    fun `palavras acentuadas e emojis no meio do texto passam inteiros`() {
        val texto = "Ação, coração e emoção 😀 em frases. ".repeat(80)
        val trechos = DivisorDeFala.dividir(texto, max = 100)
        conferir(texto, trechos, 100)
    }

    @Test
    fun `o limite padrao e 800`() {
        assertThat(DivisorDeFala.MAX_PADRAO).isEqualTo(800)
        val texto = "x".repeat(801)
        assertThat(DivisorDeFala.dividir(texto)).hasSize(2)
        assertThat(DivisorDeFala.dividir("x".repeat(800))).hasSize(1)
    }

    @Test
    fun `quebras de linha seguidas nao geram trecho vazio`() {
        val trechos = DivisorDeFala.dividir("Um.\n\n\nDois.\n \nTrês.", max = 5)
        assertThat(trechos).containsExactly("Um.", "Dois.", "Três.").inOrder()
    }

    @Test
    fun `um separador enorme entre frases nao estoura o limite do trecho`() {
        val texto = "Um." + " ".repeat(5000) + "Dois."
        val trechos = DivisorDeFala.dividir(texto)
        assertThat(trechos).containsExactly("Um.", "Dois.").inOrder()
    }

    @Test
    fun `propriedade - para qualquer texto o resultado respeita o limite e remonta o texto`() = runBlocking<Unit> {
        val pedacos = Arb.element(
            "Olá", "mundo", ".", "!", "?", "\n", " ", "  ", "ção", "é", "é", "😀", "👨‍👩‍👧", "🇧🇷", "3.14", "”", ")", "x".repeat(50), "y".repeat(900),
        )
        val textos = Arb.list(pedacos, 0..120).map { it.joinToString("") }
        checkAll(2_000, textos, Arb.int(8..900)) { texto, max ->
            val trechos = DivisorDeFala.dividir(texto, max)
            assertThat(trechos.all { it.isNotBlank() }).isTrue()
            assertThat(trechos.all { it.length <= max }).isTrue()
            assertThat(semEspacos(trechos.joinToString(""))).isEqualTo(semEspacos(texto))
            for (t in trechos) {
                assertThat(t.first().isLowSurrogate()).isFalse()
                assertThat(t.last().isHighSurrogate()).isFalse()
            }
        }
    }
}
