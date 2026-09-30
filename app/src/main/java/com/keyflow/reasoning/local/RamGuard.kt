package com.keyflow.reasoning.local

/**
 * Guarda de RAM antes de carregar um modelo de texto (RRUN-04). A estimativa vem do catálogo (memória de trabalho,
 * não o tamanho do arquivo: os pesos do LiteRT-LM são mapeados em memória); sem dado do sistema não se bloqueia — o
 * `OutOfMemoryError` do load ainda é capturado depois.
 */
internal object RamGuard {
    fun enough(availableMb: Long?, estimateMb: Int): Boolean =
        availableMb == null || estimateMb <= 0 || availableMb >= estimateMb
}
