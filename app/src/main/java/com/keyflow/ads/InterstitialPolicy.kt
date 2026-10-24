package com.keyflow.ads

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class InterstitialPolicyParams(
    val initialCooldownSeconds: Long = 120,
    val cooldownFactor: Double = 2.0,
    val maxCooldownSeconds: Long = 3_600,
    val minEngagementInteractions: Int = 5,
    val postPurchaseWindowSeconds: Long = 300,
    val dailyCap: Int = 10,
)

data class InterstitialPolicyState(
    val sessionCount: Long = 0,
    val shownCount: Long = 0,
    val lastShownAtMillis: Long? = null,
    val dailyBucket: Long? = null,
    val dailyShown: Int = 0,
    val purchaseStartedAtMillis: Long? = null,
)

/** Decisão pura de cadência; o armazenamento fica separado para manter o relógio e o estado fakeáveis. */
class InterstitialPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    params: InterstitialPolicyParams = InterstitialPolicyParams(),
    initialState: InterstitialPolicyState = InterstitialPolicyState(),
) {
    private var params = params.normalized()
    private var state = initialState.copy(sessionCount = initialState.sessionCount + 1)
    private val firstSession = initialState.sessionCount == 0L
    private var interactions = 0

    fun updateParams(next: InterstitialPolicyParams) {
        params = next.normalized()
    }

    fun recordInteraction() {
        interactions++
    }

    fun markPurchaseStarted() {
        state = state.copy(purchaseStartedAtMillis = clock())
    }

    fun shouldShow(tabSwitch: Boolean): Boolean {
        if (!tabSwitch || firstSession) return false
        if (interactions < params.minEngagementInteractions) return false

        val now = clock()
        val bucket = now.floorDiv(MILLIS_PER_DAY)
        val dailyShown = if (state.dailyBucket == bucket) state.dailyShown else 0
        if (dailyShown >= params.dailyCap) return false

        state.purchaseStartedAtMillis?.let { started ->
            if (now - started < params.postPurchaseWindowSeconds * 1_000L) return false
        }
        state.lastShownAtMillis?.let { shownAt ->
            if (now - shownAt < cooldownMillis()) return false
        }
        return true
    }

    fun markShown() {
        val now = clock()
        val bucket = now.floorDiv(MILLIS_PER_DAY)
        val shownToday = if (state.dailyBucket == bucket) state.dailyShown else 0
        state = state.copy(
            shownCount = state.shownCount + 1,
            lastShownAtMillis = now,
            dailyBucket = bucket,
            dailyShown = shownToday + 1,
        )
        interactions = 0
    }

    fun snapshot(): InterstitialPolicyState = state

    private fun cooldownMillis(): Long {
        val seconds = params.initialCooldownSeconds.toDouble() *
            params.cooldownFactor.powClamped((state.shownCount - 1L).coerceAtLeast(0L))
        return seconds.coerceAtMost(params.maxCooldownSeconds.toDouble())
            .times(1_000.0)
            .toLong()
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        fun InterstitialPolicyParams.normalized(): InterstitialPolicyParams = copy(
            initialCooldownSeconds = initialCooldownSeconds.coerceAtLeast(0),
            cooldownFactor = cooldownFactor.coerceIn(1.0, 10.0),
            maxCooldownSeconds = maxCooldownSeconds.coerceAtLeast(initialCooldownSeconds),
            minEngagementInteractions = minEngagementInteractions.coerceAtLeast(0),
            postPurchaseWindowSeconds = postPurchaseWindowSeconds.coerceAtLeast(0),
            dailyCap = dailyCap.coerceAtLeast(0),
        )

        fun Double.powClamped(exponent: Long): Double {
            var result = 1.0
            repeat(exponent.coerceAtMost(31).toInt()) { result = (result * this).coerceAtMost(1.0e12) }
            return result
        }
    }
}

fun com.keyflow.data.monetization.RemoteAdsParameters.toInterstitialPolicyParams() = InterstitialPolicyParams(
    initialCooldownSeconds = initialCooldownSeconds,
    cooldownFactor = cooldownFactor,
    maxCooldownSeconds = maxCooldownSeconds,
    minEngagementInteractions = minEngagementSeconds.toInt(),
    postPurchaseWindowSeconds = postPurchaseWindowSeconds,
    dailyCap = dailyCap,
)

interface InterstitialPolicyStore {
    suspend fun read(): InterstitialPolicyState
    suspend fun write(state: InterstitialPolicyState)
}

/** Persistência pequena em DataStore, criada apenas no app de configuração. */
class DataStoreInterstitialPolicyStore(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : InterstitialPolicyStore, AutoCloseable {
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { context.applicationContext.preferencesDataStoreFile("keyflow_interstitial") },
    )

    override suspend fun read(): InterstitialPolicyState = withContext(Dispatchers.IO) {
        dataStore.data.first().let { prefs ->
            InterstitialPolicyState(
                sessionCount = prefs[SESSION_COUNT] ?: 0,
                shownCount = prefs[SHOWN_COUNT] ?: 0,
                lastShownAtMillis = prefs[LAST_SHOWN],
                dailyBucket = prefs[DAILY_BUCKET],
                dailyShown = (prefs[DAILY_SHOWN] ?: 0).coerceAtLeast(0),
                purchaseStartedAtMillis = prefs[PURCHASE_STARTED_AT],
            )
        }
    }

    override suspend fun write(state: InterstitialPolicyState) {
        dataStore.edit { prefs ->
            prefs[SESSION_COUNT] = state.sessionCount
            prefs[SHOWN_COUNT] = state.shownCount
            state.lastShownAtMillis?.let { prefs[LAST_SHOWN] = it } ?: prefs.remove(LAST_SHOWN)
            state.dailyBucket?.let { prefs[DAILY_BUCKET] = it } ?: prefs.remove(DAILY_BUCKET)
            prefs[DAILY_SHOWN] = state.dailyShown
            state.purchaseStartedAtMillis?.let { prefs[PURCHASE_STARTED_AT] = it }
                ?: prefs.remove(PURCHASE_STARTED_AT)
        }
    }

    override fun close() = scope.cancel()

    private val scope = scope

    private companion object {
        val SESSION_COUNT = longPreferencesKey("session_count")
        val SHOWN_COUNT = longPreferencesKey("shown_count")
        val LAST_SHOWN = longPreferencesKey("last_shown_at")
        val DAILY_BUCKET = longPreferencesKey("daily_bucket")
        val DAILY_SHOWN = intPreferencesKey("daily_shown")
        val PURCHASE_STARTED_AT = longPreferencesKey("purchase_started_at")
    }
}
