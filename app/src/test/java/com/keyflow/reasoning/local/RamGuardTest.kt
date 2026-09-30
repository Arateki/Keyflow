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
}
