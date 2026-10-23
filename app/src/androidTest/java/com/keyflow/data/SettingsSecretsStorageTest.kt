package com.keyflow.data

import android.content.Context
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.auth.Credential
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.ProviderId
import com.keyflow.auth.ProviderIds
import com.keyflow.auth.SecureCredentialStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Os segredos de [Settings] moram no cofre cifrado, nunca no arquivo em claro do DataStore (que vai no backup do
 * Google e na transferência entre aparelhos), e quem já os tinha gravado em claro em builds de desenvolvimento é
 * migrado. Cada teste usa o próprio arquivo de DataStore e o próprio cofre (nomes únicos), com o Keystore de verdade.
 *
 * O `FactorySettingsListener` devolve a config do dono do aparelho ao fim da instrumentação: o que estes testes
 * gravam no DataStore/cofre de teste some depois — esperado, e nenhum deles encosta no arquivo nem no cofre reais.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSecretsStorageTest {

    private lateinit var context: Context
    private lateinit var dataStoreFile: File
    private lateinit var securePrefsName: String
    private lateinit var authPrefsName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dataStoreFile = File(context.filesDir, "test-secrets-datastore/$id.preferences_pb")
        securePrefsName = "test_keyflow_secure_secrets_$id"
        authPrefsName = "${SecureCredentialStore.PREFS_NAME}_$securePrefsName"
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
        dataStoreFile.parentFile?.delete()
        context.deleteSharedPreferences(securePrefsName)
        context.deleteSharedPreferences(authPrefsName)
    }

    private fun newRepository() = SettingsRepository(
        context = context,
        dataStoreFile = dataStoreFile,
        securePrefsName = securePrefsName,
    )

    private fun newRepository(store: CredentialStore) = SettingsRepository(
        context = context,
        dataStoreFile = dataStoreFile,
        securePrefsName = securePrefsName,
        credentialStoreFactory = { _, _ -> store },
    )

    /** Cofre controlável para provar que uma falha depois de uma gravação não apaga a origem. */
    private class FailingCredentialStore : CredentialStore {
        private val credentials = mutableMapOf<ProviderId, Credential>()
        private val secrets = mutableMapOf<String, String>()
        var writesBeforeFailure: Int? = null

        private fun beforeWrite() {
            val remaining = writesBeforeFailure ?: return
            if (remaining == 0) error("falha de teste no meio da migração")
            writesBeforeFailure = remaining - 1
        }

        override fun get(provider: ProviderId): Credential? = credentials[provider]
        override fun put(provider: ProviderId, credential: Credential) {
            beforeWrite()
            credentials[provider] = credential
        }
        override fun clear(provider: ProviderId) { credentials.remove(provider) }
        override fun changes(provider: ProviderId): Flow<Credential?> = flowOf(credentials[provider])
        override fun meta(provider: ProviderId, key: String): String? = null
        override fun putMeta(provider: ProviderId, key: String, value: String) = Unit
        override fun getSecret(key: String): String? = secrets[key]
        override fun putSecret(key: String, value: String) {
            beforeWrite()
            secrets[key] = value
        }
        override fun clearSecret(key: String) { secrets.remove(key) }
    }

    /** O cofre antigo, usado só para semear a migração. */
    private fun legacyVault() = SecurePrefs.create(context, securePrefsName)

    /** O destino único da V1-2. */
    private fun vault() = SecureCredentialStore(context, authPrefsName)

    private fun dataStoreText(): String = dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)

    private fun vaultXml(): String =
        File(context.applicationInfo.dataDir, "shared_prefs/$authPrefsName.xml").readText()

    private fun sentinel(property: String) = "SENTINELA-${property.uppercase()}-123"

    /** Valor de um campo de [Settings] pelo nome da propriedade. */
    private fun Settings.property(name: String): String =
        Settings::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as String

    /** Um valor-sentinela distinto em cada um dos segredos ([SettingsSecrets.BY_PROPERTY]) — escrito campo a campo. */
    private fun settingsWithSentinels() = Settings(
        customHeaderValue = sentinel("customHeaderValue"),
        reasoningCustomHeaderValue = sentinel("reasoningCustomHeaderValue"),
        visionCustomHeaderValue = sentinel("visionCustomHeaderValue"),
        falaCustomHeaderValue = sentinel("falaCustomHeaderValue"),
        computerSshHost = "host.claro.test",
        computerSshPassword = sentinel("computerSshPassword"),
        // Uma chave privada de verdade tem várias linhas: o sentinela está no meio do bloco.
        computerSshKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n${sentinel("computerSshKey")}\n-----END OPENSSH PRIVATE KEY-----",
    )

    private fun targetSecretKey(key: String) = when (key) {
        SettingsSecrets.AUTH_HEADER_VALUE -> SettingsCredentialKeys.CUSTOM_HEADER
        SettingsSecrets.REASONING_CUSTOM_HEADER_VALUE -> SettingsCredentialKeys.REASONING_CUSTOM_HEADER
        SettingsSecrets.VISION_CUSTOM_HEADER_VALUE -> SettingsCredentialKeys.VISION_CUSTOM_HEADER
        SettingsSecrets.FALA_CUSTOM_HEADER_VALUE -> SettingsCredentialKeys.FALA_CUSTOM_HEADER
        SettingsSecrets.COMPUTER_SSH_PASSWORD -> SettingsCredentialKeys.COMPUTER_SSH_PASSWORD
        SettingsSecrets.COMPUTER_SSH_KEY -> SettingsCredentialKeys.COMPUTER_SSH_KEY
        else -> error("não é segredo de Settings: $key")
    }

    private fun migratedProvider(key: String) = when (key) {
        SettingsSecrets.OPENAI_TOKEN, SettingsSecrets.PROVIDER_OPENAI_API_KEY -> ProviderIds.OPENAI
        SettingsSecrets.GROQ_TOKEN, SettingsSecrets.PROVIDER_GROQ_API_KEY -> ProviderIds.GROQ
        SettingsSecrets.VISION_GOOGLE_API_KEY, SettingsSecrets.FALA_GOOGLE_API_KEY, SettingsSecrets.PROVIDER_GOOGLE_API_KEY -> ProviderIds.GOOGLE
        SettingsSecrets.VISION_ANTHROPIC_API_KEY, SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY -> ProviderIds.ANTHROPIC
        SettingsSecrets.FALA_ELEVENLABS_API_KEY -> ProviderIds.ELEVENLABS
        SettingsSecrets.PROVIDER_GROK_API_KEY -> ProviderIds.GROK
        else -> null
    }

    /** Várias chaves antigas representam o mesmo provedor; a precedência da migração é explícita. */
    private fun assertMigratedValues(legacy: Map<String, String>) {
        SettingsSecrets.LEGACY_MIGRATION_ORDER
            .mapNotNull { name -> legacy[name]?.let { value -> migratedProvider(name)?.let { it to value } } }
            .distinctBy { it.first }
            .forEach { (provider, value) ->
                assertEquals(value, (vault().get(provider) as Credential.ApiKey).value)
            }
        legacy.filterKeys { migratedProvider(it) == null }.forEach { (name, value) ->
            assertEquals(value, vault().getSecret(targetSecretKey(name)))
        }
    }

    // ── Gravação: nada em claro no arquivo do DataStore ──────────────────────

    @Test
    fun noSecretReachesTheDataStoreFileButEveryOneRoundTrips() = runTest {
        val repository = newRepository()
        val saved = settingsWithSentinels()

        repository.saveSettings(saved)

        val dataStore = dataStoreText()
        // Controle: o arquivo é o certo e a leitura dos bytes funciona — o que NÃO é segredo está lá em claro.
        assertTrue("o arquivo do DataStore deveria ter o host SSH em claro", dataStore.contains("host.claro.test"))
        assertEquals(6, SettingsSecrets.BY_PROPERTY.size)
        for (property in SettingsSecrets.BY_PROPERTY.keys) {
            assertFalse("$property está em claro no DataStore", dataStore.contains(sentinel(property)))
        }
        // As chaves antigas tampouco existem lá, nem vazias: nada de `provider_anthropic_api_key` no arquivo.
        for (legacy in SettingsSecrets.LEGACY_IN_DATASTORE) {
            assertFalse("a chave ${legacy.name} ainda está no DataStore", dataStore.contains(legacy.name))
        }
        // E o cofre não guarda texto claro (cifra valor e nome da chave).
        val vaultXml = vaultXml()
        for (property in SettingsSecrets.BY_PROPERTY.keys) {
            assertFalse("$property está em claro no cofre", vaultXml.contains(sentinel(property)))
        }

        // Ida e volta pelo repositório; e por uma instância nova, para provar que o cofre persistiu.
        assertEquals(saved, repository.settingsFlow().first())
        assertEquals(saved, newRepository().settingsFlow().first())
        for ((property, vaultKey) in SettingsSecrets.BY_PROPERTY) {
            assertEquals(saved.property(property), vault().getSecret(targetSecretKey(vaultKey)))
        }
    }

    @Test
    fun clearingASecretClearsItInTheVaultToo() = runTest {
        val repository = newRepository()
        repository.saveSettings(settingsWithSentinels())

        repository.saveSettings(repository.settingsFlow().first().copy(computerSshKey = ""))

        val read = repository.settingsFlow().first()
        assertEquals("", read.computerSshKey)
        assertEquals(sentinel("computerSshPassword"), read.computerSshPassword)
    }

    // ── Migração de quem já gravou em claro ───────────────────────────────────

    /** O DataStore como um build de desenvolvimento antigo o deixou: segredos em claro ao lado de config comum. */
    private suspend fun seedLegacyDataStore(repository: SettingsRepository): Map<String, String> {
        val legacy = SettingsSecrets.LEGACY_IN_DATASTORE.associate { it.name to "SENTINELA-ANTIGO-${it.name}" }
        repository.replaceRawPreferences(
            mutablePreferencesOf(
                *legacy.map { (name, value) -> stringPreferencesKey(name) to value }.toTypedArray(),
                stringPreferencesKey("computer_ssh_host") to "host.antigo.test",
            ),
        )
        // Controle: o arquivo realmente tinha todos em claro antes da migração.
        val before = dataStoreText()
        legacy.values.forEach { assertTrue("semeadura: $it deveria estar no arquivo", before.contains(it)) }
        return legacy
    }

    @Test
    fun readingMovesTheOldDataStoreSecretsToTheVaultAndRemovesThemFromTheFile() = runTest {
        val repository = newRepository()
        val legacy = seedLegacyDataStore(repository)
        val propertyOf = SettingsSecrets.BY_PROPERTY.entries.associate { it.value to it.key }

        val read = repository.settingsFlow().first()

        // A tela continua vendo os valores...
        legacy.filterKeys { it in propertyOf }.forEach { (name, value) -> assertEquals(value, read.property(propertyOf.getValue(name))) }
        // ...que agora estão no cofre...
        assertMigratedValues(legacy)
        // ...e saíram do arquivo em claro, sem levar junto o que não é segredo.
        val after = dataStoreText()
        legacy.forEach { (name, value) ->
            assertFalse("$name ainda está no DataStore", after.contains(value) || after.contains(name))
        }
        assertTrue(after.contains("host.antigo.test"))
        assertEquals("host.antigo.test", read.computerSshHost)

        // Idempotente: ler de novo não muda nada, nem o cofre nem o arquivo.
        val bytesAfter = dataStoreFile.readBytes()
        assertEquals(read, repository.settingsFlow().first())
        assertEquals(read, newRepository().settingsFlow().first())
        assertTrue(bytesAfter.contentEquals(dataStoreFile.readBytes()))
        assertMigratedValues(legacy)
    }

    @Test
    fun readingMovesTheOldSecurePrefsKeysToTheAuthVault() = runTest {
        val repository = newRepository()
        val old = "SENTINELA-SECURE-ANTHROPIC"
        legacyVault().edit().putString(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY, old).commit()

        repository.settingsFlow().first()

        assertEquals(old, (vault().get(ProviderIds.ANTHROPIC) as Credential.ApiKey).value)
        assertFalse(legacyVault().contains(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY))
    }

    @Test
    fun theTestHookThatSnapshotsTheDataStoreNeverSeesAnOldSecretEither() = runTest {
        val repository = newRepository()
        val legacy = seedLegacyDataStore(repository)

        val raw = repository.rawPreferencesOnce()

        // `rawPreferencesOnce` alimenta o arquivo de snapshot do gate (em `files/`, no backup): sem segredo em claro.
        val names = raw.asMap().keys.map { it.name }
        legacy.keys.forEach { assertFalse("$it ficou no snapshot", it in names) }
        assertTrue("computer_ssh_host" in names)
        assertMigratedValues(legacy)
    }

    @Test
    fun aValueAlreadyInTheVaultWinsOverTheOldOneAndTheOldOneStillLeavesTheFile() = runTest {
        val repository = newRepository()
        seedLegacyDataStore(repository)
        vault().put(ProviderIds.ANTHROPIC, Credential.ApiKey("SENTINELA-NO-COFRE"))

        val read = repository.settingsFlow().first()

        assertEquals("SENTINELA-NO-COFRE", (vault().get(ProviderIds.ANTHROPIC) as Credential.ApiKey).value)
        assertFalse(dataStoreText().contains("SENTINELA-ANTIGO-provider_anthropic_api_key"))
    }

    @Test
    fun anOAuthAlreadyInTheVaultWinsOverEveryLegacyApiKeyForThatProvider() = runTest {
        val repository = newRepository()
        seedLegacyDataStore(repository)
        val existing = Credential.OAuth(
            accessToken = "oauth-access",
            refreshToken = "oauth-refresh",
            expiresAtMs = Long.MAX_VALUE,
            earliestRefreshAtMs = null,
            scopes = emptySet(),
            idToken = "oauth-id",
            clientId = "oauth-client",
            subject = "oauth-subject",
            account = "oauth@example.test",
        )
        vault().put(ProviderIds.GOOGLE, existing)

        repository.settingsFlow().first()

        assertEquals(existing, vault().getOAuth(ProviderIds.GOOGLE))
        assertFalse(dataStoreText().contains("SENTINELA-ANTIGO-provider_google_api_key"))
        assertFalse(dataStoreText().contains("SENTINELA-ANTIGO-vision_google_api_key"))
        assertFalse(dataStoreText().contains("SENTINELA-ANTIGO-fala_google_api_key"))
    }

    @Test
    fun everyLegacyProviderCollisionUsesProviderThenTranscriptionThenVisionThenFala() = runTest {
        val repository = newRepository()
        val legacy = seedLegacyDataStore(repository)

        repository.settingsFlow().first()

        assertMigratedValues(legacy)
        assertEquals("SENTINELA-ANTIGO-provider_openai_api_key", vault().getApiKey(ProviderIds.OPENAI)?.value)
        assertEquals("SENTINELA-ANTIGO-provider_groq_api_key", vault().getApiKey(ProviderIds.GROQ)?.value)
        assertEquals("SENTINELA-ANTIGO-provider_google_api_key", vault().getApiKey(ProviderIds.GOOGLE)?.value)
        assertEquals("SENTINELA-ANTIGO-provider_anthropic_api_key", vault().getApiKey(ProviderIds.ANTHROPIC)?.value)
    }

    @Test
    fun savingBeforeAnyReadAlsoPurgesTheOldClearTextCopies() = runTest {
        val repository = newRepository()
        seedLegacyDataStore(repository)

        // A gravação é a verdade do usuário: vale o que ela traz, e as cópias antigas em claro não sobram.
        repository.saveSecret(SettingsCredentialKeys.PROVIDER_GROQ_API_KEY, "SENTINELA-NOVA")

        val after = dataStoreText()
        SettingsSecrets.LEGACY_IN_DATASTORE.forEach {
            assertFalse("${it.name} ainda está no DataStore", after.contains(it.name) || after.contains("SENTINELA-ANTIGO-${it.name}"))
        }
        assertEquals("SENTINELA-NOVA", (vault().get(ProviderIds.GROQ) as Credential.ApiKey).value)
        newRepository().settingsFlow().first()
        assertEquals("SENTINELA-NOVA", (vault().get(ProviderIds.GROQ) as Credential.ApiKey).value)
    }

    @Test
    fun twoRepositoriesSerializeTheSameMigration() = runTest {
        val first = newRepository()
        val second = newRepository()
        seedLegacyDataStore(first)

        listOf(
            async { first.settingsFlow().first() },
            async { second.settingsFlow().first() },
        ).awaitAll()

        SettingsSecrets.LEGACY_IN_DATASTORE.forEach { key ->
            assertFalse("${key.name} ainda está no DataStore", dataStoreText().contains(key.name))
        }
        assertEquals(
            "SENTINELA-ANTIGO-provider_openai_api_key",
            vault().getApiKey(ProviderIds.OPENAI)?.value,
        )
        assertMigratedValues(SettingsSecrets.LEGACY_IN_DATASTORE.associate { it.name to "SENTINELA-ANTIGO-${it.name}" })
    }

    @Test
    fun aFailureMidMigrationLeavesEverySourceUntilTheNextRead() = runTest {
        val target = FailingCredentialStore().apply { writesBeforeFailure = 1 }
        val repository = newRepository(target)
        val legacy = seedLegacyDataStore(repository)

        repository.settingsFlow().first()

        // A primeira escrita chegou ao destino, mas a segunda falhou: nada da origem pode ter sido apagado.
        assertEquals(legacy.getValue(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY), target.get(ProviderIds.ANTHROPIC)?.let { (it as Credential.ApiKey).value })
        assertTrue(dataStoreText().contains(legacy.getValue(SettingsSecrets.PROVIDER_OPENAI_API_KEY)))
        assertTrue(dataStoreText().contains(legacy.getValue(SettingsSecrets.OPENAI_TOKEN)))

        target.writesBeforeFailure = null
        repository.settingsFlow().first()

        legacy.forEach { (name, value) ->
            assertFalse("$name ainda está no DataStore", dataStoreText().contains(name) || dataStoreText().contains(value))
        }
        assertEquals(
            legacy.getValue(SettingsSecrets.PROVIDER_OPENAI_API_KEY),
            (target.get(ProviderIds.OPENAI) as Credential.ApiKey).value,
        )
        assertEquals(
            legacy.getValue(SettingsSecrets.PROVIDER_GROQ_API_KEY),
            (target.get(ProviderIds.GROQ) as Credential.ApiKey).value,
        )
    }
}
