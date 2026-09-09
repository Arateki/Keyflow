package com.keyflow.ui.tour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O pulso da seta de avançar é função pura do tempo, e não uma animação própria, por dois motivos: uma
 * `rememberInfiniteTransition` deixaria a tela eternamente não-ociosa (quebrando todo teste Compose que
 * a renderiza) e tornaria os goldens não-determinísticos. Vindo do relógio do tour, ela congela junto.
 */
class TourArrowPulseTest {

    @Test
    fun staysWithinASubtleRange() {
        for (t in 0..4000 step 50) {
            val v = tourArrowPulse(t.toFloat())
            assertTrue("fora da faixa em ${t}ms: $v", v in TOUR_ARROW_PULSE_FLOOR..1f)
        }
    }

    @Test
    fun neverLooksDisabled() {
        // Uma seta que passa a maior parte do tempo mais apagada que a vizinha lê como desabilitada —
        // o oposto do convite que ela deveria ser.
        assertTrue("o piso está baixo demais", TOUR_ARROW_PULSE_FLOOR >= 0.5f)
    }

    @Test
    fun actuallyPulsesInsteadOfSittingStill() {
        val values = (0..2000 step 100).map { tourArrowPulse(it.toFloat()) }

        assertTrue("deveria variar ao longo do tempo", values.max() - values.min() > 0.3f)
    }

    @Test
    fun repeatsOnEveryCycle() {
        assertEquals(tourArrowPulse(0f), tourArrowPulse(TOUR_ARROW_PULSE_PERIOD_MS.toFloat()), 0.001f)
    }

    @Test
    fun aFrozenClockGivesAFrozenPulse() {
        assertEquals(tourArrowPulse(1234f), tourArrowPulse(1234f), 0f)
    }
}
