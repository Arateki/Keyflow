package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import org.junit.Test

/** RRUN-04: a guarda de RAM antes de carregar um modelo de texto. */
class RamGuardTest {

    @Test
    fun `available below the estimate is rejected`() {
        assertThat(RamGuard.enough(availableMb = 1_000, estimateMb = 2_048)).isFalse()
    }

    @Test
    fun `available above or equal to the estimate passes`() {
        assertThat(RamGuard.enough(availableMb = 4_000, estimateMb = 2_048)).isTrue()
        assertThat(RamGuard.enough(availableMb = 2_048, estimateMb = 2_048)).isTrue()
    }

    /** Sem dado do sistema não se bloqueia: o `OutOfMemoryError` do load ainda é capturado depois. */
    @Test
    fun `unknown available memory passes`() {
        assertThat(RamGuard.enough(availableMb = null, estimateMb = 2_048)).isTrue()
    }

    @Test
    fun `a zero or negative estimate never blocks`() {
        assertThat(RamGuard.enough(availableMb = 1, estimateMb = 0)).isTrue()
        assertThat(RamGuard.enough(availableMb = 1, estimateMb = -5)).isTrue()
    }

    /** Medido no A32: 1,1–1,5 GB livres e picos de 1,66–1,74 GB nos modelos de texto; a estimativa do catálogo sozinha deixaria passar. */
    @Test
    fun `preload needs headroom over the measured peak even when the estimate fits`() {
        assertThat(RamGuard.enoughForPreload(availableMb = 1_500, estimateMb = 512)).isFalse()
        assertThat(RamGuard.enoughForPreload(availableMb = 2_047, estimateMb = 512)).isFalse()
        assertThat(RamGuard.enoughForPreload(availableMb = 2_048, estimateMb = 512)).isTrue()
    }

    @Test
    fun `preload still respects an estimate above the floor`() {
        assertThat(RamGuard.enoughForPreload(availableMb = 3_000, estimateMb = 4_096)).isFalse()
        assertThat(RamGuard.enoughForPreload(availableMb = 4_096, estimateMb = 4_096)).isTrue()
    }

    @Test
    fun `preload without an estimate or unknown memory never blocks`() {
        assertThat(RamGuard.enoughForPreload(availableMb = 100, estimateMb = 0)).isTrue()
        assertThat(RamGuard.enoughForPreload(availableMb = null, estimateMb = 4_096)).isTrue()
    }

    // ── RAM total: o que decide a carga pedida (medições de pico de VmRSS do `:reasoning`, 2026-10-27) ──────────────────

    private fun estimate(spec: ModelSpec) = spec.recommendedRamMb / 2

