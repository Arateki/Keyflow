package com.keyflow.transcription.local

/**
 * O Whisper do sherpa-onnx só olha os primeiros ~30 s de cada stream e **descarta o resto** com um
 * aviso no log (conferido em `offline-recognizer-whisper-impl.h`, v1.13.8: `max_num_frames = 3000`).
 * Ditado mais longo é cortado em janelas de até [MAX_SECONDS], cada corte no trecho mais silencioso
 * dos últimos [SEARCH_SECONDS] da janela — para não partir palavra ao meio.
 */
object WhisperChunker {

    const val MAX_SECONDS = 28
    const val SEARCH_SECONDS = 4
    private const val FRAME_MS = 20

    fun split(samples: FloatArray, sampleRate: Int = AudioDecoder.TARGET_SAMPLE_RATE): List<FloatArray> {
        val max = MAX_SECONDS * sampleRate
        if (samples.size <= max) return listOf(samples)
        val frame = sampleRate * FRAME_MS / 1000
        val chunks = mutableListOf<FloatArray>()
        var start = 0
        while (samples.size - start > max) {
            val cut = quietestPoint(samples, start + max - SEARCH_SECONDS * sampleRate, start + max, frame)
            chunks += samples.copyOfRange(start, cut)
            start = cut
        }
        chunks += samples.copyOfRange(start, samples.size)
        return chunks
    }

    /** Meio do quadro de menor energia em [from, to). */
    private fun quietestPoint(samples: FloatArray, from: Int, to: Int, frame: Int): Int {
        var best = to
        var bestEnergy = Double.MAX_VALUE
        var i = from
        while (i + frame <= to) {
            var energy = 0.0
            for (k in i until i + frame) energy += samples[k] * samples[k]
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = i + frame / 2
            }
            i += frame
        }
        return best
    }
}
