package com.keyflow.vision.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Reamostragem separável com filtro triangular (o "bilinear com anti-serrilhado" do PIL/Pillow): ao reduzir, o filtro alarga e
 * faz média; ao ampliar, é o bilinear comum. Só Kotlin, sem `android.graphics`, para as etapas do OCR rodarem na JVM.
 */
internal object Resampler {

    private class Axis(val start: IntArray, val count: IntArray, val weights: FloatArray, val stride: Int)

    private fun axis(srcSize: Int, dstSize: Int): Axis {
        val scale = srcSize.toDouble() / dstSize
        val filterScale = max(scale, 1.0)
        val support = filterScale
        val stride = ceil(support * 2).toInt() + 1
        val start = IntArray(dstSize)
        val count = IntArray(dstSize)
        val weights = FloatArray(dstSize * stride)
        for (i in 0 until dstSize) {
            val center = (i + 0.5) * scale
            val lo = max(0, floor(center - support + 0.5).toInt())
            val hi = min(srcSize, floor(center + support + 0.5).toInt())
            var sum = 0.0
            val n = max(0, hi - lo)
            for (j in 0 until n) {
                val w = max(0.0, 1.0 - abs((lo + j + 0.5 - center) / filterScale))
                weights[i * stride + j] = w.toFloat()
                sum += w
            }
            if (n == 0 || sum <= 0.0) {
                // Sem peso (borda degenerada): o pixel mais próximo.
                start[i] = min(srcSize - 1, max(0, center.toInt()))
                count[i] = 1
                weights[i * stride] = 1f
            } else {
                start[i] = lo
                count[i] = n
                for (j in 0 until n) weights[i * stride + j] = (weights[i * stride + j] / sum).toFloat()
            }
        }
        return Axis(start, count, weights, stride)
    }

    /**
     * Reamostra a região `[x0,x1) x [y0,y1)` de [src] para [dstW]x[dstH]. Devolve três planos float (CHW, valores 0..255):
     * canais na ordem R,G,B ou, com [bgr], B,G,R. O alfa é ignorado (a captura de tela é opaca).
     */
    fun toPlanes(src: RgbaImage, x0: Int, y0: Int, x1: Int, y1: Int, dstW: Int, dstH: Int, bgr: Boolean): FloatArray {
        require(x0 in 0 until x1 && x1 <= src.width && y0 in 0 until y1 && y1 <= src.height) { "região fora da imagem" }
        require(dstW > 0 && dstH > 0)
        val regionW = x1 - x0
        val regionH = y1 - y0
        val ax = axis(regionW, dstW)
        val ay = axis(regionH, dstH)
        val pixels = src.rgba

        // Passo horizontal: regionH linhas x dstW colunas, 3 canais intercalados.
        val tmp = FloatArray(regionH * dstW * 3)
        for (y in 0 until regionH) {
            val rowBase = ((y0 + y).toLong() * src.width + x0).toInt() * 4
            val outRow = y * dstW * 3
            for (x in 0 until dstW) {
                val s = ax.start[x]
                val n = ax.count[x]
                val wBase = x * ax.stride
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until n) {
                    val w = ax.weights[wBase + j]
                    val p = rowBase + (s + j) * 4
                    r += w * (pixels[p].toInt() and 0xFF)
                    g += w * (pixels[p + 1].toInt() and 0xFF)
                    b += w * (pixels[p + 2].toInt() and 0xFF)
                }
                val o = outRow + x * 3
                tmp[o] = r
                tmp[o + 1] = g
                tmp[o + 2] = b
            }
        }

        // Passo vertical, já gravando em planos na ordem de canais pedida.
        val plane = dstW * dstH
        val out = FloatArray(3 * plane)
        for (y in 0 until dstH) {
            val s = ay.start[y]
            val n = ay.count[y]
            val wBase = y * ay.stride
            for (x in 0 until dstW) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until n) {
                    val w = ay.weights[wBase + j]
                    val o = ((s + j) * dstW + x) * 3
                    r += w * tmp[o]
                    g += w * tmp[o + 1]
                    b += w * tmp[o + 2]
                }
                val dst = y * dstW + x
                // plano 0 = primeiro canal pedido (R, ou B no modo BGR)
                out[dst] = if (bgr) b else r
                out[plane + dst] = g
                out[2 * plane + dst] = if (bgr) r else b
            }
        }
        return out
    }
}
