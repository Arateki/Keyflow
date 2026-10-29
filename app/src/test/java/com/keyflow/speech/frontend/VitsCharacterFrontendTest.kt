package com.keyflow.speech.frontend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Ids do frontend de caracteres contra o sherpa-onnx (o `tokens.txt` real, sem baixar o ONNX).
 * O mapa mini cobre blank, descarte e o corte da frase sem o vocabulário inteiro.
 */
class VitsCharacterFrontendTest {

    private val front = VitsCharacterFrontend.parse(resource("tokens.txt"))
    private val mini = VitsCharacterFrontend.parse(resource("tokens-mini.txt"))

    @Test
    fun `known phrases match the sherpa character frontend`() {
        assertThat(one("Oi.")).containsExactly(3, 18, 3, 12, 3, 124, 3).inOrder()
        assertThat(one("dois")).containsExactly(3, 7, 3, 18, 3, 12, 3, 22, 3).inOrder()
        assertThat(one("sete")).containsExactly(3, 22, 3, 8, 3, 23, 3, 8, 3).inOrder()
        assertThat(one("ONU")).containsExactly(3, 18, 3, 17, 3, 24, 3).inOrder()
        assertThat(one("abc€.")).containsExactly(3, 4, 3, 5, 3, 6, 3, 124, 3).inOrder()
        assertThat(one("a três dois")).containsExactly(
            3, 4, 3, 128, 3, 23, 3, 21, 3, 42, 3, 22, 3, 128, 3, 7, 3, 18, 3, 12, 3, 22, 3,
        ).inOrder()
        assertThat(one("tenho dois cães")).containsExactly(
            3, 23, 3, 8, 3, 17, 3, 11, 3, 18, 3, 128, 3, 7, 3, 18, 3, 12, 3, 22, 3, 128, 3, 6, 3, 36, 3, 8, 3, 22, 3,
        ).inOrder()
        assertThat(one("vinte e um")).containsExactly(
            3, 25, 3, 12, 3, 17, 3, 23, 3, 8, 3, 128, 3, 8, 3, 128, 3, 24, 3, 16, 3,
        ).inOrder()
    }

    @Test
    fun `punctuation splits the sentence and the next one starts clean`() {
        val ola = front.sentences("Olá! Este é um teste.")
        assertThat(ola).hasSize(2)
        assertThat(ola[0].toList()).containsExactly(3, 18, 3, 15, 3, 34, 3, 118, 3).inOrder()
        assertThat(ola[1].toList()).containsExactly(
            3, 128, 3, 8, 3, 22, 3, 23, 3, 8, 3, 128, 3, 41, 3, 128, 3, 24, 3, 16, 3, 128,
            3, 23, 3, 8, 3, 22, 3, 23, 3, 8, 3, 124, 3,
        ).inOrder()
        val acao = front.sentences("Ação: já?")
        assertThat(acao).hasSize(2)
        assertThat(acao[0].toList()).containsExactly(3, 4, 3, 39, 3, 36, 3, 18, 3, 125, 3).inOrder()
        assertThat(acao[1].toList()).containsExactly(3, 128, 3, 13, 3, 34, 3, 127, 3).inOrder()
        val dois = front.sentences("Um. Dois.")
        assertThat(dois[0].toList()).containsExactly(3, 24, 3, 16, 3, 124, 3).inOrder()
        assertThat(dois[1].toList()).containsExactly(3, 128, 3, 7, 3, 18, 3, 12, 3, 22, 3, 124, 3).inOrder()
    }

    @Test
    fun `a digit becomes the portuguese word before the character map`() {
        assertThat(front.sentences("2").map { it.toList() }).isEqualTo(front.sentences("dois").map { it.toList() })
        assertThat(one("2")).containsExactly(3, 7, 3, 18, 3, 12, 3, 22, 3).inOrder()
    }

    @Test
    fun `blank sits between tokens and an unknown character is dropped`() {
        // O ponto está no vocabulário: entra como token e também corta a frase.
        assertThat(mini.sentences("ab.", normalize = false).single().toList())
            .containsExactly(3, 4, 3, 5, 3, 6, 3).inOrder()
        assertThat(mini.sentences("a€b", normalize = false).single().toList())
            .containsExactly(3, 4, 3, 5, 3).inOrder()
        val split = mini.sentences("a.b", normalize = false)
        assertThat(split).hasSize(2)
        assertThat(split[0].toList()).containsExactly(3, 4, 3, 6, 3).inOrder()
        assertThat(split[1].toList()).containsExactly(3, 5, 3).inOrder()
        assertThat(mini.sentences("2").single().toList()).containsExactly(3, 8, 3, 9, 3, 10, 3, 11, 3).inOrder()
        assertThat(mini.sentences("2", normalize = false)).isEmpty()
    }

    @Test
    fun `eos and bos wrap a sentence only when the model asks for them`() {
        val wrapped = VitsCharacterFrontend.parse(resource("tokens-mini.txt"), addBlank = true, useEosBos = true)
        assertThat(wrapped.sentences("a", normalize = false).single().toList())
            .containsExactly(2, 3, 4, 3, 1).inOrder()
        val plain = VitsCharacterFrontend.parse(resource("tokens-mini.txt"), addBlank = false, useEosBos = false)
        assertThat(plain.sentences("ab", normalize = false).single().toList()).containsExactly(4, 5).inOrder()
    }

    private fun one(text: String): List<Int> = front.sentences(text).single().toList()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/vits/$name")) { name }.readText()
}
