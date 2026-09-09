package com.keyflow.ui.tour

import androidx.compose.ui.geometry.Offset
import com.keyflow.core.ButtonState
import com.keyflow.ui.orb.OrbState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOUR-02/05: a interpolação keyframes→frame é função pura. Com ela testável fora do Compose, o
 * player (GT-T3) fica com uma responsabilidade só — desenhar — e o clock congelado rende sempre o
 * mesmo frame, que é o que torna os goldens possíveis.
 */
class TourEngineTest {

    private val script = GestureScript(
        id = "t",
        title = "t",
        description = "t",
        scene = TourScene.ORB,
        steps = listOf(
            GestureStep(
                at = Offset(0f, 0f),
                action = FingerAction.MOVE,
                durationMs = 100,
                reaction = SceneReaction(orbState = OrbState.Idle),
            ),
            GestureStep(
                at = Offset(1f, 0f),
                action = FingerAction.MOVE,
                durationMs = 200,
            ),
            GestureStep(
                at = Offset(1f, 1f),
                action = FingerAction.MULTI_TAP,
                durationMs = 100,
                reaction = SceneReaction(micState = ButtonState.RECORDING),
                tapCount = 3,
            ),
        ),
    )

    @Test
    fun totalDurationIsTheSumOfTheSteps() {
        assertEquals(400, script.totalDurationMs)
    }

    @Test
    fun atZeroTheFingerSitsOnTheFirstStep() {
        val frame = tourFrameAt(script, 0)

        assertEquals(0, frame.stepIndex)
        assertEquals(Offset(0f, 0f), frame.position)
        assertFalse(frame.finished)
    }

    @Test
    fun aMoveInterpolatesFromWhereTheFingerWas() {
        // Passo 2 vai de (0,0) até (1,0) em 200ms; na metade dele (t=100+100) o dedo está no meio.
        val frame = tourFrameAt(script, 200)

        assertEquals(1, frame.stepIndex)
        assertEquals(0.5f, frame.position.x, 0.001f)
        assertEquals(0f, frame.position.y, 0.001f)
    }

    @Test
    fun actionProgressRunsFromZeroToOneInsideTheStep() {
        assertEquals(0f, tourFrameAt(script, 100).actionProgress, 0.001f)
        assertEquals(0.5f, tourFrameAt(script, 200).actionProgress, 0.001f)
    }

    @Test
    fun aTapStaysPutInsteadOfSliding() {
        // O 3º passo é MULTI_TAP: o dedo já está no ponto e não deve "viajar" durante a rajada.
        val start = tourFrameAt(script, 300).position
        val middle = tourFrameAt(script, 350).position

        assertEquals(Offset(1f, 1f), start)
        assertEquals(start, middle)
    }

    @Test
    fun multiTapExposesTheConfiguredCount() {
        assertEquals(3, tourFrameAt(script, 350).tapCount)
    }

    @Test
    fun reactionCarriesOverFromEarlierSteps() {
        // O passo 2 não declara nada: o estado da orbe declarado no passo 1 continua valendo.
        val frame = tourFrameAt(script, 200)

        assertEquals(OrbState.Idle, frame.reaction.orbState)
    }

    @Test
    fun laterStepsOverrideEarlierReactions() {
        val frame = tourFrameAt(script, 350)

        assertEquals(OrbState.Idle, frame.reaction.orbState)
        assertEquals(ButtonState.RECORDING, frame.reaction.micState)
    }

    @Test
    fun anExplicitNoneClearsTheOverlayHint() {
        // `null` significa "não mexe"; para APAGAR o banner de desfazer o passo declara NONE.
        val withHint = GestureScript(
            id = "h", title = "h", description = "h", scene = TourScene.ORB,
            steps = listOf(
                GestureStep(
                    at = Offset.Zero,
                    action = FingerAction.TAP,
                    durationMs = 100,
                    reaction = SceneReaction(overlayHint = TourOverlayHint.UNDO_BANNER),
                ),
                GestureStep(at = Offset.Zero, action = FingerAction.TAP, durationMs = 100),
                GestureStep(
                    at = Offset.Zero,
                    action = FingerAction.TAP,
                    durationMs = 100,
                    reaction = SceneReaction(overlayHint = TourOverlayHint.NONE),
                ),
            ),
        )

        assertEquals(TourOverlayHint.UNDO_BANNER, tourFrameAt(withHint, 50).reaction.overlayHint)
        assertEquals("passo sem declaração não apaga", TourOverlayHint.UNDO_BANNER, tourFrameAt(withHint, 150).reaction.overlayHint)
        assertNull(tourFrameAt(withHint, 250).reaction.overlayHint)
    }

    @Test
    fun dragWalksThePathAndLeavesATrailBehind() {
        val drag = GestureScript(
            id = "d", title = "d", description = "d", scene = TourScene.ORB,
            steps = listOf(
                GestureStep(
                    at = Offset(1f, 1f),
                    action = FingerAction.DRAG,
                    durationMs = 100,
                    dragPath = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f)),
                ),
            ),
        )

        val quarter = tourFrameAt(drag, 25)
        assertEquals("no 1º quarto ainda está na primeira perna", 0.5f, quarter.position.x, 0.001f)
        assertEquals(0f, quarter.position.y, 0.001f)

        val threeQuarters = tourFrameAt(drag, 75)
        assertEquals(1f, threeQuarters.position.x, 0.001f)
        assertEquals(0.5f, threeQuarters.position.y, 0.001f)

        assertTrue("a trilha deveria crescer com o arrasto", threeQuarters.trail.size > quarter.trail.size)
        assertEquals("a trilha começa onde o arrasto começou", Offset(0f, 0f), threeQuarters.trail.first())
        assertEquals("e termina onde o dedo está", threeQuarters.position, threeQuarters.trail.last())
    }

    @Test
    fun aScriptWithNoDragPathStillMovesToTheTargetPoint() {
        val drag = GestureScript(
            id = "d2", title = "d", description = "d", scene = TourScene.ORB,
            steps = listOf(
                GestureStep(at = Offset(0f, 0f), action = FingerAction.MOVE, durationMs = 100),
                GestureStep(at = Offset(1f, 1f), action = FingerAction.DRAG, durationMs = 100),
            ),
        )

        assertEquals(0.5f, tourFrameAt(drag, 150).position.x, 0.001f)
    }

    @Test
    fun pastTheEndItClampsToTheLastFrameAndSaysItFinished() {
        val frame = tourFrameAt(script, 10_000)

        assertTrue(frame.finished)
        assertEquals(script.steps.lastIndex, frame.stepIndex)
        assertEquals(Offset(1f, 1f), frame.position)
        assertEquals("o loop é decisão do player, o frame só avisa", 1f, frame.actionProgress, 0.001f)
    }

    @Test
    fun negativeTimeIsTreatedAsTheStart() {
        assertEquals(tourFrameAt(script, 0), tourFrameAt(script, -500))
    }

    @Test
    fun anEmptyScriptDoesNotBlowUp() {
        val empty = GestureScript("e", "e", "e", TourScene.ORB, emptyList())

        val frame = tourFrameAt(empty, 0)

        assertTrue(frame.finished)
        assertEquals(Offset.Zero, frame.position)
    }
}
