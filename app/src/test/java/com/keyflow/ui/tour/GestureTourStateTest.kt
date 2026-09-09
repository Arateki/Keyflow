package com.keyflow.ui.tour

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOUR-04: autoplay em loop, setas que interrompem sem deixar estado preso, e o índice/título/descrição
 * expostos para o consumidor posicionar o texto onde quiser.
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

    private fun state(pauseMs: Int = 6_000) = GestureTourState(scripts, autoplayPauseMs = pauseMs)

    @Test
    fun startsOnTheFirstScript() {
        val s = state()

        assertEquals(0, s.index)
        assertEquals("título a", s.title)
        assertEquals("descrição a", s.description)
    }

    @Test
    fun autoplayAdvancesWhenTheScriptEnds() {
        val s = state()

        s.tick(0f)
        assertEquals(0, s.index)

        s.tick(1000f)
        assertEquals("terminou o 'a', deveria estar no 'b'", 1, s.index)
    }

    @Test
    fun eachScriptGetsItsOwnDurationNotTheFirstOnes() {
        val s = state()

        s.tick(0f)
        s.tick(1000f) // → b (dura 2000)
        s.tick(2000f)
        assertEquals("b ainda não acabou", 1, s.index)

        s.tick(3000f)
        assertEquals(2, s.index)
    }

    @Test
    fun autoplayLoopsBackToTheStart() {
        val s = state()

        s.tick(0f)
        s.tick(1000f) // b
        s.tick(3000f) // c
        s.tick(4000f) // volta pro a

        assertEquals(0, s.index)
        assertEquals("título a", s.title)
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
    fun usingTheArrowsPausesAutoplayForAWhile() {
        val s = state(pauseMs = 6_000)
        s.tick(0f)

        s.next() // vai pro b em t=0
        // b dura 2000ms: sem a pausa, o autoplay já teria avançado aqui.
        s.tick(2500f)
        assertEquals("o autoplay deveria estar segurado", 1, s.index)

        // Passada a janela, ele volta a andar sozinho.
        s.tick(7000f)
        assertEquals(2, s.index)
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
    fun aSingleScriptLoopsOnItself() {
        val s = GestureTourState(listOf(script("solo", 500)))

        s.tick(0f)
        s.tick(500f)

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
