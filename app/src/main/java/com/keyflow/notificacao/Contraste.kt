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

    /** A [tinta] da skin se ela se lê sobre o [fundo]; senão, branco ou quase preto, o que contrastar mais. */
    fun legivel(tinta: Int, fundo: Int): Int {
        if (razao(tinta, fundo) >= MINIMO) return tinta
        return if (razao(BRANCO, fundo) >= razao(ESCURO, fundo)) BRANCO else ESCURO
    }
}
