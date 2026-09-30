package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O que dá para provar do motor real sem carregar um modelo: a tradução de falhas e o texto de cada trecho. */
class LiteRtBackendTest {

    @Test
    fun `a device with no native library for its ABI gets a message that says so`() {
        val failure = LiteRtBackend.loadFailure(UnsatisfiedLinkError("dlopen failed: library liblitertlm_jni.so not found"))
        assertThat(failure.kind).isEqualTo(ReasoningErrorKind.ENGINE)
        assertThat(failure.message).contains("arm64")
    }

    @Test
    fun `a missing class is the same unsupported-device case`() {
        assertThat(LiteRtBackend.loadFailure(NoClassDefFoundError("com/google/ai/edge/litertlm/Engine")).kind)
            .isEqualTo(ReasoningErrorKind.ENGINE)
    }

    @Test
    fun `an out of memory error keeps its own kind so the user is told to free memory`() {
        assertThat(LiteRtBackend.loadFailure(OutOfMemoryError("x")).kind).isEqualTo(ReasoningErrorKind.OOM)
    }

    @Test
    fun `any other load failure is an engine error carrying the reason`() {
        val failure = LiteRtBackend.loadFailure(IllegalStateException("modelo corrompido"))
        assertThat(failure.kind).isEqualTo(ReasoningErrorKind.ENGINE)
        assertThat(failure.message).contains("modelo corrompido")
    }

    @Test
    fun `the text of a streamed chunk is what the chunk prints as`() {
        assertThat(LiteRtBackend.textOf("olá")).isEqualTo("olá")
        assertThat(LiteRtBackend.textOf(object { override fun toString() = " mundo" })).isEqualTo(" mundo")
    }

    @Test
    fun `an empty or missing chunk yields nothing to send`() {
        assertThat(LiteRtBackend.textOf("")).isEmpty()
        assertThat(LiteRtBackend.textOf(null)).isEmpty()
    }
}
