package com.keyflow.ime.keyboard.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Aceleração do repeat-on-hold do backspace (RND-04). Pura — a View só chama `Handler.postDelayed`. */
class RepeatScheduleTest {

    @Test
    fun `first repeat waits the initial delay`() {
        assertThat(RepeatSchedule.delayForTick(0)).isEqualTo(400L)
    }

    @Test
    fun `delay decreases as ticks accumulate`() {
        val first = RepeatSchedule.delayForTick(0)
        val third = RepeatSchedule.delayForTick(3)
        val tenth = RepeatSchedule.delayForTick(10)
        assertThat(third).isLessThan(first)
        assertThat(tenth).isLessThan(third)
    }

    @Test
    fun `delay never goes below the floor`() {
        assertThat(RepeatSchedule.delayForTick(1000)).isEqualTo(RepeatSchedule.MIN_DELAY_MS)
    }

    @Test
    fun `delay is never negative or zero`() {
        for (tick in 0..50) {
            assertThat(RepeatSchedule.delayForTick(tick)).isGreaterThan(0L)
        }
    }
}