    /** A32 (~3,7 GB; 1,1–1,5 GB livres): Qwen, Gemma E2B e DeepSeek rodaram a 1,66–1,73 GB; o SmolLM2 foi morto pelo lmkd. */
    @Test
    fun `on the A32 the three measured models load and SmolLM2, the E4B and Qwen3 4B are refused`() {
        val total = 3_700L
        val free = 1_200L // a RAM livre subestima: o Android libera apps em segundo plano ao carregar
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.QWEN3_0_6B), total)).isTrue()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.GEMMA_4_E2B), total)).isTrue()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.DEEPSEEK_R1_1_5B), total)).isTrue()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.SMOLLM2_1_7B), total)).isFalse()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.GEMMA_4_E4B), total)).isFalse()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.QWEN3_4B), total)).isFalse()
    }

    /** Tab S10 Lite (~5,8 GB; 2,3 GB livres): SmolLM2 rodou a 3,3 GB; o E4B (4,4–4,5 GB) e o Qwen3 4B (4,6–6,1 GB na CPU) não cabem. */
    @Test
    fun `on a 6 GB tablet SmolLM2 loads and the two big models are refused`() {
        val total = 5_700L
        val free = 2_300L
        val big = setOf(ModelCatalog.GEMMA_4_E4B, ModelCatalog.QWEN3_4B)
        ModelCatalog.visibleIn(ModelCatalog.TEXT_ALL, debug = false).filter { it !in big }.forEach {
            assertThat(RamGuard.enough(free, estimate(it), total)).isTrue()
        }
        big.forEach { assertThat(RamGuard.enough(free, estimate(it), total)).isFalse() }
    }

    /**
     * Vaio TL10 (~7,6 GB; 4,8 GB livres): o E4B rodou a 4,4–4,5 GB. O Qwen3 4B não: pela CPU o processo passou de 5 GB e o Android o matou por falta de
     * memória (só a GPU, que o app não usa, o carregou, com 4,4 GB) — a guarda o barra em vez de deixar o usuário esperar a carga morrer.
     */
    @Test
    fun `on an 8 GB device every text model loads but Qwen3 4B`() {
        ModelCatalog.visibleIn(ModelCatalog.TEXT_ALL, debug = false).filter { it != ModelCatalog.QWEN3_4B }.forEach {
            assertThat(RamGuard.enough(4_800L, estimate(it), 7_400L)).isTrue()
        }
        assertThat(RamGuard.enough(4_800L, estimate(ModelCatalog.QWEN3_4B), 7_400L)).isFalse()
    }

    /** Um aparelho de 12 GB mostra ~11 GB de RAM total: carrega o Qwen3 4B (RSS de 4,6–6,1 GB na CPU, segundo o autor) mesmo com pouca RAM livre. */
    @Test
    fun `on a 12 GB device every text model loads`() {
        ModelCatalog.visibleIn(ModelCatalog.TEXT_ALL, debug = false).forEach { assertThat(RamGuard.enough(3_000L, estimate(it), 11_200L)).isTrue() }
    }

    @Test
    fun `the Qwen3 4B floor is 9831 MB, rounded up from 80 percent of 12288`() {
        val estimate = ModelCatalog.QWEN3_4B.recommendedRamMb / 2
        assertThat(RamGuard.fitsDevice(totalMb = 9_831, estimateMb = estimate)).isTrue()
        assertThat(RamGuard.fitsDevice(totalMb = 9_830, estimateMb = estimate)).isFalse()
    }

    @Test
    fun `with the total known the free memory no longer decides the load`() {
        assertThat(RamGuard.enough(availableMb = 100, estimateMb = 2_048, totalMb = 4_000)).isTrue()
        assertThat(RamGuard.enough(availableMb = 6_000, estimateMb = 2_048, totalMb = 3_000)).isFalse()
    }

    @Test
    fun `the total limit is 1_6 times the estimate, inclusive`() {
        assertThat(RamGuard.fitsDevice(totalMb = 3_277, estimateMb = 2_048)).isTrue()
        assertThat(RamGuard.fitsDevice(totalMb = 3_276, estimateMb = 2_048)).isFalse()
        assertThat(RamGuard.fitsDevice(totalMb = null, estimateMb = 8_192)).isTrue()
        assertThat(RamGuard.fitsDevice(totalMb = 1, estimateMb = 0)).isTrue()
    }

    /** Total zero ou negativo é dado inválido: vale a regra antiga da RAM livre, como com total nulo. */
    @Test
    fun `a non positive total is treated as unknown and falls back to the free memory`() {
        listOf(0L, -1L).forEach { total ->
            assertThat(RamGuard.enough(availableMb = 5_000, estimateMb = 2_048, totalMb = total)).isTrue()
            assertThat(RamGuard.enough(availableMb = 1_000, estimateMb = 2_048, totalMb = total)).isFalse()
            assertThat(RamGuard.enough(availableMb = null, estimateMb = 2_048, totalMb = total)).isTrue()
            assertThat(RamGuard.fitsDevice(totalMb = total, estimateMb = 8_192)).isTrue()
        }
        assertThat(RamGuard.enoughForPreload(availableMb = 5_000, estimateMb = 3_072, totalMb = 0)).isTrue()
    }

    @Test
    fun `the SmolLM2 floor is 4916 MB, rounded up from 80 percent of 6144`() {
        val estimate = ModelCatalog.SMOLLM2_1_7B.recommendedRamMb / 2
        assertThat(RamGuard.fitsDevice(totalMb = 4_916, estimateMb = estimate)).isTrue()
        assertThat(RamGuard.fitsDevice(totalMb = 4_915, estimateMb = estimate)).isFalse()
    }

    /** A pré-carga é especulativa: além de caber no aparelho, exige RAM livre de verdade (piso e estimativa). */
    @Test
    fun `preload also needs the model to fit the device`() {
        assertThat(RamGuard.enoughForPreload(availableMb = 5_000, estimateMb = 3_072, totalMb = 4_000)).isFalse()
        assertThat(RamGuard.enoughForPreload(availableMb = 5_000, estimateMb = 3_072, totalMb = 5_700)).isTrue()
        assertThat(RamGuard.enoughForPreload(availableMb = 1_200, estimateMb = 2_048, totalMb = 3_700)).isFalse()
    }
}
