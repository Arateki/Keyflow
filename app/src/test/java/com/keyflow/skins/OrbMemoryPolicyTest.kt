package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A conta do orçamento de memória da orbe de pacote (SKP-ORB-06): o que um estado ocupa, o teto do aparelho e o orçamento que sai disso. */
class OrbMemoryPolicyTest {

    private val mb = 1024L * 1024

    @Test
    fun `a frame costs side squared times four bytes and a state costs that times its frames`() {
        assertThat(OrbMemoryPolicy.stateBytes(280, 1)).isEqualTo(313_600)
        assertThat(OrbMemoryPolicy.stateBytes(195, 1)).isEqualTo(152_100)
        assertThat(OrbMemoryPolicy.stateBytes(156, 120)).isEqualTo(11_681_280)
        assertThat(OrbMemoryPolicy.stateBytes(110, 240)).isEqualTo(11_616_000)
        assertThat(OrbMemoryPolicy.stateBytes(280, 240)).isEqualTo(75_264_000)
    }

    @Test
    fun `the basic budget is the 16 MB the orb always had`() {
        assertThat(OrbMemoryPolicy.BASE_BYTES).isEqualTo(16 * mb)
        assertThat(PackOrb.CACHE_BYTES).isEqualTo(16 * mb)
    }

    @Test
    fun `a state that fits the basic budget never changes it, whatever the device allows`() {
        for (ceiling in listOf(0L, 16 * mb, 64 * mb, 1024 * mb)) {
            assertThat(OrbMemoryPolicy.budgetFor(0, ceiling)).isEqualTo(16 * mb)
            assertThat(OrbMemoryPolicy.budgetFor(1, ceiling)).isEqualTo(16 * mb)
            assertThat(OrbMemoryPolicy.budgetFor(13_770_000, ceiling)).isEqualTo(16 * mb) // 45 frames a 280 px: o exemplo-neon
            assertThat(OrbMemoryPolicy.budgetFor(16 * mb, ceiling)).isEqualTo(16 * mb)
        }
    }

    @Test
    fun `a state that does not fit grows the budget to what it needs, up to the device ceiling`() {
        assertThat(OrbMemoryPolicy.budgetFor(18_252_000, 64 * mb)).isEqualTo(18_252_000) // 120 frames a 195 px
        assertThat(OrbMemoryPolicy.budgetFor(36_504_000, 64 * mb)).isEqualTo(36_504_000) // 240 frames a 195 px
        assertThat(OrbMemoryPolicy.budgetFor(75_264_000, 64 * mb)).isEqualTo(64 * mb) // 240 a 280 px não cabe nem no A32: o teto
        assertThat(OrbMemoryPolicy.budgetFor(75_264_000, 48 * mb)).isEqualTo(48 * mb)
    }

    @Test
    fun `the budget never goes under 16 MB nor over the absolute ceiling`() {
        assertThat(OrbMemoryPolicy.budgetFor(40 * mb, 4 * mb)).isEqualTo(16 * mb) // teto de aparelho absurdamente baixo
        assertThat(OrbMemoryPolicy.budgetFor(500 * mb, 1000 * mb)).isEqualTo(72 * mb) // pedido absurdamente alto, aparelho folgado
        assertThat(OrbMemoryPolicy.budgetFor(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualTo(OrbMemoryPolicy.ABSOLUTE_MAX_BYTES)
    }

    @Test
    fun `the device ceiling is a quarter of the memory class between 16 MB and the absolute ceiling`() {
        assertThat(OrbMemoryPolicy.deviceCeiling(256, lowRam = false)).isEqualTo(64 * mb) // o A32
        assertThat(OrbMemoryPolicy.deviceCeiling(192, lowRam = false)).isEqualTo(48 * mb) // o emulador API 34
        assertThat(OrbMemoryPolicy.deviceCeiling(128, lowRam = false)).isEqualTo(32 * mb)
        assertThat(OrbMemoryPolicy.deviceCeiling(64, lowRam = false)).isEqualTo(16 * mb) // 1/4 = 16: já é a base
        assertThat(OrbMemoryPolicy.deviceCeiling(32, lowRam = false)).isEqualTo(16 * mb) // nunca abaixo da base
        assertThat(OrbMemoryPolicy.deviceCeiling(512, lowRam = false)).isEqualTo(72 * mb) // 128 MB de fração: o teto absoluto
        assertThat(OrbMemoryPolicy.deviceCeiling(0, lowRam = false)).isEqualTo(16 * mb)
        assertThat(OrbMemoryPolicy.deviceCeiling(-1, lowRam = false)).isEqualTo(16 * mb)
    }

    @Test
    fun `a low RAM device keeps the 16 MB whatever its memory class says`() {
        for (memoryClass in listOf(48, 128, 256, 512, 1024)) assertThat(OrbMemoryPolicy.deviceCeiling(memoryClass, lowRam = true)).isEqualTo(16 * mb)
    }

    @Test
    fun `the largest state the format accepts fits the absolute ceiling exactly at the largest orb size`() {
        val largest = OrbMemoryPolicy.stateBytes(OrbMemoryPolicy.MAX_SIDE_PX, SkinPackReader.MAX_FRAMES_PER_STATE)
        assertThat(largest).isAtMost(OrbMemoryPolicy.ABSOLUTE_MAX_BYTES)
        assertThat(largest).isGreaterThan(OrbMemoryPolicy.ABSOLUTE_MAX_BYTES - mb) // folga de menos de 1 MB: o teto é o estado máximo, não um número solto
    }

    @Test
    fun `the old limit of 64 frames at the largest size did not fit the basic budget, which is why the cache grows for it`() {
        assertThat(OrbMemoryPolicy.stateBytes(280, 64)).isGreaterThan(16 * mb)
        assertThat(OrbMemoryPolicy.stateBytes(280, 53)).isAtMost(16 * mb)
        assertThat(OrbMemoryPolicy.stateBytes(280, 54)).isGreaterThan(16 * mb)
    }

    @Test
    fun `megabytes round up so that what passes 16 MB never reads as 16`() {
        assertThat(OrbMemoryPolicy.megabytes(16 * mb)).isEqualTo(16)
        assertThat(OrbMemoryPolicy.megabytes(16 * mb + 1)).isEqualTo(17)
        assertThat(OrbMemoryPolicy.megabytes(OrbMemoryPolicy.stateBytes(280, 64))).isEqualTo(20)
        assertThat(OrbMemoryPolicy.megabytes(0)).isEqualTo(0)
    }
}
