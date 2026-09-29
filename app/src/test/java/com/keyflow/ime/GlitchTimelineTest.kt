package com.keyflow.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O calendário das falhas do teclado Glitch: função pura do tempo, com rajadas curtas e espaçadas. */
class GlitchTimelineTest {

    @Test
    fun `every burst starts and ends inside its own period, so bursts never overlap`() {
        for (k in 0..200) {
            val start = GlitchTimeline.burstStart(k)
            val end = start + GlitchTimeline.burstLength(k)
            assertThat(start).isAtLeast(k * GlitchTimeline.PERIOD_MS)
            assertThat(end).isLessThan((k + 1) * GlitchTimeline.PERIOD_MS)
        }
    }

    @Test
    fun `bursts are short and the keyboard is calm between them`() {
        for (k in 0..50) {
            val length = GlitchTimeline.burstLength(k)
            assertThat(length).isAtLeast(150f)
            assertThat(length).isAtMost(400f)
            // A rajada é uma fração pequena do período: o resto do tempo o teclado fica parado (sem custo).
            assertThat(length / GlitchTimeline.PERIOD_MS).isLessThan(0.12f)
        }
        assertThat(GlitchTimeline.intensity(GlitchTimeline.burstStart(3) - 50f)).isEqualTo(0f)
        assertThat(GlitchTimeline.intensity(GlitchTimeline.burstStart(3) + GlitchTimeline.burstLength(3) + 50f)).isEqualTo(0f)
    }

    @Test
    fun `the intensity peaks at 1 in the middle of a burst and always stays between 0 and 1`() {
        for (k in 0..20) assertThat(GlitchTimeline.intensity(GlitchTimeline.peakOf(k))).isWithin(0.001f).of(1f)
        var t = 0f
        while (t < 20_000f) {
            assertThat(GlitchTimeline.intensity(t)).isIn(com.google.common.collect.Range.closed(0f, 1f))
            t += 23f
        }
    }

    @Test
    fun `the next burst is always ahead of now`() {
        var t = 0f
        while (t < 15_000f) {
            assertThat(GlitchTimeline.nextBurstStart(t)).isGreaterThan(t)
            t += 137f
        }
    }

    @Test
    fun `the bands of a burst are sorted, inside the keyboard, never overlap and grow with the intensity`() {
        for (k in 0..40) {
            val bands = GlitchTimeline.bands(k, 1f)
            assertThat(bands).isNotEmpty()
            assertThat(bands.size).isAtMost(3)
            var edge = 0f
            for (band in bands) {
                assertThat(band.top).isAtLeast(edge)
                assertThat(band.bottom).isGreaterThan(band.top)
                assertThat(band.bottom).isAtMost(1f)
                edge = band.bottom
            }
            assertThat(GlitchTimeline.bands(k, 0f)).isEmpty()
            assertThat(bands.maxOf { kotlin.math.abs(it.dxDp) })
                .isAtLeast(GlitchTimeline.bands(k, 0.3f).maxOfOrNull { kotlin.math.abs(it.dxDp) } ?: 0f)
        }
    }

    @Test
    fun `the same burst always gives the same bands - a frozen clock is a fixed frame`() {
        for (k in 0..10) {
            val a = GlitchTimeline.bands(k, 0.8f).map { Triple(it.top, it.bottom, it.dxDp) }
            val b = GlitchTimeline.bands(k, 0.8f).map { Triple(it.top, it.bottom, it.dxDp) }
            assertThat(a).isEqualTo(b)
        }
    }
}
