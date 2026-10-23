package com.keyflow.auth

import android.content.SharedPreferences

/**
 * Migração única das chaves que a configuração antiga guardava em `keyflow_secure`.
 * O destino é sempre o [CredentialStore] (`keyflow_auth`); se já houver uma credencial lá, ela vence.
 */
internal object LegacyApiKeyMigration {

    const val LEGACY_PREFS_NAME = "keyflow_secure"

    private val legacyKeys = linkedMapOf(
        ProviderIds.ANTHROPIC to "provider_anthropic_api_key",
        ProviderIds.OPENAI to "provider_openai_api_key",
        ProviderIds.GOOGLE to "provider_google_api_key",
    )
    private val LOCK = Any()

    /** Compatibilidade dos testes antigos; a produção migra pelo repositório de Settings. */
    fun migrate(legacy: SharedPreferences, target: CredentialStore) = synchronized(LOCK) {
        val editor = legacy.edit()
        var changed = false
        for ((provider, key) in legacyKeys) {
            val value = legacy.getString(key, null).orEmpty()
            if (value.isNotBlank() && target.getApiKey(provider) == null) {
                target.put(provider, Credential.ApiKey(value))
            }
            if (legacy.contains(key)) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) check(editor.commit()) { "não consegui apagar cópias antigas de chaves" }
    }
}
