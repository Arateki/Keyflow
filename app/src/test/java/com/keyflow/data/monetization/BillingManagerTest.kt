package com.keyflow.data.monetization

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

    @Test
    fun `atualizacao parcial do Play preserva os demais produtos`() = runTest {
        val updates = MutableSharedFlow<List<StorePurchase>>(extraBufferCapacity = 1)
        val credit = StorePurchase(
            products = listOf(MonetizationProducts.CREDITS_10),
            purchaseToken = "credit-token",
            state = StorePurchaseState.PURCHASED,
            acknowledged = true,
        )
        val unlock = StorePurchase(
            products = listOf(MonetizationProducts.UNLOCK_FOREVER),
            purchaseToken = "unlock-token",
            state = StorePurchaseState.PURCHASED,
            acknowledged = false,
        )
        val backend = FakeBillingBackend(
            products = emptyList(),
            initialPurchases = listOf(credit),
            updates = updates,
        )
        val manager = BillingManager(
            context = null,
            identity = { "identity" },
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        manager.connect()
        updates.emit(listOf(unlock))

        assertThat(manager.purchases.value).containsExactly(credit, unlock)
        manager.close()
    }

    @Test
    fun `unlock não confirmado usa acknowledge do fake`() = runTest {
        val backend = FakeBillingBackend(products = emptyList())
        val manager = BillingManager(
            context = null,
            identity = { "identity" },
            backend = backend,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        val purchase = StorePurchase(
            products = listOf(MonetizationProducts.UNLOCK_FOREVER),
            purchaseToken = "unlock-token",
            state = StorePurchaseState.PURCHASED,
            acknowledged = false,
        )

        assertThat(manager.acknowledge(purchase).ok).isTrue()
        assertThat(backend.acknowledgedToken).isEqualTo("unlock-token")
        manager.close()
    }

    @Test
    fun `acknowledge local acontece mesmo quando o redeem remoto confirma`() = runTest {
        val purchase = StorePurchase(
            products = listOf(MonetizationProducts.UNLOCK_FOREVER),
            purchaseToken = "unlock-token",
            state = StorePurchaseState.PURCHASED,
            acknowledged = false,
        )
        val backend = FakeBillingBackend(products = emptyList(), initialPurchases = listOf(purchase))
        val manager = BillingManager(
            context = null,
            identity = { "identity" },
            backend = backend,
            redeemPurchase = { true },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        manager.connect()

        assertThat(backend.acknowledgedToken).isEqualTo("unlock-token")
        manager.close()
    }

    private class FakeBillingBackend(
        private val products: List<StoreProduct>,
        private val initialPurchases: List<StorePurchase> = emptyList(),
        private val updates: Flow<List<StorePurchase>> = emptyFlow(),
    ) : BillingBackend {
        override val purchaseUpdates: Flow<List<StorePurchase>> = updates
        var obfuscatedAccountId: String? = null
        var acknowledgedToken: String? = null
        override suspend fun connect() = Unit
        override suspend fun queryProducts(productIds: List<String>): List<StoreProduct> = products
        override suspend fun queryPurchases(): List<StorePurchase> = initialPurchases
        override fun launch(activity: Activity, product: StoreProduct, obfuscatedAccountId: String): BillingOperationResult {
            this.obfuscatedAccountId = obfuscatedAccountId
            return BillingOperationResult(BillingClient.BillingResponseCode.OK)
        }
        override suspend fun consume(purchaseToken: String): BillingOperationResult =
            BillingOperationResult(BillingClient.BillingResponseCode.OK)
        override suspend fun acknowledge(purchaseToken: String): BillingOperationResult {
            acknowledgedToken = purchaseToken
            return BillingOperationResult(BillingClient.BillingResponseCode.OK)
        }
        override fun close() = Unit
    }
}
