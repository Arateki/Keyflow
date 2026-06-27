package com.keyflow.data

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.TranscriptionSource
import org.junit.Test

/** Resolução de presets de transcrição (VOZ-02/03/05/06). Lógica pura, sem Context. */
class EffectiveConfigTest {

    @Test
    fun `openai resolves to fixed endpoint and bearer token`() {
        val settings = Settings(
            source = TranscriptionSource.OPENAI,
            openaiToken = "sk-abc",
            openaiModel = "whisper-1",
            openaiLanguage = "pt",
        )

        val ready = settings.effectiveConfigFor() as EffectiveConfig.Ready
        assertThat(ready.config.endpointUrl).isEqualTo(Settings.OPENAI_ENDPOINT)
        assertThat(ready.config.authHeaderName).isEqualTo("Authorization")
        assertThat(ready.config.authHeaderValue).isEqualTo("Bearer sk-abc")
        assertThat(ready.config.model).isEqualTo("whisper-1")
        assertThat(ready.config.language).isEqualTo("pt")
        assertThat(ready.config.isComplete()).isTrue()
    }

    @Test
    fun `groq resolves to fixed endpoint and model`() {
        val settings = Settings(source = TranscriptionSource.GROQ, groqToken = "gsk-xyz")

        val ready = settings.effectiveConfigFor() as EffectiveConfig.Ready
        assertThat(ready.config.endpointUrl).isEqualTo(Settings.GROQ_ENDPOINT)
        assertThat(ready.config.authHeaderValue).isEqualTo("Bearer gsk-xyz")
        assertThat(ready.config.model).isEqualTo(Settings.GROQ_MODEL)
    }

    @Test
    fun `custom resolves to the manual fields verbatim`() {
        val settings = Settings(
            source = TranscriptionSource.CUSTOM,
            customEndpoint = "https://self.host/v1/audio/transcriptions",
            customHeaderName = "X-Api-Key",
            customHeaderValue = "raw-key",
            customModel = "whisper-large-v3",
            customLanguage = "es",
        )

        val ready = settings.effectiveConfigFor() as EffectiveConfig.Ready
        assertThat(ready.config).isEqualTo(
            Config(
                endpointUrl = "https://self.host/v1/audio/transcriptions",
                authHeaderName = "X-Api-Key",
                authHeaderValue = "raw-key",
                model = "whisper-large-v3",
                language = "es",
            )
        )
    }

    @Test
    fun `credits is unavailable`() {
        val settings = Settings(source = TranscriptionSource.CREDITS)
        assertThat(settings.effectiveConfigFor()).isEqualTo(EffectiveConfig.Unavailable)
    }

    @Test
    fun `byo preset without token is incomplete and cannot transcribe`() {
        val settings = Settings(source = TranscriptionSource.OPENAI, openaiToken = "")
        val ready = settings.effectiveConfigFor() as EffectiveConfig.Ready
        assertThat(ready.config.authHeaderValue).isEmpty()
        assertThat(ready.config.isComplete()).isFalse()
    }

    @Test
    fun `effectiveConfigFor honors an explicit source over the stored one`() {
        val settings = Settings(source = TranscriptionSource.CUSTOM, groqToken = "gsk-1")
        val ready = settings.effectiveConfigFor(TranscriptionSource.GROQ) as EffectiveConfig.Ready
        assertThat(ready.config.endpointUrl).isEqualTo(Settings.GROQ_ENDPOINT)
    }
}
