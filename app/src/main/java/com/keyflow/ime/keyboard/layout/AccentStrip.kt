package com.keyflow.ime.keyboard.layout

import kotlin.math.abs

/**
 * Tira de variantes do long-press (RND-03), no jeito do teclado Samsung: a 1ª variante aparece sobre
 * a tecla e já vem escolhida; o dedo troca a escolha só andando para os lados, sem precisar subir até
 * a tira — a altura do dedo não entra na conta. Pura (JVM).
 */
object AccentStrip {

    /**
     * Posição (esquerda) de cada chip, na ordem das variantes. A 1ª fica centrada em [anchorX] (a tecla) e
     * as outras seguem para a direita; sem espaço à direita, seguem para a esquerda (a 1ª continua sobre a
     * tecla); sem espaço nenhum dos dois lados, a tira começa na borda esquerda.
     */
    fun place(anchorX: Float, count: Int, chipWidth: Float, viewWidth: Float): List<Float> {
        val first = anchorX - chipWidth / 2
        val total = count * chipWidth
        return when {
            first >= 0f && first + total <= viewWidth -> List(count) { first + it * chipWidth }
            first + chipWidth - total >= 0f && first + chipWidth <= viewWidth -> List(count) { first - it * chipWidth }
            else -> {
                val start = (viewWidth - total).coerceAtLeast(0f).coerceAtMost(first.coerceAtLeast(0f))
                List(count) { start + it * chipWidth }
            }
        }
    }

    /** O chip cujo centro está mais perto de [x] — passando das pontas, fica no da ponta. */
    fun indexAt(x: Float, lefts: List<Float>, chipWidth: Float): Int =
        lefts.indices.minByOrNull { abs(x - (lefts[it] + chipWidth / 2)) } ?: -1
}
