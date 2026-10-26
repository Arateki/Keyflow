package com.keyflow.reasoning.local

/**
 * Guarda de RAM antes de carregar um modelo de texto (RRUN-04). A estimativa vem do catálogo (memória de trabalho,
 * não o tamanho do arquivo: os pesos do LiteRT-LM são mapeados em memória); sem dado do sistema não se bloqueia — o
 * `OutOfMemoryError` do load ainda é capturado depois.
 */
internal object RamGuard {
    fun enough(availableMb: Long?, estimateMb: Int): Boolean =
        availableMb == null || estimateMb <= 0 || availableMb >= estimateMb

    /**
     * Piso de RAM livre para a pré-carga ao selecionar (sem pedido do usuário). Medido no Galaxy A32 (3,7 GB, 1,1–1,5 GB
     * livres): DeepSeek R1 1.5B, Gemma 4 E2B e Qwen3 0.6B chegam a 1,66–1,74 GB de RSS e o SmolLM2 1.7B foi morto pelo
     * lmkd acima de ~1,7 GB — as estimativas do catálogo ficam abaixo disso. Como a pré-carga é especulativa, ela só
     * roda com folga sobre o pico medido (estimativa zero = motor sem pesos, como o de eco dos testes: não bloqueia); senão o modelo carrega no primeiro uso, que tem a guarda comum.
     */
    const val PRELOAD_FLOOR_MB = 2_048L

    fun enoughForPreload(availableMb: Long?, estimateMb: Int): Boolean =
        availableMb == null || estimateMb <= 0 || (enough(availableMb, estimateMb) && availableMb >= PRELOAD_FLOOR_MB)
}
