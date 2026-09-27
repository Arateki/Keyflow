package com.keyflow.transcription.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** NAT-02: downmix e resample do decodificador — contas puras. */
class PcmMathTest {

    private fun sine(freq: Double, rate: Int, seconds: Double = 1.0, amplitude: Float = 0.5f) =
        FloatArray((rate * seconds).toInt()) { i -> amplitude * sin(2 * PI * freq * i / rate).toFloat() }

    /** Frequência dominante pela contagem de cruzamentos por zero — suficiente para um tom puro. */
    private fun frequencyOf(samples: FloatArray, rate: Int): Double {
        var crossings = 0
        for (i in 1 until samples.size) if ((samples[i - 1] < 0) != (samples[i] < 0)) crossings++
        return crossings / 2.0 / (samples.size.toDouble() / rate)
    }

    @Test
    fun `downmix averages the channels`() {
        val stereo = floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f)

        assertThat(PcmMath.downmixToMono(stereo, 2).toList()).containsExactly(0.5f, 0.5f, 0f).inOrder()
    }

    @Test
    fun `mono passes through untouched`() {
        val mono = floatArrayOf(0.1f, -0.2f)

        assertThat(PcmMath.downmixToMono(mono, 1)).isSameInstanceAs(mono)
        assertThat(PcmMath.resample(mono, 16_000, 16_000)).isSameInstanceAs(mono)
    }

    @Test
    fun `44_1 kHz to 16 kHz keeps duration and pitch`() {
        val out = PcmMath.resample(sine(440.0, 44_100), 44_100, 16_000)

        assertThat(out.size).isIn(15_990..16_000)
        assertThat(frequencyOf(out, 16_000)).isWithin(5.0).of(440.0)
    }

    @Test
    fun `downsampling keeps a constant level`() {
        val out = PcmMath.resample(FloatArray(4_410) { 0.25f }, 44_100, 16_000)

        out.forEach { assertThat(it).isWithin(1e-6f).of(0.25f) }
    }

    @Test
    fun `8 kHz to 16 kHz doubles the samples interpolating linearly`() {
        val out = PcmMath.resample(floatArrayOf(0f, 1f, 0f), 8_000, 16_000)

        assertThat(out.toList()).containsExactly(0f, 0.5f, 1f, 0.5f, 0f, 0f).inOrder()
    }

    @Test
    fun `empty input stays empty`() {
        assertThat(PcmMath.resample(FloatArray(0), 44_100, 16_000)).isEmpty()
    }

    @Test
    fun `pcm16 maps to the float range`() {
        assertThat(PcmMath.pcm16ToFloat(Short.MIN_VALUE)).isEqualTo(-1f)
        assertThat(PcmMath.pcm16ToFloat(0)).isEqualTo(0f)
        assertThat(PcmMath.pcm16ToFloat(Short.MAX_VALUE)).isLessThan(1f)
    }
}
