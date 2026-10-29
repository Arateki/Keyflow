package com.keyflow.speech.cloud

/** Um WAV PCM 16-bit lido na mão (sem biblioteca): taxa, canais e onde começam as amostras. */
internal class WavInfo(val sampleRate: Int, val channels: Int, val dataOffset: Int, val dataLength: Int)

internal object WavAudio {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    private fun tag(b: ByteArray, o: Int, t: String) = o + 4 <= b.size && String(b, o, 4, Charsets.ISO_8859_1) == t

    /** Nulo se não for WAV PCM de 16 bits. Dado de tamanho 0 ou maior que o arquivo (WAV em streaming) vale até o fim. */
    fun parse(b: ByteArray): WavInfo? {
        if (b.size < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return null
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var format = 0
        while (pos + 8 <= b.size) {
            val size = u32(b, pos + 4)
            val body = pos + 8
            if (tag(b, pos, "fmt ") && body + 16 <= b.size) {
                format = u16(b, body)
                channels = u16(b, body + 2)
                rate = u32(b, body + 4)
                bits = u16(b, body + 14)
            } else if (tag(b, pos, "data")) {
                if ((format != 1 && format != 0xFFFE) || bits != 16 || channels < 1 || rate <= 0) return null
                val available = b.size - body
                val length = if (size <= 0 || size > available) available else size
                return WavInfo(rate, channels, body, length)
            }
            if (size < 0 || size > b.size) return null
            pos = body + size + (size and 1)
        }
        return null
    }

    /** As amostras do WAV em mono 16-bit: mais de um canal vira a média. */
    fun toMono(b: ByteArray, info: WavInfo): ByteArray {
        val frame = 2 * info.channels
        val frames = info.dataLength / frame
        if (info.channels == 1) return b.copyOfRange(info.dataOffset, info.dataOffset + frames * 2)
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until info.channels) {
                val o = info.dataOffset + f * frame + c * 2
                sum += (u16(b, o) shl 16) shr 16
            }
            val avg = sum / info.channels
            out[f * 2] = avg.toByte()
            out[f * 2 + 1] = (avg shr 8).toByte()
        }
        return out
    }
}
