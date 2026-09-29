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

    @Test
    fun `transcriptionTimeoutMs is shared across providers, not per-preset`() {
        val settings = Settings(
            transcriptionTimeoutMs = 30_000,
            openaiToken = "sk-abc",
            groqToken = "gsk-1",
            customEndpoint = "https://self.host/v1/audio/transcriptions",
            customHeaderValue = "raw-key",
        )
        assertThat((settings.effectiveConfigFor(TranscriptionSource.OPENAI) as EffectiveConfig.Ready).config.timeoutMs)
            .isEqualTo(30_000L)
        assertThat((settings.effectiveConfigFor(TranscriptionSource.GROQ) as EffectiveConfig.Ready).config.timeoutMs)
            .isEqualTo(30_000L)
        assertThat((settings.effectiveConfigFor(TranscriptionSource.CUSTOM) as EffectiveConfig.Ready).config.timeoutMs)
            .isEqualTo(30_000L)
    }

    @Test
    fun `local source keeps its floor but an unlimited choice stays unlimited`() {
        assertThat(Config.localTimeoutMs(15_000L)).isEqualTo(Config.LOCAL_MIN_TIMEOUT_MS)
        assertThat(Config.localTimeoutMs(600_000L)).isEqualTo(600_000L)
        assertThat(Config.localTimeoutMs(Config.NO_TIMEOUT)).isEqualTo(Config.NO_TIMEOUT)
        assertThat(Config(timeoutMs = Config.NO_TIMEOUT).waitsForever).isTrue()
        assertThat(Config().waitsForever).isFalse()
    }

    @Test
    fun `transcriptionTimeoutMs defaults to Config's default`() {
        val settings = Settings(source = TranscriptionSource.OPENAI, openaiToken = "sk-abc")
        val ready = settings.effectiveConfigFor() as EffectiveConfig.Ready
        assertThat(ready.config.timeoutMs).isEqualTo(com.keyflow.data.Config.DEFAULT_TIMEOUT_MS)
    }

    // ── NM-T2: origem Local ────────────────────────────────────────────────────

    @Test
    fun `local with a selected model resolves to the on-device engine with its language`() {
        val settings = Settings(source = TranscriptionSource.LOCAL, localModelId = "whisper-base", localLanguage = "pt")

        assertThat(settings.effectiveConfigFor()).isEqualTo(EffectiveConfig.Local(modelId = "whisper-base", language = "pt"))
    }

    @Test
    fun `local without a language keeps it null for auto-detect`() {
        val settings = Settings(source = TranscriptionSource.LOCAL, localModelId = "parakeet")

        assertThat((settings.effectiveConfigFor() as EffectiveConfig.Local).language).isNull()
    }

    /** NAT-03: sem modelo, o mesmo caminho do Créditos — que termina em CONFIG_MISSING. */
    @Test
    fun `local without a model is unavailable`() {
        val settings = Settings(source = TranscriptionSource.LOCAL, localLanguage = "pt")

        assertThat(settings.effectiveConfigFor()).isEqualTo(EffectiveConfig.Unavailable)
    }

    @Test
    fun `choosing another source keeps the local model for when the user comes back`() {
        val settings = Settings(source = TranscriptionSource.OPENAI, openaiToken = "sk-abc", localModelId = "whisper-base")

        assertThat(settings.effectiveConfigFor()).isInstanceOf(EffectiveConfig.Ready::class.java)
        assertThat(settings.effectiveConfigFor(TranscriptionSource.LOCAL)).isInstanceOf(EffectiveConfig.Local::class.java)
    }
}
