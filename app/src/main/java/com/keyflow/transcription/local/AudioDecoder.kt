package com.keyflow.transcription.local

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * `.m4a` (AAC) → PCM mono float em 16 kHz, a entrada dos modelos do sherpa-onnx (NAT-02). Roda no
 * processo `:inference`, antes da inferência. A gravação já sai em 16 kHz mono; downmix e resample
 * existem para qualquer arquivo que chegue diferente ([PcmMath]).
 */
object AudioDecoder {

    const val TARGET_SAMPLE_RATE = 16_000

    private const val TIMEOUT_US = 10_000L

    /** @throws IOException se o arquivo não tiver faixa de áudio decodificável. */
    fun decodeToMono16k(file: File): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("sem faixa de áudio em ${file.name}")
            extractor.selectTrack(track)
            val decoded = decode(extractor, extractor.getTrackFormat(track))
            val mono = PcmMath.downmixToMono(decoded.samples, decoded.channels)
            return PcmMath.resample(mono, decoded.sampleRate, TARGET_SAMPLE_RATE)
        } finally {
            extractor.release()
        }
    }

    private class Decoded(val samples: FloatArray, val sampleRate: Int, val channels: Int)

    private fun decode(extractor: MediaExtractor, inputFormat: MediaFormat): Decoded {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        // O formato de saída real chega em INFO_OUTPUT_FORMAT_CHANGED; o de entrada é só o ponto de partida.
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val out = GrowableFloatArray()
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        if (floatPcm) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.hasRemaining()) out.add(floats.get())
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.hasRemaining()) out.add(PcmMath.pcm16ToFloat(shorts.get()))
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return Decoded(out.toArray(), sampleRate, channels)
    }

    /** Cresce por dobra — evita um `List<Float>` com boxing de centenas de milhares de amostras. */
    private class GrowableFloatArray {
        private var data = FloatArray(1 shl 16)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
