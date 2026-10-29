package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
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

    // ── RAM total: o que decide a carga e a pré-carga (medições de pico de VmRSS do `:reasoning`, 2026-10-27) ──────────────────

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
        ModelCatalog.TEXT_ALL.filter { it.validatedOnDevice }.filter { it !in big }.forEach {
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
        ModelCatalog.TEXT_ALL.filter { it.validatedOnDevice }.filter { it != ModelCatalog.QWEN3_4B }.forEach {
            assertThat(RamGuard.enough(4_800L, estimate(it), 7_400L)).isTrue()
        }
        assertThat(RamGuard.enough(4_800L, estimate(ModelCatalog.QWEN3_4B), 7_400L)).isFalse()
    }

    /** Um aparelho de 12 GB mostra ~11 GB de RAM total: carrega o Qwen3 4B (RSS de 4,6–6,1 GB na CPU, segundo o autor) mesmo com pouca RAM livre. */
    @Test
    fun `on a 12 GB device every text model loads`() {
        ModelCatalog.TEXT_ALL.filter { it.validatedOnDevice }.forEach { assertThat(RamGuard.enough(3_000L, estimate(it), 11_200L)).isTrue() }
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
    }

    @Test
    fun `the SmolLM2 floor is 4916 MB, rounded up from 80 percent of 6144`() {
        val estimate = ModelCatalog.SMOLLM2_1_7B.recommendedRamMb / 2
        assertThat(RamGuard.fitsDevice(totalMb = 4_916, estimateMb = estimate)).isTrue()
        assertThat(RamGuard.fitsDevice(totalMb = 4_915, estimateMb = estimate)).isFalse()
    }

    // ── por backend ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the CPU guard by backend is the same rule as always`() {
        assertThat(RamGuard.enoughFor(ComputeBackend.CPU, availableMb = 1_000, estimateMb = 2_048)).isFalse()
        assertThat(RamGuard.enoughFor(ComputeBackend.CPU, availableMb = 50, estimateMb = 2_048, totalMb = 4_000)).isTrue()
        assertThat(RamGuard.enoughFor(ComputeBackend.CPU, availableMb = 6_000, estimateMb = 2_048, totalMb = 3_000)).isFalse()
        assertThat(RamGuard.enoughFor(ComputeBackend.CPU, availableMb = 1_500, estimateMb = 512)).isTrue()
    }

    /** O Qwen3 4B só rodou na GPU (a CPU morreu por memória): a estimativa de CPU não pode barrar a GPU. */
    @Test
    fun `the GPU is never blocked by the CPU estimate`() {
        assertThat(RamGuard.enoughFor(ComputeBackend.GPU, availableMb = 100, estimateMb = 4_096, totalMb = 1_000)).isTrue()
        assertThat(RamGuard.enoughFor(ComputeBackend.GPU, availableMb = null, estimateMb = 4_096)).isTrue()
    }

    /** RPRC-06: a mesma guarda vale ao selecionar o modelo, trocar CPU/GPU ou gerar uma resposta. */
    @Test
    fun `a CPU permite os modelos que cabem no A32 mesmo com 1,1 a 1,5 GB livres`() {
        for (free in listOf(1_100L, 1_500L)) {
            for (spec in listOf(ModelCatalog.QWEN3_0_6B, ModelCatalog.GEMMA_4_E2B, ModelCatalog.DEEPSEEK_R1_1_5B)) {
                assertThat(RamGuard.enoughFor(ComputeBackend.CPU, free, estimate(spec), 3_700)).isTrue()
            }
            assertThat(RamGuard.enoughFor(ComputeBackend.CPU, free, estimate(ModelCatalog.SMOLLM2_1_7B), 3_700)).isFalse()
        }
    }
}
