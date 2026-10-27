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
    fun `on the A32 the three measured models load and SmolLM2 and the E4B are refused`() {
        val total = 3_700L
        val free = 1_200L // a RAM livre subestima: o Android libera apps em segundo plano ao carregar
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.QWEN3_0_6B), total)).isTrue()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.GEMMA_4_E2B), total)).isTrue()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.DEEPSEEK_R1_1_5B), total)).isTrue()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.SMOLLM2_1_7B), total)).isFalse()
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.GEMMA_4_E4B), total)).isFalse()
    }

    /** Tab S10 Lite (~5,8 GB; 2,3 GB livres): SmolLM2 rodou a 3,3 GB; o E4B (4,4–4,5 GB) não cabe. */
    @Test
    fun `on a 6 GB tablet SmolLM2 loads and the E4B is refused`() {
        val total = 5_700L
        val free = 2_300L
        ModelCatalog.TEXT_ALL.filter { it != ModelCatalog.GEMMA_4_E4B }.forEach {
            assertThat(RamGuard.enough(free, estimate(it), total)).isTrue()
        }
        assertThat(RamGuard.enough(free, estimate(ModelCatalog.GEMMA_4_E4B), total)).isFalse()
    }

    /** Vaio TL10 (~7,6 GB; 4,8 GB livres): o E4B rodou a 4,4–4,5 GB. */
    @Test
    fun `on an 8 GB device every text model loads`() {
        ModelCatalog.TEXT_ALL.forEach { assertThat(RamGuard.enough(4_800L, estimate(it), 7_400L)).isTrue() }
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

    /** A pré-carga é especulativa: além de caber no aparelho, exige RAM livre de verdade (piso e estimativa). */
    @Test
    fun `preload also needs the model to fit the device`() {
        assertThat(RamGuard.enoughForPreload(availableMb = 5_000, estimateMb = 3_072, totalMb = 4_000)).isFalse()
        assertThat(RamGuard.enoughForPreload(availableMb = 5_000, estimateMb = 3_072, totalMb = 5_700)).isTrue()
        assertThat(RamGuard.enoughForPreload(availableMb = 1_200, estimateMb = 2_048, totalMb = 3_700)).isFalse()
    }
}
