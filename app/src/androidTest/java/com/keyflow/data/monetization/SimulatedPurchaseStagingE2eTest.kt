package com.keyflow.data.monetization

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.BuildConfig
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class SimulatedPurchaseStagingE2eTest {
    @Test
    fun simulated_unlock_survives_reregistration_and_refund_revokes_it() = runBlocking {
        val hasAccess = BuildConfig.KEYFLOW_STAGING_ACCESS_CLIENT_ID.isNotBlank() &&
            BuildConfig.KEYFLOW_STAGING_ACCESS_CLIENT_SECRET.isNotBlank()
        val hasTestKey = BuildConfig.KEYFLOW_SIMULATED_PURCHASE_HMAC_SECRET.toByteArray().size >= 32
        assumeTrue(
            "Pulado: configure os dois tokens Cloudflare Access de staging e a chave HMAC de teste via propriedades Gradle locais.",
            hasAccess && hasTestKey,
        )

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceKey = "sim-${UUID.randomUUID()}"
        val first = DebugSimulatedPurchaseClient(context, deviceKey, UUID.randomUUID().toString())
        val second = DebugSimulatedPurchaseClient(context, deviceKey, UUID.randomUUID().toString())
        var receipt: String? = null
        try {
            assertFalse(first.account().adsOff)
            receipt = first.buy()
            val unlocked = first.account()
            assertTrue(unlocked.adsOff)
            assertTrue(unlocked.skinsUnlocked)

            // Instância sem token local: registra novamente o mesmo aparelho e lê o entitlement remoto.
            val restored = second.account()
            assertTrue(restored.adsOff)
            assertTrue(restored.skinsUnlocked)

            second.refund(requireNotNull(receipt))
            val revoked = second.account()
            assertFalse(revoked.adsOff)
            assertFalse(revoked.skinsUnlocked)
        } finally {
            receipt?.let { runCatching { second.refund(it) } }
            runCatching { first.clearLocalToken() }
            runCatching { second.clearLocalToken() }
        }
    }
}
