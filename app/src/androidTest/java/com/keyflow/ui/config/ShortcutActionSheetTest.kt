package com.keyflow.ui.config

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.OrbShortcutAction
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** MODE-01: a Visão aparece entre as ações de atalho da orbe e, escolhida, é entregue como as demais. */
@RunWith(AndroidJUnit4::class)
class ShortcutActionSheetTest {

    @get:Rule
    val rule = createComposeRule()

    private val selected = mutableListOf<OrbShortcutAction>()
    private var dismissed = 0
    private var blockedKeyboard = 0

    private fun show(current: OrbShortcutAction = OrbShortcutAction.NONE, keyboardAvailable: Boolean = false) {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ShortcutActionSheet(
                    taps = 2,
                    current = current,
                    keyboardAvailable = keyboardAvailable,
                    onSelect = { selected += it },
                    onBlockedKeyboard = { blockedKeyboard++ },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    @Test
    fun everyActionHasARowIncludingVision() {
        show()

        for (action in OrbShortcutAction.entries) {
            rule.onNodeWithTag("shortcut-action-${action.name.lowercase()}").assertIsDisplayed()
        }
        rule.onNodeWithTag("shortcut-action-vision_mode").assertTextContains("Visão", substring = true)
    }

    @Test
    fun pickingVisionSelectsItAndClosesEvenWithoutTheKeyboardActive() {
        show(keyboardAvailable = false)

        rule.onNodeWithTag("shortcut-action-vision_mode").performClick()

        rule.runOnIdle {
            assertEquals(listOf(OrbShortcutAction.VISION_MODE), selected)
            assertEquals(0, blockedKeyboard)
            assertEquals(1, dismissed)
        }
    }
}
