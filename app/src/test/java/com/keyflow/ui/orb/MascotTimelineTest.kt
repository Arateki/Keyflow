package com.keyflow.ui.orb

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** O calendário do mascote: piscadas, tremidas de orelha e olhares — função pura do tempo, irregular porém estável. */
class MascotTimelineTest {

    @Test
    fun `the hash is stable, uniform enough and stays in 0 until 1`() {
        var sum = 0f
        val n = 4000
        for (a in 0 until 40) for (b in 0 until 100) {
            val h = MascotTimeline.hash01(a, b)
            assertThat(h).isAtLeast(0f)
            assertThat(h).isLessThan(1f)
            assertThat(MascotTimeline.hash01(a, b)).isEqualTo(h)
            sum += h
        }
        assertThat(sum / n).isWithin(0.03f).of(0.5f)
    }

    @Test
    fun `every state gives the same blink for the same instant, no matter what was asked before`() {
        for (state in OrbState.entries) {
            val expected = (0..4000).map { MascotTimeline.blink(it * 37f, state) }
            // Perguntar em outra ordem (e outras vezes) não muda a resposta: não há estado escondido.
            val shuffled = (0..4000).reversed().map { MascotTimeline.blink(it * 37f, state) }.reversed()
            assertThat(shuffled).isEqualTo(expected)
        }
    }

    @Test
    fun `a blink starts and ends inside its own slot`() {
        for (state in OrbState.entries) {
            val slot = MascotTimeline.blinkSlotMs(state)
            for (k in 0..400) {
                assertThat(MascotTimeline.blinkStart(k, state)).isAtLeast(k * slot)
                assertThat(MascotTimeline.blinkEnd(k, state)).isLessThan((k + 1) * slot)
            }
        }
    }

    @Test
    fun `blinks are irregular but never glued - the gap between the end of one and the start of the next is always large`() {
        for (state in OrbState.entries) {
            val starts = ArrayList<Float>()
            var minGap = Float.MAX_VALUE
            var previousEnd = -1f
            for (k in 0..600) {
                if (!MascotTimeline.hasBlink(k, state)) continue
                val start = MascotTimeline.blinkStart(k, state)
                if (previousEnd >= 0f) minGap = minOf(minGap, start - previousEnd)
                starts.add(start)
                previousEnd = MascotTimeline.blinkEnd(k, state)
            }
            // O vão mínimo entre duas piscadas de fatias vizinhas: bem mais que a piscada dupla (150 ms de olho aberto).
            assertWithMessage("vão mínimo entre piscadas em %s", state).that(minGap).isAtLeast(700f)
            // Irregular: os intervalos entre os começos não se repetem nem andam num compasso só.
            val gaps = starts.zipWithNext { a, b -> b - a }
            assertWithMessage("intervalos distintos em %s", state).that(gaps.map { (it / 5f).toInt() }.toSet().size).isGreaterThan(100)
            val slot = MascotTimeline.blinkSlotMs(state)
            assertWithMessage("amplitude dos intervalos em %s", state).that(gaps.max() - gaps.min()).isGreaterThan(slot * 0.5f)
        }
    }

    @Test
    fun `a double blink keeps the eyes open between the two blinks`() {
        for (state in OrbState.entries) {
            var doubles = 0
            for (k in 0..600) {
                if (!MascotTimeline.hasBlink(k, state) || !MascotTimeline.isDoubleBlink(k, state)) continue
                doubles++
                val start = MascotTimeline.blinkStart(k, state)
                val one = MascotTimeline.blinkMs(state)
                // No meio do vão entre as duas, a pálpebra está aberta.
                assertThat(MascotTimeline.blink(start + one + MascotTimeline.DOUBLE_BLINK_GAP_MS / 2f, state)).isEqualTo(0f)
            }
            if (state == OrbState.Paused) assertThat(doubles).isEqualTo(0) else assertThat(doubles).isGreaterThan(20)
        }
    }

    @Test
    fun `the eyelid is always between 0 and 1, shuts at the middle of a blink and rests open most of the time`() {
        for (state in OrbState.entries) {
            var closed = 0
            val samples = 60_000
            for (i in 0 until samples) {
                val v = MascotTimeline.blink(i * 20f, state)
                assertThat(v).isIn(com.google.common.collect.Range.closed(0f, 1f))
                if (v > 0.05f) closed++
            }
            assertWithMessage("fração de tempo com o olho fechado em %s", state).that(closed.toFloat() / samples).isLessThan(0.12f)
            for (k in 0..50) {
                if (!MascotTimeline.hasBlink(k, state)) continue
                val peak = MascotTimeline.blinkStart(k, state) + MascotTimeline.blinkMs(state) * 0.35f
                assertThat(MascotTimeline.blink(peak, state)).isWithin(0.001f).of(1f)
            }
        }
    }

