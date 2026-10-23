package com.keyflow.ui.onboarding

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisclosureConsentTest {
    @Test
    fun `first use is not accepted`() {
        assertThat(DisclosureConsent.fromStoredVersion(0).isAcceptedForCurrentVersion()).isFalse()
    }

    @Test
    fun `current disclosure version remains accepted`() {
        assertThat(DisclosureConsent.fromStoredVersion(DISCLOSURE_VERSION).isAcceptedForCurrentVersion()).isTrue()
    }

    @Test
    fun `older accepted version requires renewed consent`() {
        assertThat(DisclosureConsent.fromStoredVersion(DISCLOSURE_VERSION - 1).isAcceptedForCurrentVersion()).isFalse()
    }
}
