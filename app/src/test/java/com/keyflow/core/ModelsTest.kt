package com.keyflow.core

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.Config
import org.junit.Test

class ModelsTest {

    @Test
    fun `Config defaults are correct`() {
        val cfg = Config()
        assertThat(cfg.authHeaderName).isEqualTo("Authorization")
        assertThat(cfg.model).isEqualTo("whisper-1")
        assertThat(cfg.language).isNull()
        assertThat(cfg.recordingMode).isEqualTo(RecordingMode.TOGGLE)
        assertThat(cfg.endpointUrl).isEmpty()
        assertThat(cfg.authHeaderValue).isEmpty()
    }

    @Test
    fun `Config isComplete returns false when url or auth missing`() {
        assertThat(Config().isComplete()).isFalse()
        assertThat(Config(endpointUrl = "https://api.openai.com/v1/audio/transcriptions").isComplete()).isFalse()
        assertThat(Config(authHeaderValue = "Bearer sk-xxx").isComplete()).isFalse()
    }

    @Test
    fun `Config isComplete returns true when url and auth provided`() {
        val cfg = Config(
            endpointUrl = "https://api.openai.com/v1/audio/transcriptions",
            authHeaderValue = "Bearer sk-xxx"
        )
        assertThat(cfg.isComplete()).isTrue()
    }

    @Test
    fun `TranscriptionResult Success carries text`() {
        val result = TranscriptionResult.Success("hello world")
        assertThat((result as TranscriptionResult.Success).text).isEqualTo("hello world")
    }

    @Test
    fun `TranscriptionResult Error carries kind and message`() {
        val result = TranscriptionResult.Error(ErrorKind.AUTH, "401 Unauthorized")
        assertThat((result as TranscriptionResult.Error).kind).isEqualTo(ErrorKind.AUTH)
        assertThat(result.message).contains("401")
    }
}
