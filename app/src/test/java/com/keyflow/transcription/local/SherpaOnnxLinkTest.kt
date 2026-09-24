package com.keyflow.transcription.local

import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NM-T1: o AAR do sherpa-onnx está no classpath e as configs que o engine vai montar (NM-T8) têm a
 * forma esperada. Só classes Kotlin puras — nada aqui carrega a biblioteca nativa, que a JVM de teste
 * nem teria.
 */
class SherpaOnnxLinkTest {

    @Test
    fun whisperConfigTakesEncoderDecoderAndLanguage() {
        val config = OfflineWhisperModelConfig(encoder = "enc.onnx", decoder = "dec.onnx", language = "pt")

        assertEquals("enc.onnx", config.encoder)
        assertEquals("dec.onnx", config.decoder)
        assertEquals("pt", config.language)
    }

    @Test
    fun transducerConfigTakesEncoderDecoderAndJoiner() {
        val config = OfflineTransducerModelConfig(encoder = "enc.onnx", decoder = "dec.onnx", joiner = "join.onnx")

        assertEquals("join.onnx", config.joiner)
    }
}
