package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O DeepSeek R1 (`.litertlm`) tem o prompt do modelo terminando em `<｜Assistant｜><think>\n`: a saída começa dentro do
 * pensamento e só traz o `</think>`. Saídas simuladas nesse formato — sem o modelo real.
 */
class ThoughtStripperTest {

    private fun run(opensThink: Boolean, vararg chunks: String): Pair<String, Boolean> {
        val s = ThoughtStripper(opensThink)
        val out = chunks.joinToString("") { s.accept(it) }
        return out to s.closed
    }

    @Test
    fun `everything up to the first closing tag is dropped and the answer follows`() {
        val (text, closed) = run(true, "Ok, o usuário quer um olá. ", "Vou ser breve.\n", "</think>", "\n\nOlá! Tudo bem?")
        assertThat(text).isEqualTo("Olá! Tudo bem?")
        assertThat(closed).isTrue()
    }

    @Test
    fun `a closing tag split across chunks is still found`() {
        val (text, closed) = run(true, "pensando</th", "ink>\nOlá", " mundo")
        assertThat(text).isEqualTo("Olá mundo")
        assertThat(closed).isTrue()
    }

    @Test
    fun `the answer in the same chunk as the closing tag is kept`() {
        assertThat(run(true, "pensei.</think>\n\nSim.").first).isEqualTo("Sim.")
    }

    @Test
    fun `without a closing tag nothing is shown and the stream is not closed`() {
        val (text, closed) = run(true, "Ok, vou pensar", " bastante e nunca respondo", "...")
        assertThat(text).isEmpty()
        assertThat(closed).isFalse()
    }

    @Test
    fun `an empty answer after the closing tag stays empty but closed`() {
        val (text, closed) = run(true, "pensei</think>", "\n\n")
        assertThat(text).isEmpty()
        assertThat(closed).isTrue()
    }

    @Test
    fun `a second closing tag inside the answer is not touched`() {
        assertThat(run(true, "a</think>b</think>c").first).isEqualTo("b</think>c")
    }

    @Test
    fun `models that open the think block themselves pass through untouched`() {
        val (text, closed) = run(false, "  Olá", ", mundo")
        assertThat(text).isEqualTo("  Olá, mundo")
        assertThat(closed).isTrue()
    }
}
