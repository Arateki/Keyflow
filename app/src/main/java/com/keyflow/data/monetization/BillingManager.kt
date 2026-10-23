package com.keyflow.data.monetization

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class MonetizationProductKind { CONSUMABLE, NON_CONSUMABLE }

object MonetizationProducts {
    const val CREDITS_10 = "credits_10"
    const val CREDITS_20 = "credits_20"
    const val CREDITS_50 = "credits_50"
    const val UNLOCK_FOREVER = "unlock_forever"

    /** Catálogo completo para não perder o contrato futuro; a v1 só consulta [UNLOCK_FOREVER]. */
    val all = mapOf(
        CREDITS_10 to MonetizationProductKind.CONSUMABLE,
        CREDITS_20 to MonetizationProductKind.CONSUMABLE,
        CREDITS_50 to MonetizationProductKind.CONSUMABLE,
        UNLOCK_FOREVER to MonetizationProductKind.NON_CONSUMABLE,
    )

    val activeV1 = listOf(UNLOCK_FOREVER)

    fun kind(productId: String): MonetizationProductKind? = all[productId]
}

data class StoreProduct(
    val productId: String,
    val kind: MonetizationProductKind,
    val formattedPrice: String? = null,
    internal val native: ProductDetails? = null,
)

enum class StorePurchaseState { PURCHASED, PENDING, UNSPECIFIED }

data class StorePurchase(
    val products: List<String>,
    val purchaseToken: String,
    val state: StorePurchaseState,
    val acknowledged: Boolean,
)

data class BillingOperationResult(
    val responseCode: Int,
    val debugMessage: String = "",
) {
    val ok: Boolean get() = responseCode == BillingClient.BillingResponseCode.OK
}

sealed interface BillingState {
    data object Disconnected : BillingState
    data object Ready : BillingState
    data class Unavailable(val reason: String) : BillingState
}

/** Porta pequena para as UIs e para os testes não dependerem do Play Console. */
interface BillingBackend {
    val purchaseUpdates: Flow<List<StorePurchase>>
    suspend fun connect()
    suspend fun queryProducts(productIds: List<String>): List<StoreProduct>
    suspend fun queryPurchases(): List<StorePurchase>
    fun launch(activity: Activity, product: StoreProduct, obfuscatedAccountId: String): BillingOperationResult
    suspend fun consume(purchaseToken: String): BillingOperationResult
    suspend fun acknowledge(purchaseToken: String): BillingOperationResult
    fun close()
}

class BillingUnavailableException(message: String) : Exception(message)

/**
 * Fachada do Play Billing. Falha de Play Services vira [BillingState.Unavailable], nunca impede o restante do app.
 * O manager não consulta SKUs de créditos na v1; o mapeamento continua pronto em [MonetizationProducts].
 */
