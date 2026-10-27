package com.keyflow.reasoning.local

/**
 * Guarda de RAM antes de carregar um modelo de texto (RRUN-04). A estimativa vem do catálogo (metade de `recommendedRamMb`;
 * os pesos do LiteRT-LM são mapeados em memória); sem dado do sistema não se bloqueia — o `OutOfMemoryError` do load ainda
 * é capturado depois.
 *
 * Carga pedida pelo usuário: o que decide é a RAM TOTAL do aparelho ([fitsDevice]), não a livre. A livre medida antes da
 * carga subestima — o Android derruba apps em segundo plano para dar lugar ao `:reasoning` — e não separava os casos
 * medidos (pico de VmRSS do `:reasoning`): no A32 (3,7 GB, 1,1–1,5 GB livres) Qwen3 0.6B, Gemma 4 E2B e DeepSeek R1 1.5B
 * rodaram a 1,66–1,73 GB, enquanto o SmolLM2 1.7B foi morto pelo lmkd acima de ~1,7 GB; no tablet de 6 GB (2,3 GB livres) o
 * SmolLM2 rodou a 3,3 GB; no Vaio de 8 GB (4,8 GB livres) o Gemma 4 E4B rodou a 4,4–4,5 GB. Só a RAM livre (1,1–1,5 GB no A32
 * contra 2,3 GB no tablet) barraria os que funcionam no A32 ou liberaria os que não cabem.
 */
internal object RamGuard {
    /**
     * Quanto da RAM total o aparelho precisa ter por MB de estimativa do catálogo: `total >= estimativa × 1,6`, isto é,
     * 80% da `recommendedRamMb` (= 2 × estimativa). Os limites resultantes, contra o que foi medido: Qwen3 (3072) exige
     * 2458 MB, DeepSeek e Gemma 4 E2B (4096) exigem 3277 MB — cabem no A32 (~3,6–3,8 GB); SmolLM2 (6144) exige 4916 MB — barrado
     * no A32, liberado no tablet de 6 GB (~5,7 GB); Gemma 4 E4B (8192) exige 6554 MB — barrado no tablet, liberado no Vaio de
     * 8 GB (~7,4 GB). Em todos o pico medido fica em até ~70% do mínimo exigido. Qwen3 4B (12288) exige 9831 MB — barrado também no Vaio de
     * 8 GB, onde a CPU passou de 5 GB e o Android matou o processo; só aparelhos de 12 GB (~11 GB visíveis) o liberam.
     */
    private const val TOTAL_RAM_PER_ESTIMATE_TENTHS = 16L

    /** O aparelho tem RAM total para esse modelo? Sem total (nulo, zero ou negativo = dado inválido) ou sem estimativa não se bloqueia. */
    fun fitsDevice(totalMb: Long?, estimateMb: Int): Boolean =
        totalMb == null || totalMb <= 0 || estimateMb <= 0 || totalMb * 10 >= estimateMb * TOTAL_RAM_PER_ESTIMATE_TENTHS

    /**
     * Com o total conhecido (positivo) vale [fitsDevice]; sem ele (sistema sem dado), cai na regra antiga da RAM livre. A estimativa zero
     * (motor sem pesos, como o de eco dos testes) nunca bloqueia.
     */
    fun enough(availableMb: Long?, estimateMb: Int, totalMb: Long? = null): Boolean = when {
        estimateMb <= 0 -> true
        totalMb != null && totalMb > 0 -> fitsDevice(totalMb, estimateMb)
        else -> availableMb == null || availableMb >= estimateMb
    }

    /**
     * Piso de RAM livre para a pré-carga ao selecionar (sem pedido do usuário). Medido no Galaxy A32 (3,7 GB, 1,1–1,5 GB
     * livres): DeepSeek R1 1.5B, Gemma 4 E2B e Qwen3 0.6B chegam a 1,66–1,74 GB de RSS e o SmolLM2 1.7B foi morto pelo
     * lmkd acima de ~1,7 GB. Como a pré-carga é especulativa, o Android não pode ser forçado a derrubar apps por ela: ela só
     * roda com a RAM LIVRE de fato acima da estimativa e do piso (estimativa zero = motor sem pesos, como o de eco dos testes:
     * não bloqueia); senão o modelo carrega no primeiro uso, que tem a guarda comum.
     */
    const val PRELOAD_FLOOR_MB = 2_048L

    fun enoughForPreload(availableMb: Long?, estimateMb: Int, totalMb: Long? = null): Boolean =
        estimateMb <= 0 || availableMb == null ||
            (fitsDevice(totalMb, estimateMb) && availableMb >= estimateMb && availableMb >= PRELOAD_FLOOR_MB)
}
