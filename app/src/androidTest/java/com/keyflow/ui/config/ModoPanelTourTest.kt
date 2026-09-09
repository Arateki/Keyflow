package com.keyflow.ui.config

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.orb.LocalOrbClock
import com.keyflow.ui.theme.KeyFlowTheme
import com.keyflow.ui.tour.LocalTourClock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MODO-01/02/03 e SCR-07: os cards da aba Modo passam a mostrar o TOUR no lugar das demos paradas, com
 * as setas na barra PRÉVIA e a descrição sincronizada ao lado — e os roteiros seguem a config viva.
 *
 * O relógio do tour é congelado: aqui interessa a navegação e a seleção de textos, não a animação.
 */
@RunWith(AndroidJUnit4::class)
class ModoPanelTourTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(settings: Settings) {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(
                    LocalOrbClock provides { 0f },
                    LocalTourClock provides { 0f },
                ) {
                    ModoPanel(
                        settings = settings,
                        permissions = PermissionStatus(
                            microphoneGranted = true,
                            overlayGranted = true,
                            keyboardEnabled = true,
                            notificationsGranted = true,
                        ),
                        onOverlayModeChange = {},
                        onKeyboardModeChange = {},
                        onRequestOverlayPermission = {},
                        onRequestKeyboardPermission = {},
                        onRequestMicPermission = {},
                        onRequestNotificationPermission = {},
                        onSwitchKeyboard = {},
                    )
                }
            }
        }
    }

    @Test
    fun bothCardsPlayTheirOwnTour() {
        show(Settings())

        rule.onNodeWithTag(MODO_TOUR_OVERLAY_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(MODO_TOUR_KEYBOARD_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theOverlayCardStartsOnTheRecordingBasics() {
        show(Settings(overlayRecordingMode = RecordingMode.TOGGLE))

        rule.onNodeWithTag("$MODO_TOUR_OVERLAY_TAG-title").performScrollTo()
            .assertTextContains("Gravar e parar")
        rule.onNodeWithTag("$MODO_TOUR_OVERLAY_TAG-desc").performScrollTo()
            .assertTextContains("Toque para gravar ou parar.")
    }

    @Test
    fun theArrowMovesToTheNextGestureAndTheTextFollows() {
        show(Settings(overlayRecordingMode = RecordingMode.TOGGLE))

        rule.onNodeWithTag("$MODO_TOUR_OVERLAY_TAG-next").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("$MODO_TOUR_OVERLAY_TAG-title").assertTextContains("Pausar e retomar")
        rule.onNodeWithTag("$MODO_TOUR_OVERLAY_TAG-desc")
            .assertTextContains("Segure para pausar e retomar.")
    }

    @Test
    fun goingBackFromTheFirstGestureWrapsAround() {
        show(Settings(overlayRecordingMode = RecordingMode.TOGGLE, shortcut3Tap = OrbShortcutAction.SWITCH_IME))

        rule.onNodeWithTag("$MODO_TOUR_OVERLAY_TAG-prev").performScrollTo().performClick()
        rule.waitForIdle()

        // O último roteiro do TOGGLE com 3 toques mapeados é justamente o atalho de 3 toques.
        // O título traz a contagem junto ("3 toques · ..."), então aqui a checagem é por trecho.
        rule.onNodeWithTag("$MODO_TOUR_OVERLAY_TAG-title")
            .assertTextContains("Trocar teclado do sistema", substring = true)
    }

    @Test
    fun changingTheRecordingModeRegeneratesTheScripts() {
        show(Settings(overlayRecordingMode = RecordingMode.PUSH_TO_TALK))

        // No Push-to-talk o primeiro gesto deixa de ser "toque para gravar".
        rule.onNodeWithTag("$MODO_TOUR_OVERLAY_TAG-title").performScrollTo()
            .assertTextContains("Falar e enviar")
    }

    @Test
    fun theKeyboardCardStartsOnCollapseAndExpand() {
        show(Settings())

        rule.onNodeWithTag("$MODO_TOUR_KEYBOARD_TAG-title").performScrollTo()
            .assertTextContains("Retrair e expandir")
    }
}
