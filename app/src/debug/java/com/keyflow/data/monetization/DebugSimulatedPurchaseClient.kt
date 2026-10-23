package com.keyflow.data.monetization

import android.content.Context
import android.util.Base64
import com.keyflow.BuildConfig
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.data.SecurePrefs
import com.keyflow.net.KeyFlowHttpClient
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Cliente de compra fake compilado somente na source set debug. */
class DebugSimulatedPurchaseClient(context: Context, private val deviceKey: String, tokenSlot: String = "default") {
    private val app = context.applicationContext
    private val key = BuildConfig.KEYFLOW_SIMULATED_PURCHASE_HMAC_SECRET
    private val prefsName = "simulated_purchase_${deviceKey.replace('-', '_')}_${tokenSlot.replace('-', '_')}"
    private val tokenStore = SharedPreferencesAccessTokenStore(
        SecurePrefs.create(app, prefsName, "${SecureCredentialStore.KEY_ALIAS}_simulated_${tokenSlot.replace('-', '_')}"),
    )
    private val api = KeyFlowApiClient(
        context = null,
        http = KeyFlowHttpClient.base(),
        identity = { deviceKey },
        integrityToken = { nonce -> receipt("integrity", nonce, null) },
        tokenStore = tokenStore,
    )

    suspend fun account(): KeyFlowAccount = api.account()

    suspend fun buy(): String = withContext(Dispatchers.IO) {
        check(key.toByteArray(StandardCharsets.UTF_8).size >= 32) { "chave HMAC de simulação ausente" }
        api.ensureRegistered()
        val signed = receipt("purchase", UUID.randomUUID().toString(), "unlock_forever")
        post("/v1/test/purchases/simulate", signed)
        signed
    }

    suspend fun refund(signedReceipt: String) = withContext(Dispatchers.IO) {
        api.ensureRegistered()
        post("/v1/test/purchases/refund", signedReceipt)
    }

    fun clearLocalToken() = tokenStore.clear()

    private fun post(path: String, signedReceipt: String) {
        val token = tokenStore.get() ?: error("registro de staging ausente")
        val request = Request.Builder()
            .url("${BuildConfig.KEYFLOW_API_BASE_URL.trimEnd('/')}$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(JSONObject().put("purchaseReceipt", signedReceipt).toString().toRequestBody(JSON))
            .build()
        KeyFlowHttpClient.base().newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("API de staging recusou a operação (${response.code})")
        }
    }

    private fun receipt(purpose: String, nonce: String, productId: String?): String {
        val payload = JSONObject()
            .put("purpose", purpose)
            .put("deviceKey", deviceKey)
            .put("productId", productId ?: JSONObject.NULL)
            .put("nonce", nonce)
            .put("issuedAt", System.currentTimeMillis() / 1000)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        val payloadPart = Base64.encodeToString(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val message = "kfsim1.$payloadPart"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = Base64.encodeToString(
            mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "$message.$signature"
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
