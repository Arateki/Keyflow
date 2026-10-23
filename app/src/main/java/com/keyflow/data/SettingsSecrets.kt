package com.keyflow.data

import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.ProviderIds
import kotlinx.coroutines.sync.Mutex

/** Compatibilidade das credenciais que versões antigas guardaram em DataStore ou `keyflow_secure`. */
internal object SettingsSecrets {
    const val AUTH_HEADER_VALUE = "auth_header_value"
    const val OPENAI_TOKEN = "openai_token"
    const val GROQ_TOKEN = "groq_token"
    const val REASONING_CUSTOM_HEADER_VALUE = "reasoning_custom_header_value"
    const val VISION_GOOGLE_API_KEY = "vision_google_api_key"
    const val VISION_ANTHROPIC_API_KEY = "vision_anthropic_api_key"
    const val VISION_CUSTOM_HEADER_VALUE = "vision_custom_header_value"
    const val FALA_ELEVENLABS_API_KEY = "fala_elevenlabs_api_key"
    const val FALA_GOOGLE_API_KEY = "fala_google_api_key"
    const val FALA_CUSTOM_HEADER_VALUE = "fala_custom_header_value"
    const val COMPUTER_SSH_PASSWORD = "computer_ssh_password"
    const val COMPUTER_SSH_KEY = "computer_ssh_key"
    const val PROVIDER_ANTHROPIC_API_KEY = "provider_anthropic_api_key"
    const val PROVIDER_OPENAI_API_KEY = "provider_openai_api_key"
    const val PROVIDER_GOOGLE_API_KEY = "provider_google_api_key"
    const val PROVIDER_GROQ_API_KEY = "provider_groq_api_key"
    const val PROVIDER_GROK_API_KEY = "provider_grok_api_key"

    /** Os segredos que continuam como campos de configuração; chaves de API não entram mais em Settings. */
    val BY_PROPERTY: Map<String, String> = mapOf(
        "customHeaderValue" to AUTH_HEADER_VALUE,
        "reasoningCustomHeaderValue" to REASONING_CUSTOM_HEADER_VALUE,
        "visionCustomHeaderValue" to VISION_CUSTOM_HEADER_VALUE,
        "falaCustomHeaderValue" to FALA_CUSTOM_HEADER_VALUE,
        "computerSshPassword" to COMPUTER_SSH_PASSWORD,
        "computerSshKey" to COMPUTER_SSH_KEY,
    )

    /** Tudo que versões de desenvolvimento chegaram a escrever em claro no DataStore. */
    val LEGACY_IN_DATASTORE: List<Preferences.Key<String>> = listOf(
        AUTH_HEADER_VALUE, OPENAI_TOKEN, GROQ_TOKEN, REASONING_CUSTOM_HEADER_VALUE,
        VISION_GOOGLE_API_KEY, VISION_ANTHROPIC_API_KEY, VISION_CUSTOM_HEADER_VALUE,
        FALA_ELEVENLABS_API_KEY, FALA_GOOGLE_API_KEY, FALA_CUSTOM_HEADER_VALUE,
        COMPUTER_SSH_PASSWORD, COMPUTER_SSH_KEY, PROVIDER_ANTHROPIC_API_KEY,
        PROVIDER_OPENAI_API_KEY, PROVIDER_GOOGLE_API_KEY, PROVIDER_GROQ_API_KEY,
        PROVIDER_GROK_API_KEY,
    ).map(::stringPreferencesKey)

    /**
     * Ordem da migração, independente da ordem de [SharedPreferences.all]. Para um mesmo provedor,
     * o valor já no cofre vence; depois vêm Provedores/Raciocínio, Transcrição, Visão e Fala.
     * Os valores legados que perderem uma colisão são descartados junto com a origem.
     */
    val LEGACY_MIGRATION_ORDER: List<String> = listOf(
        PROVIDER_ANTHROPIC_API_KEY,
        PROVIDER_OPENAI_API_KEY,
        PROVIDER_GOOGLE_API_KEY,
        PROVIDER_GROQ_API_KEY,
        PROVIDER_GROK_API_KEY,
        OPENAI_TOKEN,
        GROQ_TOKEN,
        VISION_GOOGLE_API_KEY,
        VISION_ANTHROPIC_API_KEY,
        FALA_ELEVENLABS_API_KEY,
        FALA_GOOGLE_API_KEY,
        AUTH_HEADER_VALUE,
        REASONING_CUSTOM_HEADER_VALUE,
        VISION_CUSTOM_HEADER_VALUE,
        FALA_CUSTOM_HEADER_VALUE,
        COMPUTER_SSH_PASSWORD,
        COMPUTER_SSH_KEY,
    )

    private val LEGACY_ORDER_INDEX = LEGACY_MIGRATION_ORDER.withIndex().associate { it.value to it.index }
    private val LEGACY_DATASTORE_KEY_BY_NAME = LEGACY_IN_DATASTORE.associateBy { it.name }

    /** saveSettings e as migrações usam o mesmo lock: gravar o destino vem antes de remover a origem. */
    val LOCK = Any()
    val SUSPEND_LOCK = Mutex()

    fun legacyPresentIn(datastore: Preferences): List<Preferences.Key<String>> =
        LEGACY_IN_DATASTORE
            .filter { it in datastore }
            .sortedBy { LEGACY_ORDER_INDEX[it.name] ?: Int.MAX_VALUE }

