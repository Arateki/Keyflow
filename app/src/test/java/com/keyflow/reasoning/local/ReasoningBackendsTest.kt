package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** RRUN-08: qual biblioteca roda cada modelo — escolhida num lugar só, sem o resto do app saber. */
class ReasoningBackendsTest {

    private fun model(id: String, engine: String = "LITERT_LM") = LlmModel(id, "/x/$id", engine, 0, 0)

    @Test
    fun `the debug echo id gets the echo backend, only in debug builds`() {
        // Os testes unitários rodam no build de debug.
        assertThat(ReasoningBackends.create(model(ECHO_MODEL_ID), cacheDir = null)).isInstanceOf(EchoBackend::class.java)
    }

    @Test
    fun `a LiteRT-LM model gets the LiteRT-LM backend`() {
        assertThat(ReasoningBackends.create(model("qwen3-0.6b-litertlm"), cacheDir = null)).isInstanceOf(LiteRtBackend::class.java)
    }

    @Test
    fun `an engine type nobody integrated gets a backend that says so instead of pretending`() {
        val backend = ReasoningBackends.create(model("x", engine = "WHISPER"), cacheDir = null)
        assertThat(backend).isNotInstanceOf(LiteRtBackend::class.java)
        assertThat(backend).isNotInstanceOf(EchoBackend::class.java)
        val failure = runCatching { backend.load(model("x", "WHISPER")) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(ReasoningFailure::class.java)
        assertThat((failure as ReasoningFailure).kind).isEqualTo(ReasoningErrorKind.ENGINE)
    }

    @Test
    fun `the echo model never reaches the real engine even when it claims to be LiteRT-LM`() {
        assertThat(ReasoningBackends.create(model(ECHO_MODEL_ID, "LITERT_LM"), cacheDir = null)).isInstanceOf(EchoBackend::class.java)
    }
}
