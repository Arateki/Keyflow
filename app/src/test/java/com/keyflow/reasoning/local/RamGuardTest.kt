package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
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
}
