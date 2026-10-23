package com.keyflow.auth

import android.content.Context
import android.content.SharedPreferences
import com.keyflow.data.SecurePrefs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

/**
 * Onde as credenciais moram (AUTH-02). [meta] é dado NÃO secreto do provedor que **sobrevive** ao [clear] — o id do host da
 * OpenAI, que precisa ser o mesmo entre logins, mesmo depois de Desconectar.
 */
interface CredentialStore {
    fun get(provider: ProviderId): Credential?

    /** A chave de API, mesmo quando um OAuth é a credencial ativa em [get]. */
    fun getApiKey(provider: ProviderId): Credential.ApiKey? = get(provider) as? Credential.ApiKey

    /** A sessão OAuth, mesmo quando uma chave de API é a credencial ativa em [get]. */
    fun getOAuth(provider: ProviderId): Credential.OAuth? = get(provider) as? Credential.OAuth

    fun put(provider: ProviderId, credential: Credential)
    fun clear(provider: ProviderId)

    /** O valor atual e, depois, cada mudança. */
    fun changes(provider: ProviderId): Flow<Credential?>
    fun meta(provider: ProviderId, key: String): String?
    fun putMeta(provider: ProviderId, key: String, value: String)
    /** Segredos de capacidades que não têm login OAuth; continuam no mesmo cofre cifrado. */
    fun getSecret(key: String): String? = null
    fun putSecret(key: String, value: String) = Unit
    fun clearSecret(key: String) = Unit
    fun secretChanges(key: String): Flow<String?> = callbackFlow {
        trySend(getSecret(key))
        awaitClose()
    }
    fun clearOAuth(provider: ProviderId) = clear(provider)
    fun clearApiKey(provider: ProviderId) = clear(provider)
}

/** O formato em que a credencial vai para o cofre. Lixo (ou formato desconhecido) lê como `null`, nunca como erro. */
internal object CredentialCodec {
    fun encode(credential: Credential): String = JSONObject().apply {
        when (credential) {
            is Credential.ApiKey -> {
                put("type", "api_key")
                put("value", credential.value)
            }
            is Credential.OAuth -> {
                put("type", "oauth")
                put("accessToken", credential.accessToken)
                credential.refreshToken?.let { put("refreshToken", it) }
                put("expiresAtMs", credential.expiresAtMs)
                credential.earliestRefreshAtMs?.let { put("earliestRefreshAtMs", it) }
                put("scopes", JSONArray(credential.scopes.toList()))
                credential.idToken?.let { put("idToken", it) }
                credential.clientId?.let { put("clientId", it) }
                credential.subject?.let { put("subject", it) }
                credential.account?.let { put("account", it) }
            }
        }
    }.toString()

