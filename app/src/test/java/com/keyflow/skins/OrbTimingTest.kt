package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** SKP-ORB-01: o frame no ar é `floor(tempoMs · fps / 1000) mod n`, determinístico e sem escapar do intervalo. */
class OrbTimingTest {

    private fun index(t: Float, fps: Int, n: Int) = OrbTiming.frameIndex(t, fps, n)

    @Test
    fun `the frame follows time at the given fps and loops`() {
        // 10 fps → um frame a cada 100 ms; 3 frames em loop.
        assertThat(index(0f, 10, 3)).isEqualTo(0)
        assertThat(index(99f, 10, 3)).isEqualTo(0)
        assertThat(index(100f, 10, 3)).isEqualTo(1)
        assertThat(index(250f, 10, 3)).isEqualTo(2)
        assertThat(index(300f, 10, 3)).isEqualTo(0)
        assertThat(index(1234f, 10, 3)).isEqualTo(0) // frame 12 → 12 mod 3
        assertThat(index(1300f, 10, 3)).isEqualTo(1)
    }

    @Test
    fun `it matches the formula in the spec for many times and rates`() {
        for (fps in listOf(1, 8, 12, 24, 30, 60)) {
            for (n in listOf(2, 3, 12, 24, 64)) {
                for (t in 0..5000 step 37) {
                    val expected = Math.floorMod((t.toLong() * fps) / 1000, n.toLong()).toInt()
                    assertThat(index(t.toFloat(), fps, n)).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun `a boundary falls on the new frame even with float rounding`() {
        // 12 fps: o frame 1 começa em 83,333… ms; o Float 83.333336 não pode ficar um frame atrás por arredondamento.
        assertThat(index(1000f / 12f, 12, 12)).isEqualTo(1)
        assertThat(index(2000f / 12f, 12, 12)).isEqualTo(2)
        assertThat(index(5000f / 24f * 7f, 24, 64)).isEqualTo(35)
        // E logo antes da fronteira ainda é o frame anterior.
        assertThat(index(83.3f, 12, 12)).isEqualTo(0)
    }

    @Test
    fun `a single frame or no frame is static`() {
        assertThat(index(123456f, 12, 1)).isEqualTo(0)
        assertThat(index(123456f, 12, 0)).isEqualTo(0)
        assertThat(index(5f, 0, 5)).isEqualTo(0)
    }

    @Test
    fun `negative and huge times stay inside the range`() {
        for (t in listOf(-1f, -100f, -12345.6f, 1e7f, 3.4e9f)) {
            val i = index(t, 24, 5)
            assertThat(i).isAtLeast(0)
            assertThat(i).isLessThan(5)
        }
        assertThat(index(-100f, 10, 3)).isEqualTo(2) // um frame antes do 0 é o último
    }

    @Test
    fun `the same time always gives the same frame`() {
        repeat(100) { assertThat(index(4321.5f, 24, 24)).isEqualTo(index(4321.5f, 24, 24)) }
    }
}
