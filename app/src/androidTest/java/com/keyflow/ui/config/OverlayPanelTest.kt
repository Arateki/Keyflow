package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes da aba Overlay (T14 / OVL-05): posição, tamanho e modo de gravação persistem.
 */
@RunWith(AndroidJUnit4::class)
class OverlayPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private inner class Harness(initial: Settings) {
        var settings by mutableStateOf(initial)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                OverlayPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    onUnlockSkins = {},
                )
            }
        }
    }

    @Test
    fun positionPersists() {
        val h = Harness(Settings(overlaySide = OverlaySide.RIGHT))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(overlaySideTag(OverlaySide.LEFT)).performClick()

        rule.runOnIdle { assertEquals(OverlaySide.LEFT, h.settings.overlaySide) }
    }

    @Test
    fun sizePersists() {
        val h = Harness(Settings(orbSizePx = 150))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(ORB_SIZE_SLIDER_TAG)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(220f) }

        rule.runOnIdle { assertEquals(220, h.settings.orbSizePx) }
    }

    @Test
    fun recordingModePersists() {
        val h = Harness(Settings(overlayRecordingMode = RecordingMode.TOGGLE))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(overlayModeTag(RecordingMode.PUSH_TO_TALK)).performClick()

        rule.runOnIdle { assertEquals(RecordingMode.PUSH_TO_TALK, h.settings.overlayRecordingMode) }
    }

    @Test
    fun orbSkinPersistsAndLeavesTheKeyboardSkinAlone() {
        // A skin da orbe é escolha própria da aba Sobrepor (Yan, 2026-09-29): não depende da do teclado.
        val h = Harness(Settings(keyboardSkin = "system", orbSkin = "atelie"))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(orbSkinTag("aero")).performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals("aero", h.settings.orbSkin)
            assertEquals("system", h.settings.keyboardSkin)
        }
    }
}