    @Test
    fun `the sleepy blink is slower than the awake one`() {
        assertThat(MascotTimeline.blinkMs(OrbState.Paused)).isGreaterThan(MascotTimeline.blinkMs(OrbState.Idle) * 3f)
        assertThat(MascotTimeline.blinkSlotMs(OrbState.Paused)).isGreaterThan(MascotTimeline.blinkSlotMs(OrbState.Idle))
        // Atento, pisca menos que em repouso.
        assertThat(MascotTimeline.blinkSlotMs(OrbState.Listening)).isGreaterThan(MascotTimeline.blinkSlotMs(OrbState.Idle))
    }

    @Test
    fun `an ear flick is a short damped wiggle on one ear or on both`() {
        for (state in OrbState.entries) {
            var single = 0
            var both = 0
            for (k in 0..400) {
                if (!MascotTimeline.hasFlick(k, state)) continue
                val start = MascotTimeline.flickStart(k, state)
                var left = 0f
                var right = 0f
                var t = start
                while (t <= start + MascotTimeline.FLICK_MS) {
                    left = maxOf(left, kotlin.math.abs(MascotTimeline.earFlickDeg(t, state, rightEar = false)))
                    right = maxOf(right, kotlin.math.abs(MascotTimeline.earFlickDeg(t, state, rightEar = true)))
                    t += 3f
                }
                assertThat(left).isAtMost(12f)
                assertThat(right).isAtMost(12f)
                when (MascotTimeline.flickSide(k)) {
                    0 -> { both++; assertThat(left).isGreaterThan(1f); assertThat(right).isGreaterThan(1f) }
                    else -> { single++; assertThat(minOf(left, right)).isEqualTo(0f); assertThat(maxOf(left, right)).isGreaterThan(1f) }
                }
                // Fora da janela da tremida, as orelhas estão quietas.
                assertThat(MascotTimeline.earFlickDeg(start - 5f, state, false)).isEqualTo(0f)
                assertThat(MascotTimeline.earFlickDeg(start + MascotTimeline.FLICK_MS + 5f, state, true)).isEqualTo(0f)
            }
            assertThat(single).isGreaterThan(0)
            assertThat(both).isGreaterThan(0)
        }
    }

    @Test
    fun `the thinking mascot never stops looking around, alternating side up, and the sleepy one never looks away`() {
        val slot = MascotTimeline.glanceSlotMs(OrbState.Transcribing)
        for (k in 0..60) {
            assertThat(MascotTimeline.hasGlance(k, OrbState.Transcribing)).isTrue()
            val mid = k * slot + slot * 0.4f
            assertThat(MascotTimeline.glanceAmount(mid, OrbState.Transcribing)).isWithin(0.001f).of(1f)
            assertThat(MascotTimeline.glanceTargetY(mid, OrbState.Transcribing)).isLessThan(0f)
            if (k > 0) {
                val before = MascotTimeline.glanceTargetX(mid - slot, OrbState.Transcribing)
                assertThat(MascotTimeline.glanceTargetX(mid, OrbState.Transcribing)).isEqualTo(-before)
            }
        }
        for (i in 0..6000) assertThat(MascotTimeline.glanceAmount(i * 41f, OrbState.Paused)).isEqualTo(0f)
    }

    @Test
    fun `a glance is eased between 0 and 1 and goes back to the center`() {
        for (state in OrbState.entries) {
            var peaks = 0
            for (i in 0..20_000) {
                val g = MascotTimeline.glanceAmount(i * 13f, state)
                assertThat(g).isIn(com.google.common.collect.Range.closed(0f, 1f))
                if (g == 1f) peaks++
                assertThat(MascotTimeline.glanceTargetX(i * 13f, state)).isIn(com.google.common.collect.Range.closed(-1f, 1f))
                assertThat(MascotTimeline.glanceTargetY(i * 13f, state)).isIn(com.google.common.collect.Range.closed(-1f, 1f))
            }
            if (state != OrbState.Paused) assertThat(peaks).isGreaterThan(0)
        }
    }

    @Test
    fun `the tail wag is an event inside its slot, 0 to 1`() {
        var wags = 0
        for (k in 0..300) {
            if (!MascotTimeline.hasWag(k)) continue
            wags++
            assertThat(MascotTimeline.wagStart(k)).isAtLeast(k * MascotTimeline.WAG_SLOT_MS)
            assertThat(MascotTimeline.wagStart(k) + MascotTimeline.WAG_MS).isLessThan((k + 1) * MascotTimeline.WAG_SLOT_MS)
            assertThat(MascotTimeline.wag(MascotTimeline.wagStart(k) + MascotTimeline.WAG_MS / 2f)).isWithin(0.001f).of(1f)
            assertThat(MascotTimeline.wag(MascotTimeline.wagStart(k) - 5f)).isEqualTo(0f)
        }
        assertThat(wags).isGreaterThan(100)
    }
}
