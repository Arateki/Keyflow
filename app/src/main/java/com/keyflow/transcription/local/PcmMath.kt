package com.keyflow.transcription.local

import kotlin.math.floor
import kotlin.math.min

/** Contas puras de PCM do [AudioDecoder] (NAT-02) — fora do Android para teste na JVM. */
object PcmMath {

    /** Média dos canais de um PCM intercalado; mono volta como está. */
    fun downmixToMono(interleaved: FloatArray, channels: Int): FloatArray {
        require(channels >= 1) { "channels = $channels" }
        if (channels == 1) return interleaved
        val frames = interleaved.size / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += interleaved[frame * channels + c]
            sum / channels
        }
    }

    /**
     * Converte de [fromRate] para [toRate]. Reduzindo, cada amostra de saída é a média da janela de
     * entrada que ela cobre (filtro de caixa — segura o grosso do aliasing sem depender de lib);
     * aumentando, interpolação linear. É o caminho **defensivo**: a gravação já sai em 16 kHz.
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "taxas inválidas: $fromRate → $toRate" }
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = fromRate.toDouble() / toRate
        val outSize = floor(input.size / ratio).toInt()
        return if (ratio > 1.0) boxDownsample(input, ratio, outSize) else linearUpsample(input, ratio, outSize)
    }

    private fun boxDownsample(input: FloatArray, ratio: Double, outSize: Int): FloatArray = FloatArray(outSize) { i ->
        val start = i * ratio
        val end = min((i + 1) * ratio, input.size.toDouble())
        var sum = 0.0
        var weight = 0.0
        var k = floor(start).toInt()
        while (k < end) {
            val overlap = min(k + 1.0, end) - maxOf(k.toDouble(), start)
            sum += input[k] * overlap
            weight += overlap
            k++
        }
        if (weight > 0) (sum / weight).toFloat() else 0f
    }

    private fun linearUpsample(input: FloatArray, ratio: Double, outSize: Int): FloatArray = FloatArray(outSize) { i ->
        val pos = i * ratio
        val k = floor(pos).toInt()
        val next = min(k + 1, input.size - 1)
        val frac = (pos - k).toFloat()
        input[k] * (1 - frac) + input[next] * frac
    }

    /** PCM 16-bit com sinal → float em [-1, 1). */
    fun pcm16ToFloat(sample: Short): Float = sample / 32768f
}
