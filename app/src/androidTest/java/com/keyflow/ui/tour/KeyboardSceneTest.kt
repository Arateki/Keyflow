package com.keyflow.ui.tour

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.ButtonState
import com.keyflow.ime.KeyFlowKeyboardView
import com.keyflow.ui.theme.KeyFlowTheme
import com.keyflow.ui.tour.scenes.KeyboardScene
import com.keyflow.ui.tour.scenes.TOUR_HINT_MIC_SELECTOR_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TOUR-03 (teclado): a cena é o `KeyFlowKeyboardView` REAL em miniatura, dirigido pelo passo. Alcançar a
 * view pela árvore da Activity, em vez de expor um gancho de teste na cena, mantém a produção limpa.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardSceneTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var current by mutableStateOf<TourFrame?>(null)

    private fun show(frame: TourFrame) {
        current = frame
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalTourClock provides { 1234f }) {
                    KeyboardScene(frame = current!!, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun update(frame: TourFrame) {
        rule.runOnUiThread { current = frame }
        rule.waitForIdle()
    }

    private fun frame(reaction: SceneReaction) = TourFrame(
        stepIndex = 0,
        position = Offset(0.11f, 0.14f),
        action = FingerAction.TAP,
        actionProgress = 0.5f,
        tapCount = 1,
        reaction = reaction,
        trail = emptyList(),
        finished = false,
    )

    /** Acha a view real na árvore da Activity — sem gancho de teste na cena. */
    private fun keyboard(): KeyFlowKeyboardView {
        lateinit var found: KeyFlowKeyboardView
        rule.runOnUiThread {
            found = requireNotNull(findKeyboard(rule.activity.window.decorView)) {
                "KeyFlowKeyboardView não está na cena"
            }
        }
        return found
    }

    private fun findKeyboard(view: View): KeyFlowKeyboardView? = when {
        view is KeyFlowKeyboardView -> view
        view is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { findKeyboard(view.getChildAt(it)) }
        else -> null
    }

    @Test
    fun theSceneShowsTheRealCanvasKeyboard() {
        show(frame(SceneReaction(keyboardExpanded = true)))

        assertNotNull("a grade Canvas deveria estar na tela", keyboard().canvasGridForTest)
    }

    @Test
    fun theMiniatureKeyboardDoesNotReserveTheOrbStrip() {
        show(frame(SceneReaction(keyboardExpanded = true)))

        val view = keyboard()
        assertEquals("a prévia não reserva a faixa da orbe", 0, view.faixaDaOrbePx)
    }

    @Test
    fun theMicFollowsTheStateTheStepDeclared() {
        show(frame(SceneReaction(micState = ButtonState.RECORDING)))

        val label = keyboard().micLabelForTest
        assertTrue("gravando, o mic mostra o tempo, veio: $label", label?.startsWith("●") == true)
    }

    @Test
    fun theMicGoesBackToTheGlyphWhenTheStepSaysIdle() {
        show(frame(SceneReaction(micState = ButtonState.RECORDING)))

        update(frame(SceneReaction(micState = ButtonState.IDLE_READY)))

        assertEquals("🎙", keyboard().micLabelForTest)
    }

    @Test
    fun theKeyboardCollapsesAndExpandsWithTheStep() {
        show(frame(SceneReaction(keyboardExpanded = true)))
        assertTrue(keyboard().expandedForTest)

        update(frame(SceneReaction(keyboardExpanded = false)))
        assertTrue("deveria ter retraído", !keyboard().expandedForTest)

        update(frame(SceneReaction(keyboardExpanded = true)))
        assertTrue("e voltado", keyboard().expandedForTest)
    }

    @Test
    fun theMicSelectorHintOnlyShowsWhenTheStepDeclaresIt() {
        show(frame(SceneReaction(micState = ButtonState.RECORDING)))
        rule.onNodeWithTag(TOUR_HINT_MIC_SELECTOR_TAG).assertDoesNotExist()

        update(
            frame(
                SceneReaction(
                    micState = ButtonState.RECORDING,
                    overlayHint = TourOverlayHint.MIC_ACTION_SELECTOR,
                ),
            ),
        )
        rule.onNodeWithTag(TOUR_HINT_MIC_SELECTOR_TAG).assertIsDisplayed()
    }
}
