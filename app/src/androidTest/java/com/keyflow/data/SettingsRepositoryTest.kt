package com.keyflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.RecordingMode
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

    @Test
    fun saveThenReadPreservesConfig() = runTest {
        val repository = SettingsRepository(
            context = context,
            dataStoreFile = dataStoreFile,
            securePrefsName = securePrefsName,
        )
        val saved = Config(
            endpointUrl = "https://example.test/v1/audio/transcriptions",
            authHeaderName = "X-Api-Key",
            authHeaderValue = "Bearer keyflow-secret",
            model = "whisper-large-v3",
            language = "pt",
            recordingMode = RecordingMode.PUSH_TO_TALK,
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
