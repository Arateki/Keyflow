package com.keyflow.data

import android.content.Context
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dataStoreFile = File(context.filesDir, "test-secrets-datastore/$id.preferences_pb")
        securePrefsName = "test_keyflow_secure_secrets_$id"
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
        dataStoreFile.parentFile?.delete()
        context.deleteSharedPreferences(securePrefsName)
    }

    private fun newRepository() = SettingsRepository(
        context = context,
        dataStoreFile = dataStoreFile,
        securePrefsName = securePrefsName,
    )

    /** O cofre aberto direto, para conferir o que a migração e a gravação deixaram nele. */
    private fun vault() = SecurePrefs.create(context, securePrefsName)

    private fun dataStoreText(): String = dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)

    private fun vaultXml(): String =
        File(context.applicationInfo.dataDir, "shared_prefs/$securePrefsName.xml").readText()

    private fun sentinel(property: String) = "SENTINELA-${property.uppercase()}-123"

    /** Valor de um campo de [Settings] pelo nome da propriedade. */
    private fun Settings.property(name: String): String =
        Settings::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as String

    /** Um valor-sentinela distinto em cada um dos segredos ([SettingsSecrets.BY_PROPERTY]) — escrito campo a campo. */
    private fun settingsWithSentinels() = Settings(
        customHeaderValue = sentinel("customHeaderValue"),
        openaiToken = sentinel("openaiToken"),
        groqToken = sentinel("groqToken"),
        reasoningCustomHeaderValue = sentinel("reasoningCustomHeaderValue"),
        visionGoogleApiKey = sentinel("visionGoogleApiKey"),
        visionAnthropicApiKey = sentinel("visionAnthropicApiKey"),
        visionCustomHeaderValue = sentinel("visionCustomHeaderValue"),
        falaElevenLabsApiKey = sentinel("falaElevenLabsApiKey"),
        falaGoogleApiKey = sentinel("falaGoogleApiKey"),
        falaCustomHeaderValue = sentinel("falaCustomHeaderValue"),
        computerSshHost = "host.claro.test",
        computerSshPassword = sentinel("computerSshPassword"),
        // Uma chave privada de verdade tem várias linhas: o sentinela está no meio do bloco.
        computerSshKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n${sentinel("computerSshKey")}\n-----END OPENSSH PRIVATE KEY-----",
        providerGroqApiKey = sentinel("providerGroqApiKey"),
        providerGrokApiKey = sentinel("providerGrokApiKey"),
    )

    // ── Gravação: nada em claro no arquivo do DataStore ──────────────────────

    @Test
    fun noSecretReachesTheDataStoreFileButEveryOneRoundTrips() = runTest {
        val repository = newRepository()
        val saved = settingsWithSentinels()

        repository.saveSettings(saved)

        val dataStore = dataStoreText()
        // Controle: o arquivo é o certo e a leitura dos bytes funciona — o que NÃO é segredo está lá em claro.
        assertTrue("o arquivo do DataStore deveria ter o host SSH em claro", dataStore.contains("host.claro.test"))
        assertEquals(14, SettingsSecrets.BY_PROPERTY.size)
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
            assertEquals(saved.property(property), vault().getString(vaultKey, null))
        }
    }

    @Test
    fun clearingASecretClearsItInTheVaultToo() = runTest {
        val repository = newRepository()
        repository.saveSettings(settingsWithSentinels())

        repository.saveSettings(repository.settingsFlow().first().copy(providerGrokApiKey = "", computerSshKey = ""))

        val read = repository.settingsFlow().first()
        assertEquals("", read.providerGrokApiKey)
        assertEquals("", read.computerSshKey)
        assertEquals(sentinel("providerGroqApiKey"), read.providerGroqApiKey) // os outros ficam
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
        legacy.forEach { (name, value) -> assertEquals(value, vault().getString(name, null)) }
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
        legacy.forEach { (name, value) -> assertEquals(value, vault().getString(name, null)) }
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
        legacy.forEach { (name, value) -> assertEquals(value, vault().getString(name, null)) }
    }

    @Test
    fun aValueAlreadyInTheVaultWinsOverTheOldOneAndTheOldOneStillLeavesTheFile() = runTest {
        val repository = newRepository()
        seedLegacyDataStore(repository)
        vault().edit().putString(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY, "SENTINELA-NO-COFRE").commit()

        val read = repository.settingsFlow().first()

        assertEquals("SENTINELA-NO-COFRE", vault().getString(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY, null))
        assertFalse(dataStoreText().contains("SENTINELA-ANTIGO-provider_anthropic_api_key"))
    }

    @Test
    fun savingBeforeAnyReadAlsoPurgesTheOldClearTextCopies() = runTest {
        val repository = newRepository()
        seedLegacyDataStore(repository)

        // A gravação é a verdade do usuário: vale o que ela traz, e as cópias antigas em claro não sobram.
        repository.saveSettings(Settings(providerGroqApiKey = "SENTINELA-NOVA"))

        val after = dataStoreText()
        SettingsSecrets.LEGACY_IN_DATASTORE.forEach {
            assertFalse("${it.name} ainda está no DataStore", after.contains(it.name) || after.contains("SENTINELA-ANTIGO-${it.name}"))
        }
        assertEquals("SENTINELA-NOVA", vault().getString(SettingsSecrets.PROVIDER_GROQ_API_KEY, null))
        assertEquals("SENTINELA-NOVA", newRepository().settingsFlow().first().providerGroqApiKey)
    }
}
