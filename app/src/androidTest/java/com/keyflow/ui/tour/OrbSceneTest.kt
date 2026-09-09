package com.keyflow.ui.tour

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.theme.KeyFlowTheme
import com.keyflow.ui.tour.scenes.OrbScene
import com.keyflow.ui.tour.scenes.TOUR_HINT_CANCEL_TARGET_TAG
import com.keyflow.ui.tour.scenes.TOUR_HINT_UNDO_BANNER_TAG
import com.keyflow.ui.tour.scenes.tourOrbTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TOUR-03 (orbe): a cena é a superfície REAL do produto reagindo ao roteiro — `VoiceOrb` de verdade
 * dirigido pelo passo, não um desenho à parte que envelhece sozinho.
 */
@RunWith(AndroidJUnit4::class)
class OrbSceneTest {

    @get:Rule
    val rule = createComposeRule()

    private var current by mutableStateOf<TourFrame?>(null)

    /** Uma composição só por teste (limitação do Compose test) — o frame entra por estado. */
    private fun show(frame: TourFrame) {
        current = frame
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalTourClock provides { 1234f }) {
                    OrbScene(frame = current!!, modifier = Modifier.size(240.dp))
                }
            }
        }
    }

    private fun update(frame: TourFrame) {
        rule.runOnUiThread { current = frame }
        rule.waitForIdle()
    }

    private fun frame(
        position: Offset = Offset(0.5f, 0.45f),
        action: FingerAction = FingerAction.TAP,
        reaction: SceneReaction = SceneReaction(),
    ) = TourFrame(
        stepIndex = 0,
        position = position,
        action = action,
        actionProgress = 0.5f,
        tapCount = 1,
        reaction = reaction,
        trail = emptyList(),
        finished = false,
    )

    @Test
    fun theOrbShowsTheStateTheStepDeclared() {
        show(frame(reaction = SceneReaction(orbState = OrbState.Listening)))

        rule.onNodeWithTag(tourOrbTag(OrbState.Listening)).assertIsDisplayed()
    }

    @Test
    fun aStepWithoutAStateFallsBackToIdleInsteadOfDisappearing() {
        show(frame(reaction = SceneReaction()))

        rule.onNodeWithTag(tourOrbTag(OrbState.Idle)).assertIsDisplayed()
    }

    @Test
    fun theCancelTargetAppearsOnlyOnTheStepThatDeclaresIt() {
        show(frame(reaction = SceneReaction(orbState = OrbState.Listening)))
        rule.onNodeWithTag(TOUR_HINT_CANCEL_TARGET_TAG).assertDoesNotExist()

        update(
            frame(
                reaction = SceneReaction(
                    orbState = OrbState.Listening,
                    overlayHint = TourOverlayHint.CANCEL_TARGET,
                ),
            ),
        )
        rule.onNodeWithTag(TOUR_HINT_CANCEL_TARGET_TAG).assertIsDisplayed()
    }

    @Test
    fun theUndoBannerShowsWhenDeclared() {
        show(
            frame(
                reaction = SceneReaction(
                    orbState = OrbState.Idle,
                    overlayHint = TourOverlayHint.UNDO_BANNER,
                ),
            ),
        )

        rule.onNodeWithTag(TOUR_HINT_UNDO_BANNER_TAG).assertIsDisplayed()
    }

    @Test
    fun theOrbRidesAlongWithTheFingerWhileDragging() {
        // Arrastar a orbe move a orbe: se ela ficasse parada enquanto o dedo desce, o tour ensinaria
        // um gesto que não existe.
        show(frame(position = Offset(0.5f, 0.2f), action = FingerAction.DRAG))
        val top = rule.onNodeWithTag(tourOrbTag(OrbState.Idle)).getUnclippedBoundsInRoot().top

        update(frame(position = Offset(0.5f, 0.8f), action = FingerAction.DRAG))
        val bottom = rule.onNodeWithTag(tourOrbTag(OrbState.Idle)).getUnclippedBoundsInRoot().top

        assertTrue("a orbe deveria ter descido com o dedo ($top → $bottom)", bottom > top)
    }

    @Test
    fun aRestingPlaceDeclaredByTheStepWins() {
        show(
            frame(
                position = Offset(0.5f, 0.9f),
                action = FingerAction.RELEASE,
                reaction = SceneReaction(orbAt = Offset(0.5f, 0.1f)),
            ),
        )

        val bounds = rule.onNodeWithTag(tourOrbTag(OrbState.Idle)).getUnclippedBoundsInRoot()
        assertTrue(
            "soltando, a orbe fica onde o passo declarou, não onde o dedo parou (${bounds.top})",
            bounds.top.value < 120f,
        )
    }
}
