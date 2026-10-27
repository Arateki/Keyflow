package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LiteRtTextFormatTest {
    @Test fun `template antigo mantem uma parte e nao tenta o formato alternativo`() {
        var calls = 0
        val format = LiteRtTextFormat.detect { parts -> calls++; "<user>${parts.single()}</user>" }
        assertThat(format).isEqualTo(LiteRtTextFormat.SINGLE)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun `template que perde string recebe duas partes tipadas`() {
        val format = LiteRtTextFormat.detect { parts -> if (parts.size == 1) "<user></user>" else parts.joinToString("") }
        assertThat(format).isEqualTo(LiteRtTextFormat.PARTS)
    }

    @Test fun `erro ao renderizar uma string tambem permite tentar partes`() {
        val format = LiteRtTextFormat.detect { parts -> check(parts.size == 2); parts.joinToString("") }
        assertThat(format).isEqualTo(LiteRtTextFormat.PARTS)
    }

    @Test fun `nao aceita template que descarta os dois formatos`() {
        assertThrows(IllegalStateException::class.java) { LiteRtTextFormat.detect { "<user></user>" } }
    }

    @Test fun `montagem preserva todos os caracteres sem separar pares Unicode`() {
        val text = "  oi João 👨🏽‍💻\namanhã às três <think> conteúdo literal  "
        LiteRtTextFormat.entries.forEach { format ->
            assertThat(format.parts(text).joinToString("")).isEqualTo(text)
            assertThat(format.parts(text).first()).isEqualTo(text)
        }
    }

    @Test fun `partes nao acrescentam espacos nem instrucoes mesmo no texto vazio`() {
        assertThat(LiteRtTextFormat.PARTS.parts("")).containsExactly("", "").inOrder()
        assertThat(LiteRtTextFormat.SINGLE.parts("")).containsExactly("")
    }
}
