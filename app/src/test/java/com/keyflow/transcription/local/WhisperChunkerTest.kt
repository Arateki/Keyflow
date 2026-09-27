package com.keyflow.transcription.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** NM-T8: cortes do áudio longo para o Whisper (que descartaria o que passa de 30 s). */
class WhisperChunkerTest {

    private val rate = 16_000

    private fun tone(seconds: Double, silences: List<ClosedFloatingPointRange<Double>> = emptyList()) =
        FloatArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            if (silences.any { t in it }) 0f else (0.5 * sin(2 * PI * 220 * t)).toFloat()
        }

    @Test
    fun `up to the window it stays whole`() {
        val audio = tone(WhisperChunker.MAX_SECONDS.toDouble())

        assertThat(WhisperChunker.split(audio, rate)).containsExactly(audio)
    }

    @Test
    fun `long audio is cut in the pause and nothing is lost`() {
        val audio = tone(60.0, silences = listOf(25.5..26.0, 52.0..52.5))

        val chunks = WhisperChunker.split(audio, rate)

        assertThat(chunks).hasSize(3)
        chunks.forEach { assertThat(it.size).isAtMost(WhisperChunker.MAX_SECONDS * rate) }
        val firstCut = chunks[0].size / rate.toDouble()
        assertThat(firstCut).isAtLeast(25.5)
        assertThat(firstCut).isAtMost(26.0)
        val secondCut = (chunks[0].size + chunks[1].size) / rate.toDouble()
        assertThat(secondCut).isAtLeast(52.0)
        assertThat(secondCut).isAtMost(52.5)
        assertThat(chunks.reduce { a, b -> a + b }).isEqualTo(audio)
    }

    @Test
    fun `without a pause it still cuts inside the window`() {
        val audio = tone(90.0)

        val chunks = WhisperChunker.split(audio, rate)

        chunks.forEach { assertThat(it.size).isAtMost(WhisperChunker.MAX_SECONDS * rate) }
        assertThat(chunks.sumOf { it.size }).isEqualTo(audio.size)
    }
}
