package com.keyflow.ads

import android.app.Activity
import android.content.Context
import android.view.View
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.ConsentInformation
import com.google.android.ump.UserMessagingPlatform
import com.keyflow.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Porta fakeável: a UI não conhece classes do GMA SDK. */
interface AdsRuntime {
    fun initialize(context: Context, onComplete: () -> Unit)
    fun createBanner(context: Context, adUnitId: String): View?
    fun loadInterstitial(context: Context, adUnitId: String, onReady: () -> Unit, onFailed: () -> Unit)
    fun showInterstitial(activity: Activity): Boolean
    fun destroy(view: View)
}

/** UMP fica separado para a asserção de que [AdsController] não toca consentimento quando adsOff. */
interface ConsentRequester {
    fun request(activity: Activity, onReady: () -> Unit)
    fun privacyOptionsRequired(context: Context): Boolean = false
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit) = onDismissed()
}

/** Fachada do AdMob. Não deve ser referenciada pelo IME, pelo overlay ou pelo onboarding. */
class AdsController(
    private val appContext: Context,
    private val runtime: AdsRuntime = GoogleAdsRuntime,
    private val consent: ConsentRequester = GoogleConsentRequester,
) : AutoCloseable {
    private val initializationStarted = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    private val _privacyOptionsRequired = MutableStateFlow(false)
    private var interstitialReady = false

    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    /** Observável também nos testes: com adsOff o valor nunca sai de zero. */
    var initializationCount: Int = 0
        private set

    fun initializeIfNeeded(
        activity: Activity?,
        adsOff: Boolean,
        entitlementsLoaded: Boolean = true,
        onReady: () -> Unit = {},
    ) {
        if (!entitlementsLoaded || adsOff || activity == null || !initializationStarted.compareAndSet(false, true)) return
        consent.request(activity) {
            _privacyOptionsRequired.value = runCatching { consent.privacyOptionsRequired(appContext) }.getOrDefault(false)
            initializationCount++
            runtime.initialize(appContext) {
                _ready.value = true
                onReady()
            }
        }
    }

    fun bannerView(context: Context, adsOff: Boolean, entitlementsLoaded: Boolean = true): View? {
        if (!entitlementsLoaded || adsOff || !_ready.value) return null
        return runCatching { runtime.createBanner(context, context.getString(R.string.admob_banner_ad_unit_id)) }.getOrNull()
    }

    fun preloadInterstitial(activity: Activity?, adsOff: Boolean, entitlementsLoaded: Boolean = true) {
        if (!entitlementsLoaded || adsOff || activity == null || !_ready.value || interstitialReady) return
        runtime.loadInterstitial(
            appContext,
            appContext.getString(R.string.admob_interstitial_ad_unit_id),
            onReady = { interstitialReady = true },
            onFailed = { interstitialReady = false },
        )
    }

    fun showInterstitial(activity: Activity?, adsOff: Boolean, entitlementsLoaded: Boolean = true): Boolean {
        if (!entitlementsLoaded || adsOff || activity == null || !_ready.value || !interstitialReady) return false
        interstitialReady = false
        return runtime.showInterstitial(activity)
    }

    fun destroyBanner(view: View) = runtime.destroy(view)

    fun showPrivacyOptionsForm(activity: Activity) {
        if (!_privacyOptionsRequired.value) return
        consent.showPrivacyOptionsForm(activity) {
            _privacyOptionsRequired.value = runCatching { consent.privacyOptionsRequired(appContext) }.getOrDefault(false)
        }
    }

    override fun close() {
        _ready.value = false
        interstitialReady = false
    }
}

private object GoogleConsentRequester : ConsentRequester {
    override fun request(activity: Activity, onReady: () -> Unit) {
        val information = UserMessagingPlatform.getConsentInformation(activity)
        val parameters = ConsentRequestParameters.Builder().build()
        information.requestConsentInfoUpdate(
            activity,
            parameters,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { onReady() }
            },
            { onReady() },
        )
    }

    override fun privacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    override fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDismissed() }
    }
}

private object GoogleAdsRuntime : AdsRuntime {
    private var interstitial: InterstitialAd? = null

    override fun initialize(context: Context, onComplete: () -> Unit) {
        MobileAds.initialize(context) { onComplete() }
    }

    override fun createBanner(context: Context, adUnitId: String): View = AdView(context).apply {
        this.adUnitId = adUnitId
        // Reservamos 50dp no slot; a largura adaptativa continua adequada ao painel estreito.
        setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, 320))
        adListener = object : AdListener() {
            override fun onAdFailedToLoad(error: LoadAdError) = Unit
        }
        loadAd(AdRequest.Builder().build())
    }

    override fun loadInterstitial(context: Context, adUnitId: String, onReady: () -> Unit, onFailed: () -> Unit) {
        InterstitialAd.load(
            context,
            adUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    onReady()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitial = null
                    onFailed()
                }
            },
        )
    }

    override fun showInterstitial(activity: Activity): Boolean {
        val ad = interstitial ?: return false
        interstitial = null
        ad.show(activity)
        return true
    }

    override fun destroy(view: View) {
        (view as? AdView)?.destroy()
    }
}
