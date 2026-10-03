package com.keyflow.ui.orb

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import androidx.compose.ui.graphics.toArgb
import org.junit.Test
import kotlin.math.abs

/** A pose do mascote: pura, estável, viva (nunca periódica) e com a personalidade de cada estado. */
class MascotPoseTest {

    private fun values(p: MascotPose): List<Float> = listOf(
        p.scaleX, p.scaleY, p.lean, p.bob, p.earL, p.earR, p.earBendL, p.earBendR, p.earLen, p.eyeOpen, p.eyeScale,
        p.gazeX, p.gazeY, p.sparkle, p.smile, p.mouthOpen, p.cheek, p.tail, p.tailBend, p.chestGlow, p.soundPhase,
        p.zPhase, p.thinkPhase, p.energy,
    )

    private val instants = (0..400).map { it * 53f }

    @Test
    fun `the pose is a pure function of time and state - a frozen clock gives a frozen frame`() {
        for (state in OrbState.entries) {
            val reused = MascotPose()
            for (t in instants) {
                val fresh = values(mascotPose(t, state))
                // Uma instância reaproveitada, que já passou por outros instantes e estados, dá o mesmo.
                reused.update(t + 777f, OrbState.entries[(t.toInt() / 53) % 4])
                reused.update(t, state)
                assertThat(values(reused)).isEqualTo(fresh)
                assertThat(values(mascotPose(t, state))).isEqualTo(fresh)
            }
        }
    }

    @Test
    fun `the pose never repeats - no whole number of seconds brings the same frame back`() {
        for (state in OrbState.entries) {
            val base = values(mascotPose(1234f, state))
            for (periodS in 1..60) {
                assertWithMessage("%s repetiu em %s s", state, periodS)
                    .that(values(mascotPose(1234f + periodS * 1000f, state))).isNotEqualTo(base)
            }
        }
    }

    @Test
    fun `the body moves smoothly - no jump between two frames of the same state`() {
        for (state in OrbState.entries) {
            var prev = mascotPose(0f, state)
            var t = 16f
            while (t < 30_000f) {
                val p = mascotPose(t, state)
                assertThat(abs(p.scaleX - prev.scaleX)).isLessThan(0.004f)
                assertThat(abs(p.scaleY - prev.scaleY)).isLessThan(0.006f)
                assertThat(abs(p.lean - prev.lean)).isLessThan(0.5f)
                assertThat(abs(p.bob - prev.bob)).isLessThan(0.002f)
                prev = p
                t += 16f
            }
        }
    }

    @Test
    fun `breathing squashes and stretches around the same volume`() {
        for (state in OrbState.entries) {
            for (t in instants) {
                val p = mascotPose(t, state)
                // Estica na vertical enquanto afina na horizontal (nunca os dois crescendo ou encolhendo muito).
                assertThat(p.scaleX * p.scaleY).isIn(com.google.common.collect.Range.closed(0.95f, 1.07f))
                assertThat(p.scaleX).isIn(com.google.common.collect.Range.closed(0.97f, 1.04f))
                assertThat(p.scaleY).isIn(com.google.common.collect.Range.closed(0.97f, 1.07f))
            }
        }
    }

    @Test
    fun `the ears stand up listening, rest at ease idle and droop paused`() {
        fun meanSpread(state: OrbState) = instants.map { t ->
            val p = mascotPose(t, state)
            (abs(p.earL) + abs(p.earR)) / 2f
        }.average().toFloat()
        val listening = meanSpread(OrbState.Listening)
        val idle = meanSpread(OrbState.Idle)
        val paused = meanSpread(OrbState.Paused)
        assertThat(listening).isLessThan(idle - 8f)
        assertThat(paused).isGreaterThan(idle + 20f)
    }

    @Test
    fun `thinking ears alternate - while one is up the other is down`() {
        // Um ciclo da alternância: a soma das aberturas fica quase constante e a diferença troca de sinal.
        var sawLeftUp = false
        var sawRightUp = false
        for (t in instants) {
            val p = mascotPose(t, OrbState.Transcribing)
            if (abs(p.earL) < abs(p.earR) - 25f) sawLeftUp = true
            if (abs(p.earR) < abs(p.earL) - 25f) sawRightUp = true
        }
        assertThat(sawLeftUp).isTrue()
        assertThat(sawRightUp).isTrue()
    }

