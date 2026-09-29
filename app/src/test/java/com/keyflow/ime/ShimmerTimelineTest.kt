package com.keyflow.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O calendário do brilho do Rose Gold: um evento curto por período, função pura do tempo. */
class ShimmerTimelineTest {

    @Test
    fun `every shimmer starts and ends inside its own period`() {
        for (k in 0..200) {
            val start = ShimmerTimeline.eventStart(k)
            assertThat(start).isAtLeast(k * ShimmerTimeline.PERIOD_MS)
            assertThat(start + ShimmerTimeline.LENGTH_MS).isLessThan((k + 1) * ShimmerTimeline.PERIOD_MS)
        }
    }

    @Test
    fun `the progress runs from 0 to 1 through the event and is -1 outside it`() {
        for (k in 0..20) {
            val start = ShimmerTimeline.eventStart(k)
            assertThat(ShimmerTimeline.progress(start + 1f)).isWithin(0.01f).of(0f)
            assertThat(ShimmerTimeline.progress(ShimmerTimeline.peakOf(k))).isWithin(0.001f).of(0.5f)
            assertThat(ShimmerTimeline.progress(start + ShimmerTimeline.LENGTH_MS - 1f)).isWithin(0.01f).of(1f)
            assertThat(ShimmerTimeline.progress(start - 10f)).isEqualTo(-1f)
            assertThat(ShimmerTimeline.progress(start + ShimmerTimeline.LENGTH_MS + 10f)).isEqualTo(-1f)
        }
    }

    @Test
    fun `the keyboard rests most of the time - the event is a small part of the period`() {
        assertThat(ShimmerTimeline.LENGTH_MS / ShimmerTimeline.PERIOD_MS).isLessThan(0.2f)
    }

    @Test
    fun `the next event is always ahead of now`() {
        var t = 0f
        while (t < 30_000f) {
            assertThat(ShimmerTimeline.nextEventStart(t)).isGreaterThan(t)
            t += 173f
        }
    }

    @Test
    fun `the sparkles sit inside the keyboard and are stable for the same event`() {
        for (k in 0..30) {
            for (i in 0 until ShimmerTimeline.SPARKLES) {
                val (x, y) = ShimmerTimeline.sparkle(k, i)
                assertThat(x).isIn(com.google.common.collect.Range.closed(0.05f, 0.95f))
                assertThat(y).isIn(com.google.common.collect.Range.closed(0.1f, 0.9f))
                assertThat(ShimmerTimeline.sparkle(k, i)).isEqualTo(x to y)
            }
        }
    }
}
