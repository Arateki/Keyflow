package com.keyflow.data.monetization

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BillingManagerTest {
    @Test
    fun `obfuscatedAccountId usa o vetor sha256hex do design`() {
        assertThat(AnonymousIdentity.obfuscatedAccountId("550e8400-e29b-41d4-a716-446655440000"))
            .isEqualTo("a3a9e1ed9732cab28868127be00f1ce921acaefdd5c3b23a6e9e0072bd9c1a34")
    }

    @Test
    fun `catalogo mapeia recargas futuras mas a v1 consulta só unlock`() {
        assertThat(MonetizationProducts.kind(MonetizationProducts.CREDITS_10))
            .isEqualTo(MonetizationProductKind.CONSUMABLE)
        assertThat(MonetizationProducts.kind(MonetizationProducts.UNLOCK_FOREVER))
            .isEqualTo(MonetizationProductKind.NON_CONSUMABLE)
        assertThat(MonetizationProducts.activeV1).containsExactly(MonetizationProducts.UNLOCK_FOREVER)
    }

    @Test
    fun `manager usa hash no fluxo de compra e degrada sem Play via fake`() = runTest {
        val backend = FakeBillingBackend(
            products = listOf(StoreProduct(MonetizationProducts.UNLOCK_FOREVER, MonetizationProductKind.NON_CONSUMABLE)),
        )
        val manager = BillingManager(
            context = null,
            identity = { "550e8400-e29b-41d4-a716-446655440000" },
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        manager.connect()
        val result = manager.purchase(Activity(), MonetizationProducts.UNLOCK_FOREVER)

        assertThat(result.responseCode).isEqualTo(BillingClient.BillingResponseCode.OK)
        assertThat(backend.obfuscatedAccountId).isEqualTo(
            "a3a9e1ed9732cab28868127be00f1ce921acaefdd5c3b23a6e9e0072bd9c1a34",
        )
        manager.close()
    }

    private class FakeBillingBackend(
        private val products: List<StoreProduct>,
    ) : BillingBackend {
        override val purchaseUpdates: Flow<List<StorePurchase>> = emptyFlow()
        var obfuscatedAccountId: String? = null
        override suspend fun connect() = Unit
        override suspend fun queryProducts(productIds: List<String>): List<StoreProduct> = products
        override suspend fun queryPurchases(): List<StorePurchase> = emptyList()
        override fun launch(activity: Activity, product: StoreProduct, obfuscatedAccountId: String): BillingOperationResult {
            this.obfuscatedAccountId = obfuscatedAccountId
            return BillingOperationResult(BillingClient.BillingResponseCode.OK)
        }
        override suspend fun consume(purchaseToken: String): BillingOperationResult =
            BillingOperationResult(BillingClient.BillingResponseCode.OK)
        override fun close() = Unit
    }
}
