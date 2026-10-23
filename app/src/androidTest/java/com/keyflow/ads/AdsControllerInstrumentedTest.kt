package com.keyflow.ads

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdsControllerInstrumentedTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun adsOffDoesNotInitializeAndRecoversBannerSpace() {
        val runtime = FakeRuntime()
        val consent = FakeConsent()
        val controller = AdsController(ApplicationProvider.getApplicationContext(), runtime, consent)

        rule.setContent { BannerSlot(controller, adsOff = true) }
        rule.onNodeWithTag(BANNER_SLOT_TAG).assertDoesNotExist()
        controller.initializeIfNeeded(activity = null, adsOff = true)

        assertEquals(0, consent.requests)
        assertEquals(0, runtime.initializations)
    }

    @Test
    fun enabledSlotReservesBannerHeight() {
        val controller = AdsController(
            ApplicationProvider.getApplicationContext(),
            FakeRuntime(),
            FakeConsent(),
        )

        rule.setContent { BannerSlot(controller, adsOff = false) }
        rule.onNodeWithTag(BANNER_SLOT_TAG).assertExists()
    }

    private class FakeConsent : ConsentRequester {
        var requests = 0
        override fun request(activity: Activity, onReady: () -> Unit) {
            requests++
            onReady()
        }
    }

    private class FakeRuntime : AdsRuntime {
        var initializations = 0
        override fun initialize(context: Context, onComplete: () -> Unit) {
            initializations++
            onComplete()
        }

        override fun createBanner(context: Context, adUnitId: String): View? = null
        override fun loadInterstitial(context: Context, adUnitId: String, onReady: () -> Unit, onFailed: () -> Unit) = Unit
        override fun showInterstitial(activity: Activity): Boolean = false
        override fun destroy(view: View) = Unit
    }
}
