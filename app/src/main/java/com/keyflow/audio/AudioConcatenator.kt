package com.keyflow.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Remuxa dois ou mais arquivos .m4a/AAC gravados pelo mesmo [AudioRecorder] (mesmo formato/trilha) em um
 * único arquivo contínuo. Não decodifica/recodifica — só copia as amostras já codificadas e desloca os
 * timestamps de cada segmento seguinte para colar logo depois do fim do anterior. Usado quando o Yan
 * cancela durante a transcrição (SENDING), desfaz e continua gravando (Q6, 2026-07-03): os dois trechos
 * viram um áudio só antes de uma nova transcrição, em vez de duas chamadas separadas.
 */
object AudioConcatenator {

    /** Funde [segments] (em ordem) num arquivo novo dentro de [outputDir]. Requer 2+ segmentos. */
    fun concat(segments: List<File>, outputDir: File): File {
        require(segments.size >= 2) { "concat precisa de pelo menos 2 segmentos, recebeu ${segments.size}" }
        outputDir.mkdirs()
        val output = File(outputDir, "keyflow-merged-${System.currentTimeMillis()}.m4a")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var muxerTrackIndex = -1
        var timeOffsetUs = 0L
        val bufferInfo = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(MAX_SAMPLE_SIZE)

        try {
            for (segment in segments) {
                val extractor = MediaExtractor()
                extractor.setDataSource(segment.absolutePath)
                try {
                    val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                        extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw IllegalStateException("Nenhuma trilha de áudio em ${segment.name}")
                    val format = extractor.getTrackFormat(trackIndex)
                    extractor.selectTrack(trackIndex)

                    if (muxerTrackIndex == -1) {
                        muxerTrackIndex = muxer.addTrack(format)
                        muxer.start()
                    }

                    var lastSampleTimeUs = 0L
                    while (true) {
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        lastSampleTimeUs = extractor.sampleTime
                        bufferInfo.set(0, size, timeOffsetUs + extractor.sampleTime, extractor.sampleFlags)
                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                    // Próximo segmento cola logo após o fim deste (estimativa de +1 frame — não precisa ser
                    // sample-accurate, só evitar sobrepor ou deixar um buraco perceptível).
                    timeOffsetUs += lastSampleTimeUs + FRAME_DURATION_ESTIMATE_US
                } finally {
                    extractor.release()
                }
            }
        } finally {
            if (muxerTrackIndex != -1) muxer.stop()
            muxer.release()
        }
        return output
    }

    private const val MAX_SAMPLE_SIZE = 256 * 1024
    private const val FRAME_DURATION_ESTIMATE_US = 25_000L // ~1 frame AAC a 44.1kHz/1024 amostras por quadro
}
