package com.keyflow.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.config.panelTag
import com.keyflow.ui.onboarding.DISCLOSURE_CONTINUE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_DECLINED_EXPLANATION_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_DECLINE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import com.keyflow.core.KeyFlowController

/** Uma divulgação atualizada deve dar a mesma chance de ler a explicação numa instalação já concluída. */
@RunWith(AndroidJUnit4::class)
class DisclosureUpdateRoutingTest {
    private val compose = createAndroidComposeRule<SetupActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(SeedOnboardingRule(done = true, acceptedVersion = DISCLOSURE_VERSION - 1))
        .around(compose)

    @After
    fun tearDown() {
        KeyFlowController.clearInserter()
    }

    @Test
    fun updateDeclineShowsExplanationBeforeContinuingWithoutDictation() {
        compose.mainClock.autoAdvance = false
        var frames = 0
        while (compose.onAllNodesWithTag(DISCLOSURE_DECLINE_TAG).fetchSemanticsNodes().isEmpty() && frames++ < 100) {
            compose.mainClock.advanceTimeBy(100)
            android.os.SystemClock.sleep(20)
        }

        compose.onNodeWithTag(DISCLOSURE_DECLINE_TAG).performClick()
        compose.mainClock.advanceTimeBy(100)

        compose.onNodeWithTag(DISCLOSURE_DECLINED_EXPLANATION_TAG).assertExists()
        compose.onNodeWithTag(DISCLOSURE_CONTINUE_TAG).performClick()

        frames = 0
        while (compose.onAllNodesWithTag(panelTag(ConfigTab.OVERLAY)).fetchSemanticsNodes().isEmpty() && frames++ < 100) {
            compose.mainClock.advanceTimeBy(100)
            android.os.SystemClock.sleep(20)
        }
        compose.onNodeWithTag(panelTag(ConfigTab.OVERLAY)).assertExists()
    }
}
