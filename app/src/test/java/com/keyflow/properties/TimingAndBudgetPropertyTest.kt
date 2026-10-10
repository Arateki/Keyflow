package com.keyflow.properties

import com.keyflow.ime.keyboard.layout.RepeatSchedule
import com.keyflow.skins.OrbMemoryPolicy
import com.keyflow.skins.OrbTiming
import io.kotest.property.Arb
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contas de tempo e memória da orbe de pacote e da repetição de tecla, para quaisquer valores — inclusive os absurdos. */
class TimingAndBudgetPropertyTest {

    /** O frame da orbe sempre existe — tempo negativo, enorme, NaN ou infinito não pode pedir um frame fora do pacote. */
    @Test
    fun theOrbFrameAlwaysExists() = runBlocking<Unit> {
        checkAll(ITERATIONS, Arb.float(), Arb.int(-5..240), Arb.int(1..400)) { time, fps, count ->
            val index = OrbTiming.frameIndex(time, fps, count)
            assertTrue("frame $index de $count (t=$time, fps=$fps)", index in 0 until count)
        }
    }

    @Test
    fun framesShownAheadAreDistinctInRangeAndNeverTheCurrentOne() = runBlocking<Unit> {
        checkAll(ITERATIONS, Arb.int(0..600_000), Arb.int(1..240), Arb.int(2..400), Arb.int(0..240), Arb.int(0..30)) { t, fps, count, hz, window ->
            val ahead = OrbTiming.framesShownAhead(t.toFloat(), fps, count, hz.toFloat(), window)
            val now = OrbTiming.frameIndex(t.toFloat(), fps, count)
            assertTrue(ahead.all { it in 0 until count })
            assertTrue("repetiu: $ahead", ahead.size == ahead.toSet().size || window >= count)
            assertTrue("pediu mais que a janela: $ahead", ahead.size <= window)
            if (window < count) assertTrue("incluiu o frame de agora ($now): $ahead", now !in ahead)
        }
    }

    /** O orçamento do cache fica entre o básico e o teto absoluto, e nunca diminui quando a orbe pede mais. */
    @Test
    fun theBudgetStaysBetweenTheBasicAndTheCeilingAndGrowsWithTheDemand() = runBlocking<Unit> {
        val mb = 1024L * 1024
        checkAll(ITERATIONS, Arb.long(0..200 * mb), Arb.long(0..200 * mb), Arb.long(0..200 * mb)) { a, b, ceiling ->
            val budgetA = OrbMemoryPolicy.budgetFor(a, ceiling)
            assertTrue(budgetA in OrbMemoryPolicy.BASE_BYTES..maxOf(OrbMemoryPolicy.BASE_BYTES, OrbMemoryPolicy.ABSOLUTE_MAX_BYTES))
            val (low, high) = minOf(a, b) to maxOf(a, b)
            assertTrue("encolheu ao pedir mais", OrbMemoryPolicy.budgetFor(low, ceiling) <= OrbMemoryPolicy.budgetFor(high, ceiling))
        }
    }

    /** Segurar o backspace acelera e nunca para: o intervalo só diminui, até o mínimo, e nunca abaixo dele. */
    @Test
    fun keyRepeatOnlyAcceleratesDownToTheMinimum() = runBlocking<Unit> {
        checkAll(ITERATIONS, Arb.int(0..1_000_000)) { tick ->
            val delay = RepeatSchedule.delayForTick(tick)
            assertTrue(delay >= RepeatSchedule.MIN_DELAY_MS)
            assertTrue(RepeatSchedule.delayForTick(tick + 1) <= delay)
        }
        assertEquals(RepeatSchedule.MIN_DELAY_MS, RepeatSchedule.delayForTick(Int.MAX_VALUE))
    }

    companion object {
        const val ITERATIONS = 3_000
    }
}