class BillingManager(
    context: Context?,
    private val identity: suspend () -> String = context?.let { app ->
        { AnonymousIdentity(app.applicationContext).get() }
    } ?: { error("BillingManager precisa de identity em testes sem Context") },
    private val backend: BillingBackend = context?.let { PlayBillingBackend(it.applicationContext) }
        ?: error("BillingManager precisa de backend em testes sem Context"),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val redeemPurchase: suspend (StorePurchase) -> Boolean = { false },
) : AutoCloseable {
    private val _state = MutableStateFlow<BillingState>(BillingState.Disconnected)
    private val _products = MutableStateFlow<List<StoreProduct>>(emptyList())
    private val _purchases = MutableStateFlow<List<StorePurchase>>(emptyList())
    private var updatesJob: Job? = null
    private val acknowledgementsInFlight = mutableSetOf<String>()

    val state: StateFlow<BillingState> = _state.asStateFlow()
    val products: StateFlow<List<StoreProduct>> = _products.asStateFlow()
    val purchases: StateFlow<List<StorePurchase>> = _purchases.asStateFlow()
    val localUnlock: Flow<Boolean> = _purchases
        .map { list -> list.any { it.isPurchased(MonetizationProducts.UNLOCK_FOREVER) } }

    suspend fun connect() {
        updatesJob?.cancel()
        try {
            backend.connect()
            updatesJob = scope.launch {
                backend.purchaseUpdates.collect(::acceptPurchases)
            }
            _products.value = backend.queryProducts(MonetizationProducts.activeV1)
            refreshPurchases()
            _state.value = BillingState.Ready
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = BillingState.Unavailable(e.message ?: "Play Billing indisponível")
        }
    }

    suspend fun refreshPurchases() {
        runCatching { backend.queryPurchases() }
            .onSuccess(::publishPurchases)
    }

    suspend fun purchase(activity: Activity, productId: String): BillingOperationResult {
        val product = _products.value.firstOrNull { it.productId == productId }
            ?: return BillingOperationResult(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE, "produto não carregado")
        val accountId = AnonymousIdentity.obfuscatedAccountId(identity())
        return backend.launch(activity, product, accountId)
    }

    suspend fun consume(purchase: StorePurchase): BillingOperationResult {
        val productId = purchase.products.firstOrNull() ?: return BillingOperationResult(
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
            "compra sem produto",
        )
        if (MonetizationProducts.kind(productId) != MonetizationProductKind.CONSUMABLE) {
            return BillingOperationResult(BillingClient.BillingResponseCode.OK)
        }
        return backend.consume(purchase.purchaseToken)
    }

    suspend fun acknowledge(purchase: StorePurchase): BillingOperationResult {
        val productId = purchase.products.firstOrNull() ?: return BillingOperationResult(
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
            "compra sem produto",
        )
        if (purchase.state != StorePurchaseState.PURCHASED ||
            MonetizationProducts.kind(productId) != MonetizationProductKind.NON_CONSUMABLE
        ) {
            return BillingOperationResult(BillingClient.BillingResponseCode.ITEM_NOT_OWNED, "compra não confirmável")
        }
        if (purchase.acknowledged) return BillingOperationResult(BillingClient.BillingResponseCode.OK)
        return backend.acknowledge(purchase.purchaseToken)
    }

    override fun close() {
        updatesJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        backend.close()
    }

    private fun acceptPurchases(updates: List<StorePurchase>) {
        if (updates.isEmpty()) return
        val merged = mergePurchases(_purchases.value, updates)
        _purchases.value = merged
        scheduleAcknowledgements(merged)
    }

    private fun publishPurchases(purchases: List<StorePurchase>) {
        _purchases.value = purchases
        scheduleAcknowledgements(purchases)
    }

    private fun scheduleAcknowledgements(purchases: List<StorePurchase>) {
        purchases
            .filter { it.state == StorePurchaseState.PURCHASED && !it.acknowledged }
            .filter { purchase ->
                purchase.products.any { productId ->
                    MonetizationProducts.kind(productId) == MonetizationProductKind.NON_CONSUMABLE
                }
            }
            .forEach { purchase ->
                if (!acknowledgementsInFlight.add(purchase.purchaseToken)) return@forEach
                scope.launch {
                    val serverHandled = runCatching { redeemPurchase(purchase) }.getOrDefault(false)
                    val result = if (serverHandled) {
                        BillingOperationResult(BillingClient.BillingResponseCode.OK)
                    } else {
                        acknowledge(purchase)
                    }
                    if (!result.ok) acknowledgementsInFlight.remove(purchase.purchaseToken)
                }
            }
    }

    private fun mergePurchases(
        current: List<StorePurchase>,
        updates: List<StorePurchase>,
    ): List<StorePurchase> {
        val byProduct = linkedMapOf<String, StorePurchase>()
        current.forEach { purchase ->
            purchase.products.forEach { productId -> byProduct[productId] = purchase }
        }
        updates.forEach { purchase ->
            purchase.products.forEach { productId -> byProduct[productId] = purchase }
        }
        return byProduct.values.distinctBy(StorePurchase::purchaseToken)
    }

    private fun StorePurchase.isPurchased(productId: String): Boolean =
        state == StorePurchaseState.PURCHASED && productId in products
}

