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

    // ── os frames que a tela vai mostrar (SKP-ORB-06) ────────────────────────

    private fun shownAhead(t: Float, fps: Int, n: Int, hz: Float, window: Int) = OrbTiming.framesShownAhead(t, fps, n, hz, window)

    @Test
    fun `with an unknown refresh rate or a screen that shows every frame the lookahead is every next frame, as always`() {
        for (hz in listOf(0f, -1f, 120f, 144f)) {
            assertThat(shownAhead(0f, 120, 240, hz, 30)).containsExactlyElementsIn((1..30).toList()).inOrder()
        }
        assertThat(shownAhead(0f, 12, 40, 60f, 3)).containsExactly(1, 2, 3).inOrder() // 12 fps numa tela de 60 Hz: todo frame aparece
        assertThat(shownAhead(98f, 12, 40, 0f, 3)).containsExactly(2, 3, 4).inOrder() // frame de agora = 1 (98 ms · 12 / 1000 = 1,17 → 1): os seguintes
    }

    @Test
    fun `the lookahead wraps around the end of the loop`() {
        // 120 fps, 240 frames, t = 1990 ms → frame 238; sem tela conhecida: 239, 0, 1…
        assertThat(shownAhead(1990f, 120, 240, 0f, 4)).containsExactly(239, 0, 1, 2).inOrder()
    }

    @Test
    fun `a 60 Hz screen shows every other frame of a 120 fps animation and only those are looked ahead`() {
        val ahead = shownAhead(1000f, 120, 240, 60f, 30)
        assertThat(ahead.size).isAtLeast(14)
        assertThat(ahead.size).isAtMost(15)
        // Quinze desenhos a 16,67 ms: o frame sobe de dois em dois, nunca o do meio.
        for ((a, b) in ahead.zipWithNext()) assertThat(b - a).isEqualTo(2)
        assertThat(ahead.first() - index(1000f, 120, 240)).isIn(listOf(1, 2))
        assertThat(ahead.last() - index(1000f, 120, 240)).isAtMost(30)
    }

    @Test
    fun `a 90 Hz screen skips one frame in four of a 120 fps animation`() {
        val ahead = shownAhead(0f, 120, 240, 90f, 30)
        assertThat(ahead.size).isAtLeast(21)
        assertThat(ahead.size).isAtMost(22)
        // O padrão é 1, 2, 4, 5, 6, 8…: os índices 3, 7, 11… (3 mod 4) nunca aparecem, e dois seguidos nunca distam mais que 2.
        assertThat(ahead.none { it % 4 == 3 }).isTrue()
        for ((a, b) in ahead.zipWithNext()) assertThat(b - a).isIn(listOf(1, 2))
    }

    @Test
    fun `the lookahead never repeats a frame nor includes the one on screen, and a single frame has none`() {
        for (hz in listOf(30f, 60f, 90f)) {
            for (t in listOf(0f, 333.3f, 1990f, 12345f)) {
                val ahead = shownAhead(t, 120, 240, hz, 30)
                assertThat(ahead).containsNoDuplicates()
                assertThat(ahead).doesNotContain(index(t, 120, 240))
                assertThat(ahead.all { it in 0 until 240 }).isTrue()
            }
        }
        assertThat(shownAhead(0f, 120, 1, 60f, 30)).isEmpty()
        assertThat(shownAhead(0f, 120, 240, 60f, 0)).isEmpty()
    }

    @Test
    fun `every frame the screen will show is in the lookahead, whatever the phase`() {
        // A propriedade que importa: os frames que o desenho de fato pede (um a cada período da tela) estão TODOS na lista.
        for (hz in listOf(60f, 90f)) {
            for (phase in listOf(0f, 1.7f, 5.3f, 8.1f, 12.9f)) {
                val t0 = 1000f + phase
                val ahead = shownAhead(t0, 120, 240, hz, 30).toSet()
                for (draw in 1..(0.25f * hz).toInt() - 1) {
                    val shown = index(t0 + draw * (1000f / hz), 120, 240)
                    if (shown != index(t0, 120, 240)) assertThat(ahead).contains(shown)
                }
            }
        }
    }
}
