package com.keyflow.speech.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WavAudioTest {
    @Test
    fun `parses rate channels and data`() {
        val bytes = wavBytes(frames = 100, rate = 22_050)
        val info = WavAudio.parse(bytes)!!
        assertThat(info.sampleRate).isEqualTo(22_050)
        assertThat(info.channels).isEqualTo(1)
        assertThat(info.dataLength).isEqualTo(200)
    }

    @Test
    fun `streaming wav with an oversized data field reads to the end`() {
        val info = WavAudio.parse(wavBytes(frames = 10, dataSizeField = 0x7FFFFFFF))!!
        assertThat(info.dataLength).isEqualTo(20)
    }

    @Test
    fun `stereo is averaged to mono`() {
        val bytes = wavBytes(frames = 5, channels = 2, value = 1000)
        val mono = WavAudio.toMono(bytes, WavAudio.parse(bytes)!!)
        assertThat(mono.size).isEqualTo(10)
        assertThat((mono[0].toInt() and 0xFF) or (mono[1].toInt() shl 8)).isEqualTo(1000)
    }

    @Test
    fun `anything that is not a 16-bit wav is rejected`() {
        assertThat(WavAudio.parse("{\"error\":1}".toByteArray())).isNull()
        assertThat(WavAudio.parse(ByteArray(3))).isNull()
    }
}
