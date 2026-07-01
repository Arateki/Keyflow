package com.keyflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.core.TranscriptionSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var dataStoreFile: File
    private lateinit var securePrefsName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dataStoreFile = File(context.filesDir, "test-datastore/$id.preferences_pb")
        securePrefsName = "test_keyflow_secure_$id"
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

    @Test
    fun saveThenReadPreservesConfig() = runTest {
        val repository = SettingsRepository(
            context = context,
            dataStoreFile = dataStoreFile,
            securePrefsName = securePrefsName,
        )
        // recordingMode não entra aqui: desde o T5, Settings.effectiveConfigFor() sempre constrói o
        // Config com o default (TOGGLE) — o campo virou vestigial nesta API legada (substituída por
        // Settings.overlayRecordingMode, coberto em saveThenReadSettingsPreservesAllFields).
        val saved = Config(
            endpointUrl = "https://example.test/v1/audio/transcriptions",
            authHeaderName = "X-Api-Key",
            authHeaderValue = "Bearer keyflow-secret",
            model = "whisper-large-v3",
            language = "pt",
        )

        repository.save(saved)

        assertEquals(saved, repository.flow().first())
    }

    @Test
    fun authHeaderValueIsNotStoredAsPlaintext() = runTest {
        val repository = SettingsRepository(
            context = context,
            dataStoreFile = dataStoreFile,
            securePrefsName = securePrefsName,
        )
        val secret = "Bearer plain-text-must-not-appear"

        repository.save(
            Config(
                endpointUrl = "https://example.test/v1/audio/transcriptions",
                authHeaderValue = secret,
            )
        )

        val dataStoreBytes = dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)
        val securePrefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$securePrefsName.xml")
        val securePrefsXml = securePrefsFile.readText()

        assertFalse(dataStoreBytes.contains(secret))
        assertFalse(securePrefsXml.contains(secret))
    }

    @Test
    fun saveThenReadSettingsPreservesAllFields() = runTest {
        val repository = newRepository()
        val saved = Settings(
            source = TranscriptionSource.OPENAI,
            customEndpoint = "https://self.host/v1/audio/transcriptions",
            customHeaderName = "X-Api-Key",
            customHeaderValue = "custom-secret",
            customModel = "whisper-large-v3",
            customLanguage = "es",
            openaiToken = "sk-openai",
            openaiModel = "whisper-1",
            openaiLanguage = "pt",
            groqToken = "gsk-groq",
            groqModel = "whisper-large-v3",
            groqLanguage = "en",
            overlayRecordingMode = RecordingMode.PUSH_TO_TALK,
            overlaySide = OverlaySide.LEFT,
            orbSizePx = 250,
            themeDark = true,
            onboardingDone = true,
        )

        repository.saveSettings(saved)

        assertEquals(saved, repository.settingsFlow().first())
    }

    @Test
    fun switchingPresetPreservesEveryProviderToken() = runTest {
        val repository = newRepository()
        // Usuário configurou os 3 provedores e está com a origem OpenAI selecionada.
        repository.saveSettings(
            Settings(
                source = TranscriptionSource.OPENAI,
                customHeaderValue = "custom-key",
                openaiToken = "sk-openai",
                groqToken = "gsk-groq",
            )
        )

        val restored = repository.settingsFlow().first()
        assertEquals("sk-openai", restored.openaiToken)
        assertEquals("gsk-groq", restored.groqToken)
        assertEquals("custom-key", restored.customHeaderValue)
    }

    @Test
    fun providerTokensAreNotStoredAsPlaintext() = runTest {
        val repository = newRepository()
        val openai = "sk-openai-must-not-appear"
        val groq = "gsk-groq-must-not-appear"

        repository.saveSettings(Settings(openaiToken = openai, groqToken = groq))

        val dataStoreBytes = dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)
        val securePrefsXml =
            File(context.applicationInfo.dataDir, "shared_prefs/$securePrefsName.xml").readText()
        assertFalse(dataStoreBytes.contains(openai))
        assertFalse(dataStoreBytes.contains(groq))
        assertFalse(securePrefsXml.contains(openai))
        assertFalse(securePrefsXml.contains(groq))
    }

    @Test
    fun saveThenReadOverlayPositionPreservesCoordinates() = runTest {
        val repository = SettingsRepository(
            context = context,
            dataStoreFile = dataStoreFile,
            securePrefsName = securePrefsName,
        )
        val saved = OverlayPosition(x = 42, y = 99)

        repository.saveOverlayPosition(saved)

        assertEquals(saved, repository.overlayPositionFlow().first())
    }
}
