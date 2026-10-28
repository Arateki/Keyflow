package com.keyflow.vision.ocr

/**
 * Decodificação CTC gulosa do reconhecedor do PaddleOCR. Índice 0 = branco; 1..N = linhas do `dict.txt`; N+1 = espaço (o
 * PaddleOCR acrescenta o espaço ao fim do dicionário). Para o dicionário latino: 502 linhas → 504 classes (conferido contra a forma
 * de saída do `rec.onnx`, `[lote, tempo, 504]`).
 */
class CtcDecoder(private val characters: List<String>) {

    /** Número de classes que a saída do modelo deve ter: branco + dicionário + espaço. */
    val classCount: Int = characters.size + 2

    class Line(val text: String, val score: Float)

    /**
     * Decodifica a linha [index] de um tensor `[lote, tempo, classes]` achatado. Repetições consecutivas colapsam e o branco some;
     * [Line.score] é a média da probabilidade dos passos mantidos (0 se nada foi lido). A saída do modelo já é softmax; se a soma da linha
     * não der ~1, aplica-se o softmax aqui.
     */
    fun decode(logits: FloatArray, index: Int, steps: Int, classes: Int): Line {
        require(classes == classCount) { "o modelo tem $classes classes, o dicionário pede $classCount" }
        val base = index * steps * classes
        val sb = StringBuilder()
        var scoreSum = 0f
        var kept = 0
        var previous = -1
        for (t in 0 until steps) {
            val row = base + t * classes
            var best = 0
            var bestValue = logits[row]
            var sum = bestValue
            for (c in 1 until classes) {
                val v = logits[row + c]
                sum += v
                if (v > bestValue) {
                    bestValue = v
                    best = c
                }
            }
            if (best != previous && best != 0) {
                val ch = charFor(best)
                if (ch != null) {
                    sb.append(ch)
                    scoreSum += if (isProbabilities(sum, bestValue)) bestValue else softmaxMax(logits, row, classes)
                    kept++
                }
            }
            previous = best
        }
        return Line(sb.toString(), if (kept == 0) 0f else scoreSum / kept)
    }

    private fun charFor(classIndex: Int): String? = when {
        classIndex in 1..characters.size -> characters[classIndex - 1]
        classIndex == characters.size + 1 -> " "
        else -> null
    }

    private fun isProbabilities(sum: Float, max: Float): Boolean = max in 0f..1f && sum in 0.98f..1.02f

    private fun softmaxMax(logits: FloatArray, row: Int, classes: Int): Float {
        var max = Float.NEGATIVE_INFINITY
        for (c in 0 until classes) if (logits[row + c] > max) max = logits[row + c]
        var sum = 0f
        for (c in 0 until classes) sum += kotlin.math.exp(logits[row + c] - max)
        return 1f / sum
    }

    companion object {
        /** O `dict.txt`: uma entrada por linha (só `\n` separa; nada de `splitlines`, que também quebra em U+2028 e afins), sem a vazia do fim. */
        fun fromDictionary(text: String): CtcDecoder {
            val lines = text.split('\n').map { it.removeSuffix("\r") }
            val trimmed = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            return CtcDecoder(trimmed)
        }
    }
}
