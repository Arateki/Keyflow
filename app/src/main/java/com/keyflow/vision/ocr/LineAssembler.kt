package com.keyflow.vision.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Monta o texto final: agrupa as caixas reconhecidas em linhas por sobreposição vertical, ordena cada linha por x e junta com `\n`;
 * uma lacuna vertical maior que [BLOCK_GAP] vezes a altura mediana de linha vira linha em branco (bloco). Design §6.4.
 */
object LineAssembler {

    /** Fração da menor altura que tem de se sobrepor para duas caixas serem da mesma linha. */
    private const val SAME_LINE_OVERLAP = 0.5f
    private const val BLOCK_GAP = 1.5f

    private class Line(first: RecognizedBox) {
        val items = arrayListOf(first)
        // A primeira caixa ancora a linha: comparar com a faixa que cresce arrastaria a linha seguinte para dentro desta.
        val anchorY0 = first.box.y0
        val anchorY1 = first.box.y1
        var top = first.box.y0
        var bottom = first.box.y1
    }

    fun assemble(items: List<RecognizedBox>): String {
        val usable = items.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return ""
        val lines = ArrayList<Line>()
        for (item in usable.sortedWith(compareBy({ it.box.y0 + it.box.y1 }, { it.box.x0 }))) {
            val line = lines.lastOrNull()?.takeIf { sameLine(it, item.box) }
            if (line != null) {
                line.items += item
                line.top = min(line.top, item.box.y0)
                line.bottom = max(line.bottom, item.box.y1)
            } else {
                lines += Line(item)
            }
        }
        val heights = lines.map { line -> median(line.items.map { it.box.height }) }
        val medianHeight = median(heights)
        val sb = StringBuilder()
        lines.forEachIndexed { i, line ->
            if (i > 0) {
                sb.append('\n')
                val gap = line.top - lines[i - 1].bottom
                if (gap > BLOCK_GAP * medianHeight) sb.append('\n')
            }
            sb.append(line.items.sortedBy { it.box.x0 }.joinToString(" ") { it.text.trim() })
        }
        return sb.toString()
    }

    private fun sameLine(line: Line, box: TextBox): Boolean {
        val overlap = min(line.anchorY1, box.y1) - max(line.anchorY0, box.y0)
        val smaller = min(line.anchorY1 - line.anchorY0, box.height)
        return smaller > 0 && overlap >= SAME_LINE_OVERLAP * smaller
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
