package com.keyflow.transcription.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.ModelCatalog
import org.junit.Test
import java.io.File

/** NM-T8: a config do sherpa-onnx por tipo de engine (formas do exemplo oficial v1.13.8). */
class SherpaConfigsTest {

    private val dir = File("/m")

    @Test
    fun `whisper config`() {
        val model = EngineModel.from(ModelCatalog.WHISPER_SMALL, dir, "pt")
        val config = SherpaConfigs.recognizerConfig(model, numThreads = 3).modelConfig

        assertThat(config.modelType).isEqualTo("whisper")
        assertThat(config.whisper.encoder).isEqualTo(model.encoder)
        assertThat(config.whisper.decoder).isEqualTo(model.decoder)
        assertThat(config.whisper.language).isEqualTo("pt")
        assertThat(config.whisper.task).isEqualTo("transcribe")
        assertThat(config.tokens).isEqualTo(model.tokens)
        assertThat(config.numThreads).isEqualTo(3)
        assertThat(config.transducer.encoder).isEmpty()
    }

    @Test
    fun `whisper without a language hint auto detects`() {
        val model = EngineModel.from(ModelCatalog.WHISPER_BASE, dir, null)

        assertThat(SherpaConfigs.recognizerConfig(model, 1).modelConfig.whisper.language).isEmpty()
    }

    @Test
    fun `parakeet is a nemo transducer`() {
        val model = EngineModel.from(ModelCatalog.PARAKEET_V3, dir, null)
        val config = SherpaConfigs.recognizerConfig(model, numThreads = 4).modelConfig

        assertThat(config.modelType).isEqualTo("nemo_transducer")
        assertThat(config.transducer.encoder).isEqualTo(model.encoder)
        assertThat(config.transducer.decoder).isEqualTo(model.decoder)
        assertThat(config.transducer.joiner).isEqualTo(model.joiner)
        assertThat(config.tokens).isEqualTo(model.tokens)
        assertThat(config.whisper.encoder).isEmpty()
    }

    @Test
    fun `threads stay between one and four`() {
        assertThat(SherpaConfigs.defaultThreads()).isAtLeast(1)
        assertThat(SherpaConfigs.defaultThreads()).isAtMost(4)
    }
}
