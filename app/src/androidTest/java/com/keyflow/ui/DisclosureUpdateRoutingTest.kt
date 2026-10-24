package com.keyflow.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.ui.config.ConfigTab
import com.keyflow.ui.config.panelTag
import com.keyflow.ui.onboarding.DISCLOSURE_CONTINUE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_DECLINED_EXPLANATION_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_DECLINE_TAG
import com.keyflow.ui.onboarding.DISCLOSURE_VERSION
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Uma divulgação atualizada deve dar a mesma chance de ler a explicação numa instalação já concluída. */
@RunWith(AndroidJUnit4::class)
class DisclosureUpdateRoutingTest {
    private val compose = createAndroidComposeRule<SetupActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(SeedOnboardingRule(done = true, acceptedVersion = DISCLOSURE_VERSION - 1))
        .around(compose)

    @Test
    fun updateDeclineShowsExplanationBeforeContinuingWithoutDictation() {
        compose.onNodeWithTag(DISCLOSURE_DECLINE_TAG).performClick()

        compose.onNodeWithTag(DISCLOSURE_DECLINED_EXPLANATION_TAG).assertExists()
        compose.onNodeWithTag(DISCLOSURE_CONTINUE_TAG).performClick()

        compose.onNodeWithTag(panelTag(ConfigTab.VOZ)).assertExists()
    }
}
