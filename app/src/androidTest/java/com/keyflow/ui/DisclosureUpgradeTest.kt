package com.keyflow.ui

import android.Manifest
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.keyflow.ui.config.tabTag
import com.keyflow.ui.config.PRIVACY_REVIEW_DISCLOSURE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_DECLINE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_CONTINUE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_DECLINED_EXPLANATION_TAG
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.keyflow.data.SettingsRepository
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.config.panelTag
import com.keyflow.ui.onboarding.DISCLOSURE_ACCEPT_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisclosureUpgradeTest {
    private val compose = createAndroidComposeRule<SetupActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(SeedOnboardingRule(done = true, acceptedVersion = DISCLOSURE_VERSION - 1))
        .around(GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO))
        .around(compose)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun updateRequiresTheCurrentDisclosureEvenWhenMicrophoneIsAlreadyGranted() {
        compose.mainClock.autoAdvance = false
        var frames = 0
        while (compose.onAllNodesWithTag(DISCLOSURE_ACCEPT_TAG).fetchSemanticsNodes().isEmpty() && frames++ < 100) {
            compose.mainClock.advanceTimeBy(100)
        }
        compose.onNodeWithTag(DISCLOSURE_ACCEPT_TAG).assertExists()
        compose.onNodeWithTag("disclosure-recording-gate-explanation").assertExists()

        compose.onNodeWithTag(DISCLOSURE_ACCEPT_TAG).performClick()
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        var accepted = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            accepted = runBlocking {
                SettingsRepository(context).settingsFlow().first().disclosureAcceptedVersion == DISCLOSURE_VERSION
            }
            if (accepted) break
            android.os.SystemClock.sleep(50)
        }
        assertTrue("o aceite da versão atual deveria ser persistido", accepted)
        var configFrames = 0
        while (compose.onAllNodesWithTag(panelTag(ConfigTab.VOZ)).fetchSemanticsNodes().isEmpty() && configFrames++ < 100) {
            compose.mainClock.advanceTimeBy(100)
        }
        compose.onNodeWithTag(panelTag(ConfigTab.VOZ)).assertExists()
    }
    @Test
    fun reverAvisoERecusarRevogaAceiteAtualComoNaAtualizacao() {
        compose.mainClock.autoAdvance = false
        fun espera(tag: String) {
            var frames = 0
            while (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() && frames++ < 100) {
                compose.mainClock.advanceTimeBy(100)
            }
            compose.onNodeWithTag(tag).assertExists()
        }
        espera(DISCLOSURE_ACCEPT_TAG)
        compose.onNodeWithTag(DISCLOSURE_ACCEPT_TAG).performClick()
        espera(tabTag(ConfigTab.PRIVACIDADE))
        compose.onNodeWithTag(tabTag(ConfigTab.PRIVACIDADE)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag(PRIVACY_REVIEW_DISCLOSURE_TAG).performScrollTo().performClick()
        espera(DISCLOSURE_ACCEPT_TAG)
        compose.onNodeWithTag(DISCLOSURE_ACCEPT_TAG).performClick()
        espera(panelTag(ConfigTab.PRIVACIDADE))
        compose.onNodeWithTag(PRIVACY_REVIEW_DISCLOSURE_TAG).performScrollTo().performClick()
        espera(DISCLOSURE_DECLINE_TAG)
        compose.onNodeWithTag(DISCLOSURE_DECLINE_TAG).performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag(DISCLOSURE_DECLINED_EXPLANATION_TAG).assertExists()
        compose.onNodeWithTag(DISCLOSURE_CONTINUE_TAG).performClick()
        espera(panelTag(ConfigTab.OVERLAY))
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        var revoked = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val saved = runBlocking { SettingsRepository(context).settingsFlow().first() }
            revoked = saved.disclosureDeclined && saved.disclosureAcceptedVersion == 0
            if (revoked) break
            android.os.SystemClock.sleep(50)
        }
        assertTrue("recusar novamente deve invalidar o aceite que libera o gravador", revoked)
    }

}
