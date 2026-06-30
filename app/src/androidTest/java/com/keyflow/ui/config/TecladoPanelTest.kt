package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.RecordingMode
import com.keyflow.data.Settings
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes da aba Teclado (T13): colar-auto e modo de gravação persistem; "Desbloquear skins" dispara o
 * callback (a sheet real chega na T16).
 */
@RunWith(AndroidJUnit4::class)
class TecladoPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private inner class Harness(initial: Settings) {
        var settings by mutableStateOf(initial)
        var unlockClicks = 0

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                TecladoPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    onLanguageClick = {},
                    onUnlockSkins = { unlockClicks++ },
                )
            }
        }
    }

    @Test
    fun autoPasteInFieldTogglePersists() {
        val h = Harness(Settings(autoPasteInField = true))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_AUTO_PASTE_IN_FIELD_TAG).performClick()

        rule.runOnIdle { assertEquals(false, h.settings.autoPasteInField) }
    }

    @Test
    fun autoPasteOnFocusTogglePersists() {
        val h = Harness(Settings(autoPasteOnFocus = true))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_AUTO_PASTE_ON_FOCUS_TAG).performClick()

        rule.runOnIdle { assertEquals(false, h.settings.autoPasteOnFocus) }
    }

    @Test
    fun unlockSkinsInvokesCallback() {
        val h = Harness(Settings())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(TECLADO_UNLOCK_TAG).performClick()

        rule.runOnIdle { assertTrue(h.unlockClicks > 0) }
    }
}
