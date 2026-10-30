package com.keyflow.data

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.TranscriptionSource
import org.junit.Test

/**
 * `configDe(settings)` é o mapeamento das Settings para o [Config] do transcritor, extraído do `SettingsRepository.flow()` para o
 * coordinator e a porta de Ditar dos fluxos usarem a mesma função (FLX-T9): mesma `Settings`, mesma `Config` — para cada origem.
 */
class ConfigDeSettingsTest {

    private val credenciais = SettingsCredentialValues(openaiToken = "sk-abc", groqToken = "gsk-xyz")

    @Test
    fun `OpenAI e Groq viram o endpoint fixo com o token do cofre`() {
        val openai = configDe(Settings(source = TranscriptionSource.OPENAI, openaiModel = "whisper-1", openaiLanguage = "pt"), credenciais)
        assertThat(openai.endpointUrl).isEqualTo(Settings.OPENAI_ENDPOINT)
        assertThat(openai.authHeaderValue).isEqualTo("Bearer sk-abc")
        assertThat(openai.model).isEqualTo("whisper-1")
        assertThat(openai.language).isEqualTo("pt")
        val groq = configDe(Settings(source = TranscriptionSource.GROQ), credenciais)
        assertThat(groq.endpointUrl).isEqualTo(Settings.GROQ_ENDPOINT)
        assertThat(groq.authHeaderValue).isEqualTo("Bearer gsk-xyz")
    }

    @Test
    fun `Custom e a Config manual das Settings`() {
        val s = Settings(
            source = TranscriptionSource.CUSTOM, customEndpoint = "https://self.host/stt", customHeaderName = "X-Api-Key",
            customHeaderValue = "raw", customModel = "m", customLanguage = "es", transcriptionTimeoutMs = 45_000,
        )
        val config = configDe(s)
        assertThat(config).isEqualTo(
            Config(endpointUrl = "https://self.host/stt", authHeaderName = "X-Api-Key", authHeaderValue = "raw", model = "m", language = "es", timeoutMs = 45_000L),
        )
    }

    @Test
    fun `Local com modelo vira a Config local com o prazo da origem Local`() {
        val s = Settings(source = TranscriptionSource.LOCAL, localModelId = "parakeet-v3", transcriptionTimeoutMs = 30_000)
        val config = configDe(s)
        assertThat(config.local).isEqualTo(s.effectiveConfigFor(TranscriptionSource.LOCAL) as EffectiveConfig.Local)
        assertThat(config.timeoutMs).isEqualTo(Config.localTimeoutMs(30_000))
        assertThat(config.timeoutMs).isEqualTo(Config.LOCAL_MIN_TIMEOUT_MS)
    }

    @Test
    fun `Local sem modelo e CREDITS caem na Config vazia`() {
        assertThat(configDe(Settings(source = TranscriptionSource.LOCAL, localModelId = null))).isEqualTo(Config())
        assertThat(configDe(Settings(source = TranscriptionSource.CREDITS))).isEqualTo(Config())
    }

    @Test
    fun `sem credenciais o token vai vazio`() {
        assertThat(configDe(Settings(source = TranscriptionSource.OPENAI)).authHeaderValue).isEmpty()
    }
}
