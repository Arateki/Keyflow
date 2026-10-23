package com.keyflow.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdsControllerTest {
    private val context: Context = ContextWrapper(null)

    @Test
    fun `adsOff não pede consentimento nem inicializa o SDK`() {
        val runtime = FakeRuntime()
        val consent = FakeConsent()
        val controller = AdsController(context, runtime, consent)

        controller.initializeIfNeeded(Activity(), adsOff = true)

        assertThat(consent.requests).isEqualTo(0)
        assertThat(runtime.initializations).isEqualTo(0)
        assertThat(controller.initializationCount).isEqualTo(0)
        assertThat(controller.bannerView(context, adsOff = true)).isNull()
    }

    @Test
    fun `inicializa uma vez depois do consentimento`() {
        val runtime = FakeRuntime()
        val controller = AdsController(context, runtime, FakeConsent())

        controller.initializeIfNeeded(Activity(), adsOff = false)
        controller.initializeIfNeeded(Activity(), adsOff = false)

        assertThat(runtime.initializations).isEqualTo(1)
        assertThat(controller.initializationCount).isEqualTo(1)
        assertThat(controller.ready.value).isTrue()
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
