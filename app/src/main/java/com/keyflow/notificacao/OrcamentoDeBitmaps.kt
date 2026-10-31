package com.keyflow.notificacao

import kotlin.math.roundToInt

/**
 * As contas dos bitmaps da notificação (NTF-27, design §7). Pura: o `Binder` leva a `Notification` inteira, então a orbe de conteúdo e a da
 * versão pública (2 bitmaps) têm um teto; os glifos dos botões são vetores e não entram.
 */
object OrcamentoDeBitmaps {
    const val LADO_DA_ORBE_DP = 40
    const val LADO_MAXIMO_PX = 192
    const val TETO_DA_SOMA_BYTES = 1024 * 1024
    const val ALVO_DA_SOMA_BYTES = 256 * 1024

    /** `min(192, round(40 × densidade))`. */
    fun ladoPx(densidade: Float): Int =
        minOf(LADO_MAXIMO_PX, (LADO_DA_ORBE_DP * densidade).roundToInt()).coerceAtLeast(1)

    /** Bytes de um bitmap ARGB_8888 quadrado de [ladoPx]. */
    fun bytes(ladoPx: Int): Long = ladoPx.toLong() * ladoPx * 4L

    /** Conteúdo + versão pública: dois bitmaps do mesmo lado. */
    fun somaDaNotificacao(ladoPx: Int): Long = 2 * bytes(ladoPx)

    /** A soma dos [bytesPorBitmap] cabe no teto de 1 MB (senão cai no Ateliê e depois na mínima). */
    fun cabe(bytesPorBitmap: List<Long>): Boolean = bytesPorBitmap.sum() <= TETO_DA_SOMA_BYTES
}
