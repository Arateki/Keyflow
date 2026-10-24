package com.keyflow.ui.onboarding

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.legal.LegalUrls
import com.keyflow.ui.PermissionStatus
import com.keyflow.ui.orb.LocalOrbClock
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── GT-T10: "?" por permissão (PERM-01..04) ────────────────────────────────

    @Test
    fun everyPermissionHasAWhyButton() {
        rule.setContent { Screen(perms()) }
        OnboardingPermission.entries.forEach { rule.onNodeWithTag(onboardingWhyTag(it)).assertIsDisplayed() }
        OnboardingPermission.entries.forEach { rule.onNodeWithTag(onboardingWhyTextTag(it)).assertDoesNotExist() }
    }

    /** PERM-02: abre sob a linha; o "?" de outra linha troca; o mesmo "?" fecha. Uma aberta por vez. */
    @Test
    fun whyOpensSwapsAndClosesOneAtATime() {
        rule.setContent { Screen(perms()) }
        val mic = OnboardingPermission.MIC
        val overlay = OnboardingPermission.OVERLAY

        rule.onNodeWithTag(onboardingWhyTag(mic)).performClick()
        rule.onNodeWithTag(onboardingWhyTextTag(mic)).assertIsDisplayed()

        rule.onNodeWithTag(onboardingWhyTag(overlay)).performClick()
        rule.onNodeWithTag(onboardingWhyTextTag(mic)).assertDoesNotExist()
        rule.onNodeWithTag(onboardingWhyTextTag(overlay)).assertIsDisplayed()

        rule.onNodeWithTag(onboardingWhyTag(overlay)).performClick()
        OnboardingPermission.entries.forEach { rule.onNodeWithTag(onboardingWhyTextTag(it)).assertDoesNotExist() }
    }

    /** O "?" tem alvo próprio: tocar nele explica, não manda a pessoa para a tela do sistema. */
    @Test
    fun whyDoesNotRequestThePermission() {
        var requested = false
        rule.setContent { Screen(perms(mic = false), onRequestMic = { requested = true }) }

        rule.onNodeWithTag(onboardingWhyTag(OnboardingPermission.MIC)).performClick()

        rule.runOnIdle { assertFalse(requested) }
    }

    /** Edge case do spec: concedeu e voltou com a explicação aberta — o check muda, a explicação fica, uma só. */
    @Test
    fun statusChangeKeepsTheOpenExplanationWithoutDuplicating() {
        val status = mutableStateOf(perms(mic = false))
        rule.setContent { Screen(status.value) }

        rule.onNodeWithTag(onboardingWhyTag(OnboardingPermission.MIC)).performClick()
        rule.onAllNodesWithText("FALTA").assertCountEquals(1)

        status.value = perms(mic = true)

        rule.onAllNodesWithText("FALTA").assertCountEquals(0)
        rule.onAllNodesWithTag(onboardingWhyTextTag(OnboardingPermission.MIC)).assertCountEquals(1)
    }

    /** PERM-04: TalkBack anuncia o destino do "?". */
    @Test
    fun whyButtonAnnouncesWhatItExplains() {
        rule.setContent { Screen(perms()) }
        rule.onNodeWithContentDescription("Por que pedimos Microfone").assertIsDisplayed()
        rule.onNodeWithContentDescription("Por que pedimos Notificações").assertIsDisplayed()
    }

    @Test
    fun disclosureDeclineExplainsAndOffersLaterAcceptance() {
        val declined = mutableStateOf(false)
        var accepted = false
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ProminentDisclosureScreen(declined = declined.value, onAccept = { accepted = true }, onDecline = { declined.value = true })
            }
        }
        rule.onNodeWithTag("disclosure-copy").assertIsDisplayed()
        rule.onNodeWithTag(DISCLOSURE_DECLINE_TAG).performClick()
        rule.onNodeWithTag(DISCLOSURE_DECLINED_EXPLANATION_TAG).assertIsDisplayed()
        rule.onNodeWithTag(DISCLOSURE_ACCEPT_TAG).performClick()
        rule.runOnIdle { assertTrue(accepted) }
    }

    /** V1-6 / V1-5: link "Ler a política de privacidade completa" abre a URL no navegador sem rede. */
    @Test
    fun disclosurePrivacyLinkOpensPrivacyPolicyUrl() {
        val opened = mutableListOf<Intent>()
        rule.setContent {
            KeyFlowTheme(dark = false) {
                ProminentDisclosureScreen(
                    declined = false,
                    onAccept = {},
                    onDecline = {},
                    openUrl = { opened += it },
                )
            }
        }
        rule.onNodeWithTag("disclosure-privacy-link").assertIsDisplayed().performClick()
        assertEquals(1, opened.size)
        val intent = opened.single()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(LegalUrls.privacyPolicy(), intent.dataString)
    }
}
