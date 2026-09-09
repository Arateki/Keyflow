package com.keyflow.ui.tour

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TOUR-01/04/05 no Compose de verdade: autoplay em loop, setas que interrompem, estado exposto ao
 * consumidor e frame estável sob clock congelado.
 *
 * O relógio é dirigido pelo teste via [LocalTourClock] — é exatamente o mecanismo que a galeria usa
 * para congelar o tempo, então testar por aqui também prova o caminho dos goldens.
 */
@RunWith(AndroidJUnit4::class)
class GestureTourPlayerTest {

    @get:Rule
    val rule = createComposeRule()

    private fun script(id: String, durationMs: Int) = GestureScript(
        id = id,
        title = "título $id",
        description = "descrição $id",
        scene = TourScene.ORB,
        steps = listOf(GestureStep(Offset(0.5f, 0.5f), FingerAction.TAP, durationMs)),
    )

    private val scripts = listOf(script("a", 1000), script("b", 1000), script("c", 1000))

    private var now by mutableFloatStateOf(0f)
    private lateinit var state: GestureTourState

    /** Monta o player como um consumidor real: texto fora, cena plugada, setas por perto. */
    private fun content() {
        now = 0f
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalTourClock provides { now }) {
                    val s = rememberGestureTourState(scripts)
                    state = s
                    Column {
                        Text(s.title, Modifier.testTag(TITLE))
                        Text(s.description, Modifier.testTag(DESC))
                        GestureTourPlayer(
                            scripts = scripts,
                            state = s,
                            modifier = Modifier.size(200.dp).testTag(PLAYER),
                        ) { frame ->
                            // Cena fake: só marca qual passo está vigente (as reais chegam na T4/T5).
                            Box(Modifier.size(200.dp).testTag("step-${frame.stepIndex}"))
                        }
                        Text("‹", Modifier.testTag(PREV).clickable { s.prev() })
                        Text("›", Modifier.testTag(NEXT).clickable { s.next() })
                    }
                }
            }
        }
    }

    private fun advanceTo(ms: Float) {
        rule.runOnUiThread { now = ms }
        rule.waitForIdle()
    }

    @Test
    fun autoplayWalksTheListInOrderAndLoopsBack() {
        content()
        assertEquals("título a", state.title)

        advanceTo(1000f)
        assertEquals("título b", state.title)

        advanceTo(2000f)
        assertEquals("título c", state.title)

        advanceTo(3000f)
        assertEquals("voltou pro começo", "título a", state.title)
    }

    @Test
    fun theExposedTextIsWhatTheConsumerRenders() {
        content()

        advanceTo(1000f)

        rule.onNodeWithTag(TITLE).assertTextEquals("título b")
        rule.onNodeWithTag(DESC).assertTextEquals("descrição b")
    }

    @Test
    fun theArrowsNavigateAndHoldTheAutoplay() {
        content()

        rule.onNodeWithTag(NEXT).performClick()
        rule.waitForIdle()
        assertEquals("título b", state.title)

        // Sem a pausa, 1000ms depois o autoplay já teria pulado para o "c".
        advanceTo(1500f)
        assertEquals("o autoplay deveria estar segurado", "título b", state.title)
    }

    @Test
    fun goingBackFromTheFirstWrapsToTheLast() {
        content()

        rule.onNodeWithTag(PREV).performClick()
        rule.waitForIdle()

        assertEquals("título c", state.title)
    }

    @Test
    fun hammeringTheArrowDoesNotLeaveThePlayerStuck() {
        content()

        repeat(5) {
            rule.onNodeWithTag(NEXT).performClick()
            rule.waitForIdle()
        }

        assertEquals("5 avanços em 3 roteiros", "título c", state.title)
        // E o roteiro corrente continua tocando do começo, não preso no fim.
        rule.onNodeWithTag("step-0").assertExists()
    }

    @Test
    fun aFrozenClockRendersTheSameFrameEveryTime() {
        content()

        advanceTo(400f)
        rule.onNodeWithTag("step-0").assertExists()

        // Recompor sem mexer no relógio não pode mudar o que está na tela (é o que os goldens exigem).
        rule.runOnUiThread { now = 400f }
        rule.waitForIdle()
        rule.onNodeWithTag("step-0").assertExists()
    }

    @Test
    fun thePlayerAnnouncesTheCurrentGestureToTalkBack() {
        content()

        rule.onNodeWithTag(PLAYER).assertContentDescriptionEquals("título a. descrição a")
    }

    private companion object {
        const val TITLE = "tour-title"
        const val DESC = "tour-desc"
        const val PLAYER = "tour-player"
        const val PREV = "tour-prev"
        const val NEXT = "tour-next"
    }
}
