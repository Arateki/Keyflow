package com.keyflow.ui.config

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes da aba Modo (T11 / CFG-06): "Ativar" só aparece quando a permissão está pendente e dispara
 * o callback; os toggles de modo ligam/desligam (auto-save via callback).
 */
@RunWith(AndroidJUnit4::class)
class ModoPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private fun perms(
        mic: Boolean = true,
        overlay: Boolean = true,
        keyboard: Boolean = true,
    ) = PermissionStatus(
        microphoneGranted = mic,
        overlayGranted = overlay,
        keyboardEnabled = keyboard,
        notificationsGranted = true,
    )

    @Test
    fun showsActivateWhenKeyboardPermissionPending() {
        var activated = false
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ModoPanel(
                    settings = Settings(keyboardModeEnabled = true),
                    permissions = perms(keyboard = false),
                    onOverlayModeChange = {},
                    onKeyboardModeChange = {},
                    onRequestOverlayPermission = {},
                    onRequestKeyboardPermission = { activated = true },
                )
            }
        }

        rule.onNodeWithTag(ACTIVATE_KEYBOARD_TAG).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(true, activated) }
    }

    @Test
    fun hidesActivateWhenOverlayPermissionGranted() {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ModoPanel(
                    settings = Settings(),
                    permissions = perms(overlay = true),
                    onOverlayModeChange = {},
                    onKeyboardModeChange = {},
                    onRequestOverlayPermission = {},
                    onRequestKeyboardPermission = {},
                )
            }
        }

        rule.onAllNodesWithTag(ACTIVATE_OVERLAY_TAG).assertCountEquals(0)
    }

    @Test
    fun modeTogglesInvokeCallbacks() {
        var overlayChange: Boolean? = null
        var keyboardChange: Boolean? = null
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ModoPanel(
                    settings = Settings(),
                    permissions = perms(),
                    onOverlayModeChange = { overlayChange = it },
                    onKeyboardModeChange = { keyboardChange = it },
                    onRequestOverlayPermission = {},
                    onRequestKeyboardPermission = {},
                )
            }
        }

        rule.onNodeWithTag(TOGGLE_OVERLAY_MODE_TAG).performClick()
        rule.onNodeWithTag(TOGGLE_KEYBOARD_MODE_TAG).performClick()
        rule.runOnIdle {
            assertEquals(true, overlayChange)
            assertEquals(true, keyboardChange)
        }
    }
}
