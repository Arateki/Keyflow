package com.keyflow.notificacao

import kotlin.math.pow

/** Contraste de cor (WCAG) para os glifos dos botões sobre o fundo da notificação (NTF-T9: legíveis em claro e escuro). Puro. */
object Contraste {
    /** O mínimo para um glifo de interface (WCAG 1.4.11: 3:1). */
    const val MINIMO = 3.0

    private const val BRANCO = 0xFFFFFFFF.toInt()
    private const val ESCURO = 0xFF1B1B1B.toInt()

    private fun canal(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminancia(argb: Int): Double =
        0.2126 * canal((argb shr 16) and 0xFF) + 0.7152 * canal((argb shr 8) and 0xFF) + 0.0722 * canal(argb and 0xFF)

    /** A razão de contraste entre duas cores (1 a 21), ignorando o alfa. */
    fun razao(a: Int, b: Int): Double {
        val la = luminancia(a)
        val lb = luminancia(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * Compõe a [tinta] sobre o [fundo] opaco da linha e devolve uma cor opaca com contraste mínimo.
     * Se a composição não se lê, usa branco ou quase preto, o que contrastar mais.
     * Assim o filtro do botão e o tempo miúdo recebem a mesma cor, sem outra composição de alfa pelo Android.
     */
    fun legivel(tinta: Int, fundo: Int): Int {
        val alfa = tinta ushr 24
        fun componente(deslocamento: Int): Int =
            (((tinta ushr deslocamento) and 0xFF) * alfa + ((fundo ushr deslocamento) and 0xFF) * (255 - alfa)) / 255
        val composta = (0xFF shl 24) or (componente(16) shl 16) or (componente(8) shl 8) or componente(0)
        if (razao(composta, fundo) >= MINIMO) return composta
        return if (razao(BRANCO, fundo) >= razao(ESCURO, fundo)) BRANCO else ESCURO
    }
}