    @Test
    fun `eyes are wide listening and heavy asleep`() {
        fun mean(state: OrbState, f: (MascotPose) -> Float) = instants.map { f(mascotPose(it, state)) }.average().toFloat()
        assertThat(mean(OrbState.Listening) { it.eyeScale }).isGreaterThan(mean(OrbState.Idle) { it.eyeScale } + 0.08f)
        assertThat(mean(OrbState.Paused) { it.eyeOpen }).isLessThan(0.6f)
        assertThat(mean(OrbState.Idle) { it.eyeOpen }).isGreaterThan(0.9f)
        assertThat(mean(OrbState.Listening) { it.eyeOpen }).isGreaterThan(0.9f)
    }

    @Test
    fun `listening swells with the voice and no other state does`() {
        var minE = 1f
        var maxE = 0f
        for (t in instants) {
            val e = mascotPose(t, OrbState.Listening).energy
            minE = minOf(minE, e)
            maxE = maxOf(maxE, e)
            for (state in listOf(OrbState.Idle, OrbState.Paused, OrbState.Transcribing)) {
                assertThat(mascotPose(t, state).energy).isEqualTo(0f)
                assertThat(mascotPose(t, state).chestGlow).isEqualTo(0f)
            }
        }
        assertThat(maxE - minE).isGreaterThan(0.5f)
    }

    @Test
    fun `the ears and the tail drag behind the body - they follow with a delay`() {
        // Com o corpo balançando, o ângulo da orelha tem uma parte (a do atraso) que NÃO é o balanço do corpo agora.
        var maxLag = 0f
        for (t in instants) {
            val lag = MascotPose.leanAt(t - 160f, OrbState.Idle) - MascotPose.leanAt(t, OrbState.Idle)
            maxLag = maxOf(maxLag, abs(lag))
        }
        assertThat(maxLag).isGreaterThan(0.2f)
        // E o atraso aparece mesmo na pose: com o corpo parado no tempo, orelha e rabo respondem ao balanço de antes.
        val p = mascotPose(10_000f, OrbState.Idle)
        val q = mascotPose(10_000f + 1f, OrbState.Idle)
        assertThat(abs(p.earL - q.earL)).isLessThan(0.2f)
    }

    @Test
    fun `ear and tail tips never leave the window`() {
        for (state in OrbState.entries) {
            var t = 0f
            while (t < 60_000f) {
                val p = mascotPose(t, state)
                for (hasCenter in listOf(false, true)) for (right in listOf(false, true)) {
                    val tip = mascotEarTip(p, right, hasCenter)
                    // 0,5 é a borda; a ponta ainda leva o filete e o contorno (~0,025) por fora.
                    assertWithMessage("orelha %s em %s ms (%s, centro %s)", right, t, state, hasCenter).that(tip.y).isAtLeast(-0.475f)
                    assertWithMessage("orelha x %s em %s ms (%s)", right, t, state).that(abs(tip.x)).isAtMost(0.475f)
                }
                val tail = mascotTailTip(p, hasCenter = false)
                assertWithMessage("rabo em %s ms (%s)", t, state).that(tail.x).isAtMost(0.475f)
                assertThat(tail.y).isAtLeast(-0.475f)
                t += 41f
            }
        }
    }

    @Test
    fun `the effect phases loop inside 0 to 1`() {
        for (state in OrbState.entries) for (t in instants) {
            val p = mascotPose(t, state)
            for (ph in listOf(p.soundPhase, p.zPhase, p.thinkPhase)) assertThat(ph).isIn(com.google.common.collect.Range.closedOpen(0f, 1f))
        }
        assertThat(MascotPose.wrapPhase(1.25f)).isWithin(1e-6f).of(0.25f)
        assertThat(MascotPose.wrapPhase(0.5f)).isEqualTo(0.5f)
    }

    @Test
    fun `the ink on the belly reads - at least 4,5 to 1 against the cream`() {
        // A tinta do centro (marrom) sobre as duas pontas do degradê da barriga (creme claro e creme sombreado).
        for (belly in listOf(MascotBellyTop, MascotBellyBottom)) {
            assertThat(contrast(MascotInk.toArgb(), belly.toArgb())).isAtLeast(4.5)
        }
    }

    /** Razão de contraste da WCAG entre duas cores ARGB opacas. */
    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