    /** Move cada segredo para o auth vault e só devolve as chaves que já podem sair do DataStore. */
    fun moveToVault(
        datastore: Preferences,
        present: List<Preferences.Key<String>>,
        target: CredentialStore,
    ): Set<Preferences.Key<String>> = synchronized(LOCK) {
        present
            .sortedBy { LEGACY_ORDER_INDEX[it.name] ?: Int.MAX_VALUE }
            .filter { moveOne(it.name, datastore[it].orEmpty(), target) }
            .toSet()
    }

    /** Migra o antigo `keyflow_secure`; cada origem é removida só depois do destino cifrado confirmar. */
    fun moveLegacyPrefs(legacy: SharedPreferences, target: CredentialStore) = synchronized(LOCK) {
        val editor = legacy.edit()
        var changed = false
        LEGACY_MIGRATION_ORDER.filter { it in legacy.all.keys }.forEach { name ->
            if (moveOne(name, legacy.getString(name, null).orEmpty(), target)) {
                editor.remove(name)
                changed = true
            }
        }
        if (changed) check(editor.commit()) { "não consegui remover credenciais antigas" }
    }

    /**
     * Junta as duas origens antigas antes de aplicar a prioridade. Assim `provider_*` do DataStore não perde para
     * `openai_token` só porque o arquivo cifrado foi visitado primeiro. Em empate de nome, o cofre antigo vem primeiro.
     * O DataStore só é apagado pelo chamador, depois que este bloco síncrono termina sem exceção.
     */
    fun migrateLegacySources(
        datastore: Preferences,
        legacy: SharedPreferences,
        target: CredentialStore,
    ): Set<Preferences.Key<String>> = synchronized(LOCK) {
        val legacyNames = legacy.all.keys
        val datastoreKeys = LEGACY_DATASTORE_KEY_BY_NAME.values.filter { it in datastore }
        val names = (legacyNames + datastoreKeys.map { it.name })
            .filter { it in LEGACY_ORDER_INDEX }
            .distinct()
            .sortedBy { LEGACY_ORDER_INDEX[it] ?: Int.MAX_VALUE }
        val editor = legacy.edit()
        val removableDataStore = mutableSetOf<Preferences.Key<String>>()
        var legacyChanged = false

        names.forEach { name ->
            val datastoreKey = LEGACY_DATASTORE_KEY_BY_NAME[name]
            val candidates = buildList {
                if (name in legacyNames) add(legacy.getString(name, null).orEmpty())
                if (datastoreKey != null && datastoreKey in datastore) add(datastore[datastoreKey].orEmpty())
            }
            candidates.forEach { value -> check(moveOne(name, value, target)) { "segredo legado desconhecido: $name" } }
            if (name in legacyNames) {
                editor.remove(name)
                legacyChanged = true
            }
            if (datastoreKey != null && datastoreKey in datastore) removableDataStore += datastoreKey
        }

        if (legacyChanged) check(editor.commit()) { "não consegui remover credenciais antigas" }
        removableDataStore
    }

    private fun moveOne(name: String, value: String, target: CredentialStore): Boolean {
        if (value.isBlank()) return true
        providerFor(name)?.let { provider ->
            if (target.getApiKey(provider) == null) target.put(provider, Credential.ApiKey(value))
            return true
        }
        val targetKey = targetKeyFor(name) ?: return false
        if (target.getSecret(targetKey).isNullOrEmpty()) target.putSecret(targetKey, value)
        return true
    }

    private fun providerFor(name: String) = when (name) {
        OPENAI_TOKEN, PROVIDER_OPENAI_API_KEY -> ProviderIds.OPENAI
        GROQ_TOKEN, PROVIDER_GROQ_API_KEY -> ProviderIds.GROQ
        VISION_GOOGLE_API_KEY, FALA_GOOGLE_API_KEY, PROVIDER_GOOGLE_API_KEY -> ProviderIds.GOOGLE
        VISION_ANTHROPIC_API_KEY, PROVIDER_ANTHROPIC_API_KEY -> ProviderIds.ANTHROPIC
        FALA_ELEVENLABS_API_KEY -> ProviderIds.ELEVENLABS
        PROVIDER_GROK_API_KEY -> ProviderIds.GROK
        else -> null
    }

    private fun targetKeyFor(name: String): String? = when (name) {
        AUTH_HEADER_VALUE -> SettingsCredentialKeys.CUSTOM_HEADER
        REASONING_CUSTOM_HEADER_VALUE -> SettingsCredentialKeys.REASONING_CUSTOM_HEADER
        VISION_CUSTOM_HEADER_VALUE -> SettingsCredentialKeys.VISION_CUSTOM_HEADER
        FALA_CUSTOM_HEADER_VALUE -> SettingsCredentialKeys.FALA_CUSTOM_HEADER
        COMPUTER_SSH_PASSWORD -> SettingsCredentialKeys.COMPUTER_SSH_PASSWORD
        COMPUTER_SSH_KEY -> SettingsCredentialKeys.COMPUTER_SSH_KEY
        else -> null
    }

    // Compatibilidade dos testes puros antigos; o código de produção não usa SharedPreferences como destino.
    fun read(vault: SharedPreferences, datastore: Preferences, name: String): String =
        vault.getString(name, "").orEmpty().ifEmpty { datastore[stringPreferencesKey(name)].orEmpty() }

    fun moveToVault(
        datastore: Preferences,
        present: List<Preferences.Key<String>>,
        vault: SharedPreferences,
    ): Set<Preferences.Key<String>> = synchronized(LOCK) {
        present.filter { key ->
            val value = datastore[key].orEmpty()
            if (value.isBlank()) true
            else if (vault.getString(key.name, "").isNullOrEmpty()) vault.edit().putString(key.name, value).commit()
            else true
        }.toSet()
    }
}
