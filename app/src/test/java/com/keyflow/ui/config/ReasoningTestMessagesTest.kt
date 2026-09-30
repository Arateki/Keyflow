package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.UnavailableReason
import org.junit.Test

/** RTS-04: cada erro tem uma mensagem legível e própria — dois erros com o mesmo texto esconderiam a diferença do usuário. */
class ReasoningTestMessagesTest {

    @Test
    fun `every reasoning error has its own readable message`() {
        val messages = ReasoningError.entries.map { ReasoningTestMessages.error(it) }
        assertThat(messages.all { it.isNotBlank() }).isTrue()
        assertThat(messages).containsNoDuplicates()
    }

    /** "Entre de novo" não resolve quem não é elegível: a mensagem explica em vez de mandar repetir o login. */
    @Test
    fun `the not eligible message explains instead of asking to sign in again`() {
        val text = ReasoningTestMessages.error(ReasoningError.NOT_ELIGIBLE).lowercase()
        assertThat(text).doesNotContain("entre de novo")
        assertThat(text).contains("chave de api")
    }

    @Test
    fun `every unavailable reason has a message`() {
        UnavailableReason.entries.forEach { assertThat(ReasoningTestMessages.unavailable(it).isNotBlank()).isTrue() }
    }
}
