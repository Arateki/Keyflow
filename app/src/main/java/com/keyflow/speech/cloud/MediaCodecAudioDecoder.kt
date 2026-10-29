package com.keyflow.speech.cloud

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import com.keyflow.speech.SpeechFormat
import java.io.ByteArrayOutputStream

/** mp3, opus, aac e flac pelo `MediaCodec` do sistema (FCLD-05), sem biblioteca nova. O corpo inteiro é decodificado de uma vez. */
class MediaCodecAudioDecoder : CompressedAudioDecoder {
    private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }

    override fun decode(bytes: ByteArray, format: SpeechFormat): DecodedPcm? {
        if (bytes.isEmpty()) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(BytesSource(bytes))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inFormat, null, null, 0)
            codec.start()
            run(codec, extractor, inFormat)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun run(codec: MediaCodec, extractor: MediaExtractor, inFormat: MediaFormat): DecodedPcm? {
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputDone = false
        var outputDone = false
        var idle = 0
        while (!outputDone && idle < MAX_IDLE) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index >= 0 -> {
                    idle = 0
                    val buffer = codec.getOutputBuffer(index)!!
                    val chunk = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(chunk)
                    out.write(chunk)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                else -> idle++
            }
        }
        val pcm = out.toByteArray()
        if (pcm.isEmpty() || channels < 1) return null
        return DecodedPcm(downmix(pcm, channels), rate)
    }

    private fun downmix(pcm: ByteArray, channels: Int): ByteArray {
        if (channels == 1) return pcm
        val frames = pcm.size / (2 * channels)
        val mono = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val o = (f * channels + c) * 2
                sum += ((pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8))
            }
            val avg = sum / channels
            mono[f * 2] = avg.toByte()
            mono[f * 2 + 1] = (avg shr 8).toByte()
        }
        return mono
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE = 500
    }
}
