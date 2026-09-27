package com.keyflow.transcription.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * NAT-02: o decodificador real do aparelho (MediaExtractor + MediaCodec) em dois tons de 440 Hz, 1 s,
 * amplitude 0,5 (`aevalsrc`; o `sine` do ffmpeg sai a 1/8 de amplitude) — um já no formato da gravação (16 kHz mono) e outro em 44,1 kHz estéreo, que passa
 * pelo downmix e pelo resample. Fixtures geradas com ffmpeg (`app/src/androidTest/assets/audio/`).
 */
@RunWith(AndroidJUnit4::class)
class AudioDecoderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun fixture(name: String): File {
        val file = File(instrumentation.targetContext.cacheDir, name)
        instrumentation.context.assets.open("audio/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun assertIsTheTone(samples: FloatArray) {
        // O AAC acrescenta amostras de priming/padding nas bordas: 1 s decodificado fica perto de 16000.
        assertTrue("${samples.size} amostras", samples.size in 15_000..18_500)
        assertTrue(samples.all { it in -1f..1f })
        val rms = sqrt(samples.map { it * it }.average())
        // Tom de amplitude 0,5 tem RMS ≈ 0,354; folga para as bordas e a compressão.
        assertTrue("rms $rms", rms in 0.25..0.45)
        assertEquals(440.0, toneFrequency(samples), 440.0 * 0.02)
    }

    /**
     * Frequência por cruzamentos com histerese (só vira depois de passar de ±0,1) e medida entre a
     * primeira e a última virada: o priming/padding do AAC tem um chiado baixo que cruza o zero muitas
     * vezes e inflava a contagem simples (564 Hz num tom de 440 — conferido decodificando com ffmpeg).
     */
    private fun toneFrequency(samples: FloatArray): Double {
        var state = 0
        val flips = mutableListOf<Int>()
        samples.forEachIndexed { i, x ->
            if (x > 0.1f && state <= 0) { state = 1; flips += i }
            else if (x < -0.1f && state >= 0) { state = -1; flips += i }
        }
        val seconds = (flips.last() - flips.first()) / AudioDecoder.TARGET_SAMPLE_RATE.toDouble()
        return (flips.size - 1) / 2.0 / seconds
    }

    @Test
    fun decodesTheRecordingFormatDirectly() {
        assertIsTheTone(AudioDecoder.decodeToMono16k(fixture("tone-440hz-16k-mono.m4a")))
    }

    @Test
    fun downmixesAndResamplesOtherFormats() {
        assertIsTheTone(AudioDecoder.decodeToMono16k(fixture("tone-440hz-44k-stereo.m4a")))
    }
}
