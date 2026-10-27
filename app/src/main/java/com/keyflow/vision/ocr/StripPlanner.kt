package com.keyflow.vision.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Uma faixa da imagem para o detector. [x0]..[y1] é a região em pixels da imagem de origem (x1/y1 exclusivos); [mapW]x[mapH] o
 * tamanho (múltiplo de 32) em que a região é entregue ao detector. [ownStart]..[ownEnd] é o trecho do eixo maior que esta faixa
 * "possui": uma caixa só vale na faixa que contém o seu centro, e é assim que a sobreposição não duplica texto.
 */
data class Strip(
    val x0: Int, val y0: Int, val x1: Int, val y1: Int,
    val mapW: Int, val mapH: Int,
    val ownStart: Int, val ownEnd: Int,
    val vertical: Boolean,
) {
    val regionW: Int get() = x1 - x0
    val regionH: Int get() = y1 - y0

    /** O centro de [box] cai no trecho que esta faixa possui? */
    fun owns(box: TextBox): Boolean {
        val center = if (vertical) (box.y0 + box.y1) / 2 else (box.x0 + box.x1) / 2
        return center in ownStart until ownEnd
    }
}

/**
 * Divide a imagem em faixas ao longo do eixo maior (design §6.2). Por quê: o `limit_side_len = 960` padrão do PaddleOCR encolheria
 * uma tela 1080x2400 de modo que o texto de ~14 px virasse ~6 px e o detector o perdesse. Aqui só o eixo menor é limitado
 * ([OcrParams.crossLimit]) e o maior vira faixas de [OcrParams.stripLength] px com [OcrParams.stripOverlap] de sobreposição.
 */
object StripPlanner {

    /** Imagens cujo eixo maior (já reduzido) cabe em faixa + esta folga seguem numa faixa só. */
    private const val SINGLE_STRIP_SLACK = 128

    fun plan(width: Int, height: Int, params: OcrParams): List<Strip> {
        require(width > 0 && height > 0)
        val vertical = height >= width
        val long = if (vertical) height else width
        val short = if (vertical) width else height
        val scale = min(1.0, params.crossLimit.toDouble() / short)
        val scaledLong = long * scale
        val ranges = ArrayList<IntArray>() // [início, fim) no eixo maior, em px de origem

        if (scaledLong <= params.stripLength + SINGLE_STRIP_SLACK) {
            ranges += intArrayOf(0, long)
        } else {
            val stripSrc = params.stripLength / scale
            val stepSrc = (params.stripLength - params.stripOverlap) / scale
            var start = 0.0
            while (true) {
                val end = min(start + stripSrc, long.toDouble())
                ranges += intArrayOf(start.roundToInt(), end.roundToInt())
                if (end >= long) break
                start += stepSrc
            }
            // Resto menor que o dobro da sobreposição: a faixa seria quase só repetição; o fim da anterior se estende até o limite.
            if (ranges.size >= 2) {
                val last = ranges.last()
                if ((last[1] - last[0]) * scale < params.stripOverlap * 2) {
                    ranges.removeAt(ranges.size - 1)
                    ranges.last()[1] = long
                }
            }
        }

        return ranges.mapIndexed { i, r ->
            val ownStart = if (i == 0) 0 else (ranges[i - 1][1] + r[0]) / 2
            val ownEnd = if (i == ranges.size - 1) long else (r[1] + ranges[i + 1][0]) / 2
            val regionLong = r[1] - r[0]
            val mapLong = mapSize(regionLong * scale)
            val mapShort = mapSize(short * scale)
            if (vertical) {
                Strip(0, r[0], width, r[1], mapW = mapShort, mapH = mapLong, ownStart = ownStart, ownEnd = ownEnd, vertical = true)
            } else {
                Strip(r[0], 0, r[1], height, mapW = mapLong, mapH = mapShort, ownStart = ownStart, ownEnd = ownEnd, vertical = false)
            }
        }
    }

    /** Múltiplo de 32 mais próximo (o detector exige), no mínimo 32. */
    fun mapSize(pixels: Double): Int = max(32, ((pixels / 32.0).roundToInt()) * 32)
}
