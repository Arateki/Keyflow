package com.keyflow.ui.tour

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOUR-04 (revisto em 2026-09-09): o gesto corrente REPETE sozinho e só a seta troca de gesto — o
 * autoplay que avançava a lista dava pouco tempo para entender cada um (decisão do Yan usando no A32).
 * O índice/título/descrição seguem expostos para o consumidor posicionar o texto onde quiser.
 *
 * A lógica mora fora do Composable de propósito — é o que permite testar a passagem de tempo sem
 * device, com o relógio na mão.
 */
class GestureTourStateTest {

    private fun script(id: String, durationMs: Int) = GestureScript(
        id = id,
        title = "título $id",
        description = "descrição $id",
        scene = TourScene.ORB,
        steps = listOf(GestureStep(Offset.Zero, FingerAction.TAP, durationMs)),
    )

    private val scripts = listOf(script("a", 1000), script("b", 2000), script("c", 1000))

    private fun state(pauseMs: Int = 900) = GestureTourState(scripts, loopPauseMs = pauseMs)

    @Test
    fun startsOnTheFirstScript() {
        val s = state()

        assertEquals(0, s.index)
        assertEquals("título a", s.title)
        assertEquals("descrição a", s.description)
    }

    @Test
    fun theCurrentGestureRepeatsInsteadOfMovingOn() {
        val s = state()

        s.tick(0f)
        s.tick(5_000f)

        assertEquals("o tour não troca de gesto sozinho", 0, s.index)
        assertEquals("título a", s.title)
    }

    @Test
    fun theRepeatWaitsABeatBeforeStartingOver() {
        val s = state(pauseMs = 500)

        s.tick(0f)
        // "a" dura 1000ms: logo depois disso o gesto está terminado, mas ainda não recomeçou.
        assertTrue(s.tick(1200f).finished)
        // Passado o respiro, recomeça do zero.
        val restarted = s.tick(1600f)
        assertFalse(restarted.finished)
        assertEquals(0f, restarted.actionProgress, 0.001f)
    }

    @Test
    fun timePassingNeverChangesTheGestureOnItsOwn() {
        val s = state()

        repeat(20) { i -> s.tick(i * 1_000f) }

        assertEquals(0, s.index)
    }

    @Test
    fun nextAndPrevWalkTheListInBothDirections() {
        val s = state()
        s.tick(0f)

        s.next()
        assertEquals(1, s.index)

        s.prev()
        assertEquals(0, s.index)

        s.prev()
        assertEquals("voltar do primeiro dá a volta", 2, s.index)
    }

    @Test
    fun theArrowIsTheOnlyWayToChangeGesture() {
        val s = state()
        s.tick(0f)

        s.tick(9_000f)
        assertEquals("tempo sozinho não troca", 0, s.index)

        s.next()
        assertEquals(1, s.index)
    }

    @Test
    fun theScriptRestartsFromZeroAfterAnArrow() {
        val s = state()
        s.tick(500f)

        s.next()
        val frame = s.tick(500f)

        assertEquals("o roteiro novo começa do zero, não no meio", 0, frame.stepIndex)
        assertEquals(0f, frame.actionProgress, 0.001f)
        assertFalse(frame.finished)
    }

    @Test
    fun tappingTheArrowManyTimesInARowDoesNotGetStuck() {
        val s = state()
        s.tick(0f)

        repeat(7) { s.next() }

        assertEquals("7 avanços em 3 roteiros = 7 % 3", 1, s.index)
        assertTrue("e continua respondendo", s.tick(0f).stepIndex >= 0)
    }

    @Test
    fun anEmptyListDoesNotBlowUp() {
        val s = GestureTourState(emptyList())

        assertEquals("", s.title)
        assertEquals("", s.description)
        s.next()
        s.prev()
        assertTrue(s.tick(1000f).finished)
    }

    @Test
    fun aSingleScriptKeepsRepeatingWithoutBlowingUp() {
        val s = GestureTourState(listOf(script("solo", 500)))

        s.tick(0f)
        s.tick(5_000f)

        assertEquals(0, s.index)
        assertEquals("título solo", s.title)
    }

    @Test
    fun aFrozenClockKeepsGivingTheSameFrame() {
        val s = state()

        val first = s.tick(400f)
        val again = s.tick(400f)

        assertEquals(first, again)
    }

    @Test
    fun theDescriptionFollowsTheCurrentScript() {
        val s = state()
        s.tick(0f)

        s.next()

        assertEquals("descrição b", s.description)
    }
}