private class PlayBillingBackend(context: Context) : BillingBackend {
    private val updates = MutableSharedFlow<List<StorePurchase>>(extraBufferCapacity = 4)
    private val client = BillingClient.newBuilder(context)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                updates.tryEmit(purchases.map(::toStorePurchase))
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override val purchaseUpdates: Flow<List<StorePurchase>> = updates

    override suspend fun connect() {
        if (client.isReady) return
        val result = suspendCancellableCoroutine<BillingResult> { continuation ->
            client.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resumeWithException(
                        BillingUnavailableException("Play Billing desconectado"),
                    )
                }
            })
            continuation.invokeOnCancellation { client.endConnection() }
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            throw BillingUnavailableException(result.debugMessage.ifBlank { "Play Billing indisponível" })
        }
    }

    override suspend fun queryProducts(productIds: List<String>): List<StoreProduct> {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            })
            .build()
        val (result, details) = suspendCancellableCoroutine<Pair<BillingResult, List<ProductDetails>>> { continuation ->
            client.queryProductDetailsAsync(params) { billingResult, response ->
                if (continuation.isActive) continuation.resume(billingResult to response.productDetailsList)
            }
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            throw BillingUnavailableException(result.debugMessage.ifBlank { "não foi possível consultar produtos" })
        }
        return details.mapNotNull { detail ->
            MonetizationProducts.kind(detail.productId)?.let { kind ->
                StoreProduct(
                    productId = detail.productId,
                    kind = kind,
                    formattedPrice = detail.oneTimePurchaseOfferDetails?.formattedPrice,
                    native = detail,
                )
            }
        }
    }

    override suspend fun queryPurchases(): List<StorePurchase> {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val (result, purchases) = suspendCancellableCoroutine<Pair<BillingResult, List<Purchase>>> { continuation ->
            client.queryPurchasesAsync(params) { billingResult, items ->
                if (continuation.isActive) continuation.resume(billingResult to items)
            }
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            throw BillingUnavailableException(result.debugMessage.ifBlank { "não foi possível restaurar compras" })
        }
        return purchases.map(::toStorePurchase)
    }

    override fun launch(
        activity: Activity,
        product: StoreProduct,
        obfuscatedAccountId: String,
    ): BillingOperationResult {
        val native = product.native ?: return BillingOperationResult(
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            "produto sem detalhes do Play",
        )
        val details = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(native)
            .apply { native.oneTimePurchaseOfferDetails?.offerToken?.let(::setOfferToken) }
            .build()
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(details))
                .setObfuscatedAccountId(obfuscatedAccountId)
                .build(),
        )
        return BillingOperationResult(result.responseCode, result.debugMessage)
    }

    override suspend fun consume(purchaseToken: String): BillingOperationResult {
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
        val (result, _) = suspendCancellableCoroutine<Pair<BillingResult, String?>> { continuation ->
            client.consumeAsync(params) { billingResult, token ->
                if (continuation.isActive) continuation.resume(billingResult to token)
            }
        }
        return BillingOperationResult(result.responseCode, result.debugMessage)
    }

    override suspend fun acknowledge(purchaseToken: String): BillingOperationResult {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        val result = suspendCancellableCoroutine<BillingResult> { continuation ->
            client.acknowledgePurchase(params) { billingResult ->
                if (continuation.isActive) continuation.resume(billingResult)
            }
        }
        return BillingOperationResult(result.responseCode, result.debugMessage)
    }

    override fun close() {
        client.endConnection()
    }

    private companion object {
        fun toStorePurchase(purchase: Purchase): StorePurchase = StorePurchase(
            products = purchase.products,
            purchaseToken = purchase.purchaseToken,
            state = when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> StorePurchaseState.PURCHASED
                Purchase.PurchaseState.PENDING -> StorePurchaseState.PENDING
                else -> StorePurchaseState.UNSPECIFIED
            },
            acknowledged = purchase.isAcknowledged,
        )
    }
}
