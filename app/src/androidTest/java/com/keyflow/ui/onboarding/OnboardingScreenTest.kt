package com.keyflow.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.orb.LocalOrbClock
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Testes do onboarding (T17 / ONB-01..03): checklist reflete permissões; CTAs disparam callbacks. */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun perms(
        mic: Boolean = true,
        overlay: Boolean = true,
        keyboard: Boolean = true,
        notif: Boolean = true,
    ) = PermissionStatus(mic, overlay, keyboard, notif)

    @Composable
    private fun Screen(
        permissions: PermissionStatus,
        onGrant: () -> Unit = {},
        onSkip: () -> Unit = {},
        onRequestMic: () -> Unit = {},
        onRequestOverlay: () -> Unit = {},
        onRequestKeyboard: () -> Unit = {},
        onRequestNotifications: () -> Unit = {},
    ) {
        // Congela o orbe (Listening) p/ a árvore ficar idle nos testes.
        KeyFlowTheme(dark = false) {
            CompositionLocalProvider(LocalOrbClock provides { 0f }) {
                OnboardingScreen(
                    permissions = permissions,
                    onGrant = onGrant,
                    onSkip = onSkip,
                    onRequestMic = onRequestMic,
                    onRequestOverlay = onRequestOverlay,
                    onRequestKeyboard = onRequestKeyboard,
                    onRequestNotifications = onRequestNotifications,
                )
            }
        }
    }

    @Test
    fun checklistReflectsPermissions() {
        rule.setContent { Screen(perms(keyboard = false)) }
        // Uma permissão pendente → exatamente um "FALTA".
        rule.onAllNodesWithText("FALTA").assertCountEquals(1)
    }

    @Test
    fun allGrantedHasNoMissing() {
        rule.setContent { Screen(perms()) }
        rule.onAllNodesWithText("FALTA").assertCountEquals(0)
    }

    @Test
    fun ctasInvokeCallbacks() {
        var granted = false
        var skipped = false
        rule.setContent { Screen(perms(), onGrant = { granted = true }, onSkip = { skipped = true }) }

        rule.onNodeWithTag(ONBOARDING_GRANT_TAG).performClick()
        rule.onNodeWithTag(ONBOARDING_SKIP_TAG).performClick()

        rule.runOnIdle {
            assertTrue(granted)
            assertTrue(skipped)
        }
    }

    /** Cada item do checklist pede/abre só a permissão dele (Yan, 2026-07-05: um a um). */
    @Test
    fun checklistItemsInvokeTheirOwnRequest() {
        var mic = false
        var overlay = false
        var keyboard = false
        var notifications = false
        rule.setContent {
            Screen(
                perms(),
                onRequestMic = { mic = true },
                onRequestOverlay = { overlay = true },
                onRequestKeyboard = { keyboard = true },
                onRequestNotifications = { notifications = true },
            )
        }

        rule.onNodeWithTag(ONBOARDING_ITEM_MIC_TAG).performClick()
        rule.onNodeWithTag(ONBOARDING_ITEM_OVERLAY_TAG).performClick()
        rule.onNodeWithTag(ONBOARDING_ITEM_KEYBOARD_TAG).performClick()
        rule.onNodeWithTag(ONBOARDING_ITEM_NOTIFICATIONS_TAG).performClick()

        rule.runOnIdle {
            assertTrue(mic)
            assertTrue(overlay)
            assertTrue(keyboard)
            assertTrue(notifications)
        }
    }
}
