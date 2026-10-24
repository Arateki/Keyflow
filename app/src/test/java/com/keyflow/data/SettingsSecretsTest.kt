package com.keyflow.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Onde moram os segredos de [Settings]: no cofre cifrado, nunca no DataStore em claro (que vai no backup do Google
 * e na transferência entre aparelhos). Lógica pura, sem Context — o cofre é um [FakeSharedPreferences]; a prova nos
 * bytes do arquivo do DataStore, com o Keystore de verdade, está no `SettingsSecretsStorageTest` (androidTest).
 */
class SettingsSecretsTest {

    /** Cara de credencial: o nome termina assim. Um campo novo com este nome sem estar no cofre quebra o teste. */
    private val secretLooking = Regex("(Key|Password|Token|Secret|HeaderValue)$")

    private val settingsFields = Settings::class.java.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }

    // ── Cobertura: nenhum campo com cara de segredo fica de fora ───────────────

    @Test
    fun `every credential-looking Settings field is registered as a secret`() {
        val looking = settingsFields
            .filter { it.type == String::class.java && secretLooking.containsMatchIn(it.name) }
            .map { it.name }

        assertThat(looking).isNotEmpty()
        assertThat(SettingsSecrets.BY_PROPERTY.keys).containsAtLeastElementsIn(looking)
    }

    @Test
    fun `every registered secret is a String field that still exists in Settings`() {
        val strings = settingsFields.filter { it.type == String::class.java }.map { it.name }

        assertThat(strings).containsAtLeastElementsIn(SettingsSecrets.BY_PROPERTY.keys)
    }

    @Test
    fun `vault keys are unique`() {
        val keys = SettingsSecrets.BY_PROPERTY.values

        assertThat(keys).containsNoDuplicates()
    }

    @Test
    fun `the secrets an old build could write to the DataStore include every migrated key`() {
        assertThat(SettingsSecrets.LEGACY_IN_DATASTORE.map { it.name })
            .containsExactlyElementsIn(
                listOf(
                    SettingsSecrets.AUTH_HEADER_VALUE,
                    SettingsSecrets.OPENAI_TOKEN,
                    SettingsSecrets.GROQ_TOKEN,
                    SettingsSecrets.REASONING_CUSTOM_HEADER_VALUE,
                    SettingsSecrets.VISION_GOOGLE_API_KEY,
                    SettingsSecrets.VISION_ANTHROPIC_API_KEY,
                    SettingsSecrets.VISION_CUSTOM_HEADER_VALUE,
                    SettingsSecrets.FALA_ELEVENLABS_API_KEY,
                    SettingsSecrets.FALA_GOOGLE_API_KEY,
                    SettingsSecrets.FALA_CUSTOM_HEADER_VALUE,
                    SettingsSecrets.COMPUTER_SSH_PASSWORD,
                    SettingsSecrets.COMPUTER_SSH_KEY,
                    SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY,
                    SettingsSecrets.PROVIDER_OPENAI_API_KEY,
                    SettingsSecrets.PROVIDER_GOOGLE_API_KEY,
                    SettingsSecrets.PROVIDER_GROQ_API_KEY,
                    SettingsSecrets.PROVIDER_GROK_API_KEY,
                ),
            )
    }

    // ── Migração do DataStore para o cofre ───────────────────────────────────

    private fun key(name: String) = stringPreferencesKey(name)

    private fun dataStoreWith(vararg legacy: Pair<String, String>): Preferences =
        mutablePreferencesOf(*legacy.map { (name, value) -> key(name) to value }.toTypedArray())

    private fun move(datastore: Preferences, vault: FakeSharedPreferences) =
        SettingsSecrets.moveToVault(datastore, SettingsSecrets.legacyPresentIn(datastore), vault)

    @Test
    fun `a secret in the DataStore moves to an empty vault and may leave the DataStore`() {
        val vault = FakeSharedPreferences()
        val datastore = dataStoreWith(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY to "SENTINELA-ANTIGA")

        val removable = move(datastore, vault)

        assertThat(vault.getString(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY, null)).isEqualTo("SENTINELA-ANTIGA")
        assertThat(removable).containsExactly(key(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY))
    }

    @Test
    fun `every legacy secret moves, a private key included`() {
        val vault = FakeSharedPreferences()
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nSENTINELA-PEM\n-----END OPENSSH PRIVATE KEY-----"
        val legacy = SettingsSecrets.LEGACY_IN_DATASTORE.associate { it.name to "SENTINELA-${it.name}" } +
            (SettingsSecrets.COMPUTER_SSH_KEY to pem)
        val datastore = dataStoreWith(*legacy.toList().toTypedArray())

        val removable = move(datastore, vault)

        assertThat(removable).containsExactlyElementsIn(SettingsSecrets.LEGACY_IN_DATASTORE)
        legacy.forEach { (name, value) -> assertThat(vault.getString(name, null)).isEqualTo(value) }
    }

    @Test
    fun `a value already in the vault wins over the old one in the DataStore, and the old one still leaves`() {
        val vault = FakeSharedPreferences()
        vault.edit().putString(SettingsSecrets.PROVIDER_GOOGLE_API_KEY, "NOVA-NO-COFRE").commit()
        val datastore = dataStoreWith(SettingsSecrets.PROVIDER_GOOGLE_API_KEY to "VELHA-EM-CLARO")

        val removable = move(datastore, vault)

        assertThat(vault.getString(SettingsSecrets.PROVIDER_GOOGLE_API_KEY, null)).isEqualTo("NOVA-NO-COFRE")
        assertThat(removable).containsExactly(key(SettingsSecrets.PROVIDER_GOOGLE_API_KEY))
    }

    @Test
    fun `a failing vault commit keeps every secret in the DataStore and loses none`() {
        val vault = FakeSharedPreferences(commitSucceeds = false)
        val datastore = dataStoreWith(
            SettingsSecrets.FALA_ELEVENLABS_API_KEY to "SENTINELA-A",
            SettingsSecrets.COMPUTER_SSH_PASSWORD to "SENTINELA-B",
        )

        val removable = move(datastore, vault)

        // Nada pode sair do DataStore: o cofre não confirmou, e aquela cópia é a única que existe.
        assertThat(removable).isEmpty()
        assertThat(vault.snapshot()).isEmpty()
        // Quando o cofre voltar, a próxima tentativa conclui.
        vault.commitSucceeds = true
        assertThat(move(datastore, vault)).hasSize(2)
        assertThat(vault.getString(SettingsSecrets.FALA_ELEVENLABS_API_KEY, null)).isEqualTo("SENTINELA-A")
    }

    @Test
    fun `migrating again changes nothing`() {
        val vault = FakeSharedPreferences()
        val datastore = dataStoreWith(SettingsSecrets.VISION_CUSTOM_HEADER_VALUE to "SENTINELA-C")

        val first = move(datastore, vault)
        val afterFirst = vault.snapshot()
        // Segunda coleta: o DataStore já sem a chave (a remoção que o repositório faz com `first`).
        val cleaned = mutablePreferencesOf().also { it += datastore }.also { prefs -> first.forEach { prefs.remove(it) } }
        val second = move(cleaned, vault)

        assertThat(second).isEmpty()
        assertThat(vault.snapshot()).isEqualTo(afterFirst)
    }

    @Test
    fun `a blank old value is dropped without writing an empty secret to the vault`() {
        val vault = FakeSharedPreferences()
        val datastore = dataStoreWith(SettingsSecrets.PROVIDER_GROK_API_KEY to "")

        val removable = move(datastore, vault)

        assertThat(removable).containsExactly(key(SettingsSecrets.PROVIDER_GROK_API_KEY))
        assertThat(vault.snapshot()).isEmpty()
    }

    @Test
    fun `reading prefers the vault and falls back to the old value only while it is still in the DataStore`() {
        val name = SettingsSecrets.PROVIDER_GROQ_API_KEY
        val vault = FakeSharedPreferences()
        val datastore = dataStoreWith(name to "SENTINELA-EM-CLARO")

        // Cofre vazio e o DataStore ainda com o valor (migração que não pôde terminar): a tela o vê e o auto-save o preserva.
        assertThat(SettingsSecrets.read(vault, datastore, name)).isEqualTo("SENTINELA-EM-CLARO")

        vault.edit().putString(name, "SENTINELA-NO-COFRE").commit()
        assertThat(SettingsSecrets.read(vault, datastore, name)).isEqualTo("SENTINELA-NO-COFRE")

        assertThat(SettingsSecrets.read(FakeSharedPreferences(), dataStoreWith(), name)).isEqualTo("")
    }

    @Test
    fun `with nothing in clear the vault is not even opened`() {
        val vault = FakeSharedPreferences()
        // Só chaves que nunca foram segredo — o caminho comum do app.
        val datastore = dataStoreWith("computer_ssh_host" to "host.test", "provider_anthropic_model" to "claude")

        assertThat(SettingsSecrets.legacyPresentIn(datastore)).isEmpty()
        assertThat(move(datastore, vault)).isEmpty()
        assertThat(vault.editorsOpened).isEqualTo(0)
    }

    @Test
    fun `non-secret keys are never in the legacy list`() {
        val nonSecret = listOf(
            "computer_ssh_host", "computer_ssh_user", "computer_ssh_port", "computer_ssh_auth_type", "computer_mode",
            "custom_model_mirror_url", "reasoning_custom_header_name", "vision_custom_header_name",
            "fala_custom_header_name", "reasoning_custom_endpoint", "provider_anthropic_auth_mode",
            "provider_anthropic_model", "fala_elevenlabs_voice", "fala_google_voice",
        )

        assertThat(SettingsSecrets.LEGACY_IN_DATASTORE.map { it.name }).containsNoneIn(nonSecret)
    }
}
