package com.keyflow.vision.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pós-processamento do detector DB do PaddleOCR sobre o mapa de probabilidade de uma faixa: binariza, acha os componentes conexos,
 * descarta os pequenos ou de baixa pontuação, expande cada caixa (`unclip`) e leva as coordenadas de volta à imagem de origem.
 *
 * Diferenças deliberadas do código de referência (Python/OpenCV): a caixa é alinhada aos eixos (texto de tela é horizontal; sem
 * `minAreaRect` rotacionado) e a pontuação é a média da probabilidade nos pixels do próprio componente. Tudo Kotlin puro,
 * testável com mapas sintéticos.
 */
object DbPostprocess {

    private class Component(var minX: Int, var minY: Int, var maxX: Int, var maxY: Int, var count: Int, var sum: Double)

    /** Caixa em pixels do MAPA (x1/y1 exclusivos) e a pontuação do componente. */
    class MapBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val score: Float)

    /**
     * Componentes conexos (8 vizinhos) do mapa binarizado em [threshold], com a caixa mínima de cada um e a média da probabilidade.
     * [prob] é `mapH x mapW` em ordem de linha. Sem alocar um `Component` por pixel: uma pilha de índices e um mapa de visitados.
     */
    fun components(prob: FloatArray, mapW: Int, mapH: Int, threshold: Float, minSide: Int, maxBoxes: Int): List<MapBox> {
        require(prob.size >= mapW * mapH) { "mapa de ${prob.size} valores para ${mapW}x$mapH" }
        val visited = BooleanArray(mapW * mapH)
        val stack = IntArray(mapW * mapH)
        val out = ArrayList<MapBox>()
        for (start in 0 until mapW * mapH) {
            if (visited[start] || prob[start] <= threshold) continue
            val c = Component(start % mapW, start / mapW, start % mapW, start / mapW, 0, 0.0)
            var top = 0
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val p = stack[--top]
                val x = p % mapW
                val y = p / mapW
                c.count++
                c.sum += prob[p]
                if (x < c.minX) c.minX = x
                if (x > c.maxX) c.maxX = x
                if (y < c.minY) c.minY = y
                if (y > c.maxY) c.maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= mapH) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= mapW) continue
                        val q = ny * mapW + nx
                        if (!visited[q] && prob[q] > threshold) {
                            visited[q] = true
                            stack[top++] = q
                        }
                    }
                }
            }
            val w = c.maxX - c.minX + 1
            val h = c.maxY - c.minY + 1
            if (min(w, h) < minSide || c.count < 4) continue
            out += MapBox(c.minX, c.minY, c.maxX + 1, c.maxY + 1, (c.sum / c.count).toFloat())
        }
        // Mais pontuados primeiro quando passa do limite (como o max_candidates do PaddleOCR); a ordem final é de quem monta as linhas.
        return if (out.size > maxBoxes) out.sortedByDescending { it.score }.take(maxBoxes) else out
    }

    /**
     * `unclip` do PaddleOCR (pyclipper): afasta cada lado `d = área x razão / perímetro`. Devolve [x0, y0, x1, y1] em pixels do mapa,
     * sem clamp (o chamador clampa na imagem de origem).
     */
    fun unclip(box: MapBox, ratio: Float): FloatArray {
        val w = (box.x1 - box.x0).toFloat()
        val h = (box.y1 - box.y0).toFloat()
        val d = w * h * ratio / (2f * (w + h))
        return floatArrayOf(box.x0 - d, box.y0 - d, box.x1 + d, box.y1 + d)
    }

    /**
     * Caixas da faixa, em pixels da imagem de origem (clampadas em [imageW] x [imageH]). [prob] é o mapa `mapH x mapW` que o detector
     * devolveu para [strip].
     */
    fun boxesFor(strip: Strip, prob: FloatArray, imageW: Int, imageH: Int, params: OcrParams): List<TextBox> {
        val mapBoxes = components(prob, strip.mapW, strip.mapH, params.binaryThreshold, params.minBoxSide, params.maxBoxes)
        val sx = strip.regionW.toFloat() / strip.mapW
        val sy = strip.regionH.toFloat() / strip.mapH
        val out = ArrayList<TextBox>(mapBoxes.size)
        for (mb in mapBoxes) {
            if (mb.score < params.boxThreshold) continue
            val e = unclip(mb, params.unclipRatio)
            // Depois do unclip o menor lado ainda tem de passar do mínimo + 2 (PaddleOCR: `sside < min_size + 2`).
            if (min(e[2] - e[0], e[3] - e[1]) < params.minBoxSide + 2) continue
            val x0 = max(0, (strip.x0 + e[0] * sx).roundToInt())
            val y0 = max(0, (strip.y0 + e[1] * sy).roundToInt())
            val x1 = min(imageW, (strip.x0 + e[2] * sx).roundToInt())
            val y1 = min(imageH, (strip.y0 + e[3] * sy).roundToInt())
            if (x1 - x0 < 2 || y1 - y0 < 2) continue
            out += TextBox(x0, y0, x1, y1, mb.score)
        }
        return out
    }

    /**
     * Junta as caixas de todas as faixas: fica com as que a própria faixa possui ([Strip.owns]) e, por garantia, tira a caixa que
     * está quase toda dentro de outra (≥ [CONTAINED] da área da menor) — o corte de um texto grande entre duas faixas.
     */
    fun mergeStrips(perStrip: List<Pair<Strip, List<TextBox>>>): List<TextBox> {
        val owned = perStrip.flatMap { (strip, boxes) -> boxes.filter { strip.owns(it) } }
        val sorted = owned.sortedByDescending { it.area }
        val kept = ArrayList<TextBox>(sorted.size)
        for (box in sorted) {
            val smaller = box.area
            if (kept.none { smaller > 0 && box.intersection(it).toDouble() / smaller >= CONTAINED }) kept += box
        }
        return kept
    }

    private const val CONTAINED = 0.7
}
