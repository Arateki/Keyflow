package com.keyflow.data.monetization

import android.content.Context
import android.content.SharedPreferences
import com.keyflow.BuildConfig
import com.keyflow.auth.HttpErrorMessage
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.data.SecurePrefs
import com.keyflow.data.models.KeyFlowHttpClient
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/** Credenciais temporárias do registro anônimo; nunca entram em Settings nem no backup. */
interface KeyFlowAccessTokenStore {
    fun get(): String?
    fun put(token: String)
    fun clear()
}

internal class SharedPreferencesAccessTokenStore(
    private val prefs: SharedPreferences,
) : KeyFlowAccessTokenStore {
    override fun get(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
    override fun put(token: String) {
        check(prefs.edit().putString(KEY, token).commit()) { "não consegui guardar o token da API" }
    }
    override fun clear() {
        check(prefs.edit().remove(KEY).commit()) { "não consegui apagar o token da API" }
    }

    private companion object {
        const val KEY = "monetization.access_token"
    }
}

/** Erros que permitem a tela tentar de novo sem tratar uma falha da API como crash. */
sealed class KeyFlowApiException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Retryable(message: String, cause: Throwable? = null) : KeyFlowApiException(message, cause)
    class Unauthorized(message: String) : KeyFlowApiException(message)
    class Rejected(val status: Int, message: String) : KeyFlowApiException(message)
    class InvalidResponse(message: String, cause: Throwable? = null) : KeyFlowApiException(message, cause)
}

data class KeyFlowAccount(
    val balanceSeconds: Long,
    val adsOff: Boolean,
    val skinsUnlocked: Boolean,
)

/**
 * Cliente mínimo da API de monetização. O registro é lazy: construir este objeto não abre Play Integrity,
 * não toca na rede e não é chamado pelo onboarding.
 */
class KeyFlowApiClient(
    context: Context?,
    private val http: OkHttpClient = KeyFlowHttpClient.base(),
    private val baseUrl: String = BuildConfig.KEYFLOW_API_BASE_URL,
    private val identity: suspend () -> String = context?.let { app -> { AnonymousIdentity(app.applicationContext).get() } }
        ?: { error("KeyFlowApiClient precisa de identity em testes sem Context") },
    private val integrityToken: suspend (nonce: String) -> String =
        context?.let { app -> GoogleIntegrityTokenProvider(app.applicationContext).let { provider -> { nonce -> provider.request(nonce) } } }
            ?: { error("KeyFlowApiClient precisa de integrityToken em testes sem Context") },
    tokenStore: KeyFlowAccessTokenStore? = context?.applicationContext?.let { app ->
        SharedPreferencesAccessTokenStore(
            SecurePrefs.create(app, SecureCredentialStore.PREFS_NAME, SecureCredentialStore.KEY_ALIAS),
        )
    },
) {
    private val accessTokenStore = requireNotNull(tokenStore) {
        "KeyFlowApiClient precisa de tokenStore em testes sem Context"
    }
    private val registerLock = Mutex()
    private val urlBase = baseUrl.trimEnd('/')

    /** Registra ou recupera o token; chamado somente por um fluxo que realmente precisa da API. */
    suspend fun ensureRegistered(): String = withContext(Dispatchers.IO) {
        registerLock.withLock {
            accessTokenStore.get()?.let { return@withLock it }
            registerLocked()
        }
    }

    /** Conta é leitura barata; um 401 gira o token uma vez e repete a chamada. */
    suspend fun account(): KeyFlowAccount = withContext(Dispatchers.IO) {
        var token = ensureRegistered()
        var response = getAccount(token)
        if (response.code == 401) {
            response.close()
            token = registerLock.withLock {
                accessTokenStore.clear()
                registerLocked()
            }
            response = getAccount(token)
        }
        response.use { it.parseAccount() }
    }

    /** Redime a compra no servidor; o servidor valida e faz o acknowledge no Play. */
    suspend fun redeem(purchase: StorePurchase): Boolean = withContext(Dispatchers.IO) {
        val productId = purchase.products.firstOrNull() ?: return@withContext false
        val token = ensureRegistered()
        val integrity = integrityToken(nonceFor(identity()))
        val body = JSONObject()
            .put("productId", productId)
            .put("purchaseToken", purchase.purchaseToken)
            .put("integrityToken", integrity)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url("$urlBase/v1/purchases/redeem")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        execute(request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiFailure("resgate", response.code, text)
            true
        }
    }

    private suspend fun registerLocked(): String {
        val deviceKey = identity()
        val nonce = nonceFor(deviceKey)
        val integrity = integrityToken(nonce)
        val body = JSONObject()
            .put("deviceKey", deviceKey)
            .put("integrityToken", integrity)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url("$urlBase/v1/register")
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = execute(request)
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw apiFailure("registro", it.code, text)
            val token = runCatching { JSONObject(text).getString("accessToken") }
                .getOrNull()
                ?.takeIf { value -> value.isNotBlank() }
                ?: throw KeyFlowApiException.InvalidResponse("registro sem accessToken")
            accessTokenStore.put(token)
            return token
        }
    }

    private suspend fun getAccount(token: String): Response {
        val request = Request.Builder()
            .url("$urlBase/v1/account")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): Response = try {
        http.newCall(request).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw KeyFlowApiException.Retryable("API de monetização indisponível", e)
    } catch (e: Exception) {
        throw KeyFlowApiException.Retryable("falha ao chamar a API de monetização", e)
    }

    private fun Response.parseAccount(): KeyFlowAccount {
        val text = body?.string().orEmpty()
        if (code == 401) throw KeyFlowApiException.Unauthorized("token da API recusado após novo registro")
        if (!isSuccessful) throw apiFailure("conta", code, text)
        return try {
            val json = JSONObject(text)
            KeyFlowAccount(
                balanceSeconds = json.getLong("balanceSeconds"),
                adsOff = json.optBoolean("adsOff", false),
                skinsUnlocked = json.optBoolean("skinsUnlocked", false),
            )
        } catch (e: KeyFlowApiException) {
            throw e
        } catch (e: Exception) {
            throw KeyFlowApiException.InvalidResponse("resposta de conta inválida", e)
        }
    }

    private fun apiFailure(operation: String, status: Int, body: String): KeyFlowApiException {
        val message = HttpErrorMessage.of("KeyFlow $operation", status, body)
        return when {
            status == 401 -> KeyFlowApiException.Unauthorized(message)
            status == 408 || status == 429 || status >= 500 -> KeyFlowApiException.Retryable(message)
            else -> KeyFlowApiException.Rejected(status, message)
        }
    }

    private fun nonceFor(deviceKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$deviceKey:${UUID.randomUUID()}".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class GoogleIntegrityTokenProvider(private val context: Context) {
        private val manager: IntegrityManager by lazy { IntegrityManagerFactory.create(context) }

        suspend fun request(nonce: String): String {
            val builder = IntegrityTokenRequest.builder().setNonce(nonce)
            if (BuildConfig.KEYFLOW_INTEGRITY_CLOUD_PROJECT_NUMBER > 0) {
                builder.setCloudProjectNumber(BuildConfig.KEYFLOW_INTEGRITY_CLOUD_PROJECT_NUMBER)
            }
            return manager.requestIntegrityToken(builder.build()).await().token()
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isCanceled) continuation.cancel()
        else task.exception?.let(continuation::resumeWithException)
        ?: continuation.resume(task.result)
    }
}

private suspend fun okhttp3.Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: okhttp3.Call, response: Response) {
            if (continuation.isActive) continuation.resume(response)
            else response.close()
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
