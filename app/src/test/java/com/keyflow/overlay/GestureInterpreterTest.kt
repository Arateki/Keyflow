package com.keyflow.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GestureInterpreterTest {

    private var now = 0L

    @Test
    fun `single tap emits tap on release`() {
        val gestures = GestureInterpreter(clock = { now })

        assertThat(gestures.onDown()).isNull()
        now = 80

        assertThat(gestures.onUp()).isEqualTo(GestureEvent.TAP)
    }

    @Test
    fun `three taps inside window emit triple tap on third release`() {
        val gestures = GestureInterpreter(tripleTapWindowMs = 400, clock = { now })

        gestures.onDown()
        assertThat(gestures.onUp()).isEqualTo(GestureEvent.TAP)
        now = 120
        gestures.onDown()
        assertThat(gestures.onUp()).isEqualTo(GestureEvent.TAP)
        now = 240
        gestures.onDown()

        assertThat(gestures.onUp()).isEqualTo(GestureEvent.TRIPLE_TAP)
    }

    @Test
    fun `third tap outside window starts a new tap sequence`() {
        val gestures = GestureInterpreter(tripleTapWindowMs = 100, clock = { now })

        gestures.onDown()
        gestures.onUp()
        now = 80
        gestures.onDown()
        gestures.onUp()
        now = 240
        gestures.onDown()

        assertThat(gestures.onUp()).isEqualTo(GestureEvent.TAP)
    }

    @Test
    fun `hold emits hold and clears tap sequence`() {
        val gestures = GestureInterpreter(holdThresholdMs = 500, clock = { now })

        gestures.onDown()
        now = 500

        assertThat(gestures.onUp()).isEqualTo(GestureEvent.HOLD)
    }

    @Test
    fun `drag emits drag start once and drag end on release`() {
        val gestures = GestureInterpreter(clock = { now })

        gestures.onDown()

        assertThat(gestures.onMove(dx = 11f, dy = 0f)).isEqualTo(GestureEvent.DRAG_START)
        assertThat(gestures.onMove(dx = 20f, dy = 0f)).isNull()
        assertThat(gestures.onUp()).isEqualTo(GestureEvent.DRAG_END)
    }
}
