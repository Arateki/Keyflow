package com.keyflow.ui.config

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.data.Settings
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.orb.LocalOrbClock
import com.keyflow.ui.tour.LocalTourClock
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes da aba Modo (T11 / CFG-06; chip redesenhado na Fase 2 de gestos): o chip de permissão é
 * sempre visível (rótulo CONCEDIDO/PENDENTE) e também é o botão de ativar; os toggles de modo
 * ligam/desligam (auto-save via callback).
 */
@RunWith(AndroidJUnit4::class)
class ModoPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private fun perms(
        mic: Boolean = true,
        overlay: Boolean = true,
        keyboard: Boolean = true,
        notifications: Boolean = true,
    ) = PermissionStatus(
        microphoneGranted = mic,
        overlayGranted = overlay,
        keyboardEnabled = keyboard,
        notificationsGranted = notifications,
    )

    @Test
    fun showsActivateWhenKeyboardPermissionPending() {
        var activated = false
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalOrbClock provides { 0f }, LocalTourClock provides { 0f }) {
                    ModoPanel(
                        settings = Settings(keyboardModeEnabled = true),
                        permissions = perms(keyboard = false),
                        onOverlayModeChange = {},
                        onKeyboardModeChange = {},
                        onRequestOverlayPermission = {},
                        onRequestKeyboardPermission = { activated = true },
                        onRequestMicPermission = {},
                        onRequestNotificationPermission = {},
                        onSwitchKeyboard = {},
                    )
                }
            }
        }

        rule.onNodeWithTag(ACTIVATE_KEYBOARD_TAG).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(true, activated) }
    }

    @Test
    fun chipShowsGrantedStateForOverlayPermission() {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalOrbClock provides { 0f }, LocalTourClock provides { 0f }) {
                    ModoPanel(
                        settings = Settings(),
                        permissions = perms(overlay = true),
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

        // O chip continua presente (também é o botão de ativar), mas mostra CONCEDIDO.
        rule.onNodeWithTag(ACTIVATE_OVERLAY_TAG).assertIsDisplayed().assertTextContains("concedido", substring = true, ignoreCase = true)
    }

    @Test
    fun showsActivateWhenNotificationPermissionPending() {
        var activated = false
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalOrbClock provides { 0f }, LocalTourClock provides { 0f }) {
                    ModoPanel(
                        settings = Settings(),
                        permissions = perms(notifications = false),
                        onOverlayModeChange = {},
                        onKeyboardModeChange = {},
                        onRequestOverlayPermission = {},
                        onRequestKeyboardPermission = {},
                        onRequestMicPermission = {},
                        onRequestNotificationPermission = { activated = true },
                        onSwitchKeyboard = {},
                    )
                }
            }
        }

        rule.onNodeWithTag(ACTIVATE_NOTIFICATION_TAG).assertIsDisplayed()
            .assertTextContains("pendente", substring = true, ignoreCase = true)
            .performClick()
        rule.runOnIdle { assertEquals(true, activated) }
    }

    /**
     * O microfone nunca bloqueia o Modo Sobrepor (a orbe segue útil sem gravar) — com o modo já ligado
     * e o mic pendente, o chip continua mostrando PENDENTE (pulsando, ver [StatusChip.pulsing]) em vez
     * de travar o card ou esconder o aviso (Yan, 2026-07-04).
     */
    @Test
    fun micChipStaysPendingWithoutBlockingWhenOverlayModeIsOn() {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalOrbClock provides { 0f }, LocalTourClock provides { 0f }) {
                    ModoPanel(
                        settings = Settings(overlayModeEnabled = true),
                        permissions = perms(mic = false),
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

        // O chip de mic é compartilhado pelos dois cards (Sobrepor e Teclado) — o 1º é o do Sobrepor.
        rule.onAllNodesWithTag(ACTIVATE_MIC_TAG)[0].assertIsDisplayed()
            .assertTextContains("pendente", substring = true, ignoreCase = true)
        // Concedido/pendente da orbe seguem OK: mic não é o que trava o modo.
        rule.onNodeWithTag(ACTIVATE_OVERLAY_TAG).assertTextContains("concedido", substring = true, ignoreCase = true)
    }

    @Test
    fun modeTogglesInvokeCallbacks() {
        var overlayChange: Boolean? = null
        var keyboardChange: Boolean? = null
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalOrbClock provides { 0f }, LocalTourClock provides { 0f }) {
                    ModoPanel(
                        settings = Settings(),
                        permissions = perms(),
                        onOverlayModeChange = { overlayChange = it },
                        onKeyboardModeChange = { keyboardChange = it },
                        onRequestOverlayPermission = {},
                        onRequestKeyboardPermission = {},
                        onRequestMicPermission = {},
                        onRequestNotificationPermission = {},
                        onSwitchKeyboard = {},
                    )
                }
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
