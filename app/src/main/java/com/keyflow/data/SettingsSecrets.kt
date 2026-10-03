package com.keyflow.data

import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Os segredos de [Settings] e onde moram: no cofre cifrado (`keyflow_secure`, `EncryptedSharedPreferences` com chave
 * no Keystore), nunca no DataStore. O DataStore é um arquivo em claro em `files/datastore/` e **não** está nas regras de
 * exclusão do backup (`backup_rules.xml`, `data_extraction_rules.xml`); o cofre está — então um segredo gravado no
 * DataStore iria em texto claro para o backup do Google e para a transferência entre aparelhos.
 *
 * Um campo novo de [Settings] que seja credencial (chave de API, senha, valor de cabeçalho de autenticação, chave
 * privada) entra aqui, em [BY_PROPERTY], e no par leitura/gravação do `SettingsRepository`. O `SettingsSecretsTest`
 * falha enquanto um campo com cara de segredo ficar de fora.
 */
internal object SettingsSecrets {

    // Chaves no cofre. Nomes em snake_case; as 14 últimas têm o mesmo texto da chave que o DataStore chegou a ter em
    // builds de desenvolvimento (ver [LEGACY_IN_DATASTORE]).
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

    /** Propriedade de [Settings] → chave no cofre. Todo segredo de [Settings] está aqui (e só aqui mora no cofre). */
    val BY_PROPERTY: Map<String, String> = mapOf(
        "customHeaderValue" to AUTH_HEADER_VALUE,
        "openaiToken" to OPENAI_TOKEN,
        "groqToken" to GROQ_TOKEN,
        "reasoningCustomHeaderValue" to REASONING_CUSTOM_HEADER_VALUE,
        "visionGoogleApiKey" to VISION_GOOGLE_API_KEY,
        "visionAnthropicApiKey" to VISION_ANTHROPIC_API_KEY,
        "visionCustomHeaderValue" to VISION_CUSTOM_HEADER_VALUE,
        "falaElevenLabsApiKey" to FALA_ELEVENLABS_API_KEY,
        "falaGoogleApiKey" to FALA_GOOGLE_API_KEY,
        "falaCustomHeaderValue" to FALA_CUSTOM_HEADER_VALUE,
        "computerSshPassword" to COMPUTER_SSH_PASSWORD,
        "computerSshKey" to COMPUTER_SSH_KEY,
        "providerAnthropicApiKey" to PROVIDER_ANTHROPIC_API_KEY,
        "providerOpenAiApiKey" to PROVIDER_OPENAI_API_KEY,
        "providerGoogleApiKey" to PROVIDER_GOOGLE_API_KEY,
        "providerGroqApiKey" to PROVIDER_GROQ_API_KEY,
        "providerGrokApiKey" to PROVIDER_GROK_API_KEY,
    )

    /**
     * Os segredos que builds de desenvolvimento gravaram em claro no DataStore (a chave do DataStore tinha o mesmo
     * nome da do cofre). Os três primeiros de [BY_PROPERTY] nunca estiveram lá.
     */
    val LEGACY_IN_DATASTORE: List<Preferences.Key<String>> = BY_PROPERTY.values
        .filter { it != AUTH_HEADER_VALUE && it != OPENAI_TOKEN && it != GROQ_TOKEN }
        .map { stringPreferencesKey(it) }

    /**
     * Serializa o "se o cofre está vazio, copia" da migração com a gravação das Settings: sem isto, uma
     * `saveSettings` que commitasse entre a checagem e o commit da migração seria sobrescrita pelo valor velho.
     */
    val LOCK = Any()

    /** Quais [LEGACY_IN_DATASTORE] estão em [datastore]. Vazio é o caso comum: nada a fazer, o cofre nem precisa abrir. */
    fun legacyPresentIn(datastore: Preferences): List<Preferences.Key<String>> =
        LEGACY_IN_DATASTORE.filter { it in datastore }

    /**
     * O segredo [name]: o do [vault]. Vazio no cofre mas ainda em claro no [datastore] (um build de desenvolvimento
     * antigo cuja migração não chegou a terminar) devolve o valor do DataStore, para o auto-save da tela não regravar
     * "" por cima dele e perdê-lo.
     */
    fun read(vault: SharedPreferences, datastore: Preferences, name: String): String =
        vault.getString(name, "").orEmpty().ifEmpty { datastore[stringPreferencesKey(name)].orEmpty() }

    /**
     * Copia para o [vault] os segredos [present] que ele ainda não tem e devolve as chaves que já **podem sair do
     * DataStore**: as que o cofre passou a ter (com `commit()` verdadeiro) ou já tinha. O valor do cofre sempre
     * vence — um segredo velho em claro nunca sobrescreve o que o usuário gravou depois.
     *
     * Se o `commit()` falhar devolve vazio: nada é apagado do DataStore (o dado continua existindo, só que em claro,
     * e a próxima leitura tenta de novo). Idempotente: repetir depois de um sucesso não copia nem devolve nada novo.
     */
    fun moveToVault(
        datastore: Preferences,
        present: List<Preferences.Key<String>>,
        vault: SharedPreferences,
    ): Set<Preferences.Key<String>> {
        synchronized(LOCK) {
            val toCopy = present.filter { key ->
                datastore[key].orEmpty().isNotEmpty() && vault.getString(key.name, "").isNullOrEmpty()
            }
            if (toCopy.isNotEmpty()) {
                val editor = vault.edit()
                toCopy.forEach { key -> editor.putString(key.name, datastore[key].orEmpty()) }
                if (!editor.commit()) return emptySet()
            }
        }
        return present.toSet()
    }
}