    fun decode(text: String): Credential? = try {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "api_key" -> if (json.has("value")) Credential.ApiKey(json.getString("value")) else null
            "oauth" -> if (json.has("accessToken") && json.has("expiresAtMs")) {
                val scopes = json.optJSONArray("scopes")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty()
                Credential.OAuth(
                    accessToken = json.getString("accessToken"),
                    refreshToken = json.optStringOrNull("refreshToken"),
                    expiresAtMs = json.getLong("expiresAtMs"),
                    earliestRefreshAtMs = if (json.has("earliestRefreshAtMs")) json.getLong("earliestRefreshAtMs") else null,
                    scopes = scopes,
                    idToken = json.optStringOrNull("idToken"),
                    clientId = json.optStringOrNull("clientId"),
                    subject = json.optStringOrNull("subject"),
                    account = json.optStringOrNull("account"),
                )
            } else null
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null
}

/**
 * O cofre de verdade: `EncryptedSharedPreferences` (Keystore). **Alias de chave próprio** — a recuperação do [SecurePrefs]
 * apaga o keyset E a chave; dividir a chave com o `keyflow_secure` faria a recuperação de um invalidar o outro. O arquivo
 * ([PREFS_NAME]) está nas três listas de exclusão do backup (`BackupRulesTest`): restaurado sem a chave do Keystore, que não
 * é exportável, reabriria o crash em loop do A32 (Q8, 2026-09-07).
 */
class SecureCredentialStore(
    context: Context,
    prefsName: String = PREFS_NAME,
    keyAlias: String = KEY_ALIAS,
) : CredentialStore {

    private val prefs: SharedPreferences = SecurePrefs.create(context.applicationContext, prefsName, keyAlias)

    override fun get(provider: ProviderId): Credential? =
        prefs.getString(credKey(provider), null)?.let(CredentialCodec::decode)
            ?: prefs.getString(oauthKey(provider), null)?.let(CredentialCodec::decode)
            ?: prefs.getString(apiKeyKey(provider), null)?.let(CredentialCodec::decode)

    override fun getApiKey(provider: ProviderId): Credential.ApiKey? =
        prefs.getString(apiKeyKey(provider), null)?.let(CredentialCodec::decode) as? Credential.ApiKey

    override fun getOAuth(provider: ProviderId): Credential.OAuth? =
        prefs.getString(oauthKey(provider), null)?.let(CredentialCodec::decode) as? Credential.OAuth

    override fun put(provider: ProviderId, credential: Credential) {
        val editor = prefs.edit()
        when (credential) {
            is Credential.OAuth -> editor.putString(oauthKey(provider), CredentialCodec.encode(credential))
            is Credential.ApiKey -> editor.putString(apiKeyKey(provider), CredentialCodec.encode(credential))
        }
        editor.putString(credKey(provider), CredentialCodec.encode(credential))
        check(editor.commit()) { "não consegui gravar a credencial" }
    }

    override fun clear(provider: ProviderId) {
        check(prefs.edit()
            .remove(credKey(provider))
            .remove(oauthKey(provider))
            .remove(apiKeyKey(provider))
            .commit()
        ) { "não consegui apagar a credencial" }
    }

    override fun clearOAuth(provider: ProviderId) {
        val editor = prefs.edit().remove(oauthKey(provider))
        val existingApiKey = prefs.getString(apiKeyKey(provider), null)
        if (existingApiKey != null) {
            editor.putString(credKey(provider), existingApiKey)
        } else {
            editor.remove(credKey(provider))
        }
        check(editor.commit()) { "não consegui apagar a credencial de assinatura" }
    }

    override fun clearApiKey(provider: ProviderId) {
        val editor = prefs.edit().remove(apiKeyKey(provider))
        val existingOAuth = prefs.getString(oauthKey(provider), null)
        if (existingOAuth != null) {
            editor.putString(credKey(provider), existingOAuth)
        } else {
            editor.remove(credKey(provider))
        }
        check(editor.commit()) { "não consegui apagar a chave de API" }
    }

    override fun changes(provider: ProviderId): Flow<Credential?> = callbackFlow {
        val keys = setOf(credKey(provider), oauthKey(provider), apiKeyKey(provider))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed -> if (changed in keys || changed == null) trySend(get(provider)) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(get(provider))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    override fun meta(provider: ProviderId, key: String): String? = prefs.getString(metaKey(provider, key), null)

    override fun putMeta(provider: ProviderId, key: String, value: String) {
        check(prefs.edit().putString(metaKey(provider, key), value).commit()) { "não consegui gravar o dado do provedor" }
    }

    override fun getSecret(key: String): String? = prefs.getString(secretKey(key), null)

    override fun putSecret(key: String, value: String) {
        check(prefs.edit().putString(secretKey(key), value).commit()) { "não consegui gravar o segredo" }
    }

    override fun clearSecret(key: String) {
        check(prefs.edit().remove(secretKey(key)).commit()) { "não consegui apagar o segredo" }
    }

    override fun secretChanges(key: String): Flow<String?> = callbackFlow {
        val preferenceKey = secretKey(key)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == preferenceKey || changed == null) trySend(getSecret(key))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getSecret(key))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private fun credKey(provider: ProviderId) = "cred.${provider.value}"
    private fun oauthKey(provider: ProviderId) = "oauth.${provider.value}"
    private fun apiKeyKey(provider: ProviderId) = "apikey.${provider.value}"
    private fun metaKey(provider: ProviderId, key: String) = "meta.${provider.value}.$key"
    private fun secretKey(key: String) = "settings.$key"

    companion object {
        const val PREFS_NAME = "keyflow_auth"
        const val KEY_ALIAS = "keyflow_auth_master"
    }
}
