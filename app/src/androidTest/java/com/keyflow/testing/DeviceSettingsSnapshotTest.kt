package com.keyflow.testing

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * O snapshot da config do dono (que a suíte zera para o estado de fábrica e devolve no fim) em arquivos
 * temporários — nunca no DataStore real, que a própria suíte já está usando em estado de fábrica.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSettingsSnapshotTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = System.nanoTime()
    private val dataStoreFile = File(context.cacheDir, "snapshot-test-$id.preferences_pb")
    private val prefsName = "snapshot-test-ui-$id"
    private val snapshotFile = File(context.cacheDir, "snapshot-test-$id.json")
    private lateinit var repo: SettingsRepository
    private val uiPrefs get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** A barra do dono sem o mic — exatamente o que quebrava a suíte no A32. */
    private val ownerBars = BarsConfig(
        mirrored = true,
        collapsed = listOf(BarDef(listOf(BarButton.Candidates, BarButton.Paste, BarButton.ToggleExpand))),
        expanded = listOf(BarDef(listOf(BarButton.Candidates, BarButton.Paste, BarButton.ToggleExpand))),
    )

    @Before
    fun setUp() {
        repo = SettingsRepository(context, dataStoreFile = dataStoreFile)
        runBlocking {
            repo.saveBarsConfig(ownerBars)
            repo.saveKeyboardLanguageOnce("en")
        }
        uiPrefs.edit().putBoolean("expanded", false).commit()
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
        snapshotFile.delete()
        context.deleteSharedPreferences(prefsName)
    }

    private fun snapshot() = DeviceSettingsSnapshot(repo, uiPrefs, snapshotFile)

    @Test
    fun factoryStateIsAFreshInstallAndTheOwnerConfigComesBackIntact() {
        val before = runBlocking { repo.rawPreferencesOnce() }

        snapshot().enterFactoryState()
        runBlocking {
            // Fábrica, mas em português: `enterFactoryState` grava só o idioma da interface (senão a 1ª abertura da
            // SetupActivity gravaria o do aparelho, e os testes conferem os textos de origem). Nada além disso.
            assertEquals(mapOf("app_language" to "pt"), repo.rawPreferencesOnce().asMap().mapKeys { it.key.name })
            assertEquals(BarsConfig.DEFAULT, repo.barsConfigOnce())
            assertEquals("pt", repo.keyboardLanguageOnce())
        }
        assertTrue(uiPrefs.all.isEmpty())
        assertTrue(snapshotFile.exists())

        assertTrue(snapshot().restoreOwnerState())
        runBlocking {
            assertEquals(before, repo.rawPreferencesOnce())
            assertEquals(ownerBars, repo.barsConfigOnce())
        }
        assertFalse(uiPrefs.getBoolean("expanded", true))
        assertFalse(snapshotFile.exists())
    }

    @Test
    fun aRunThatDiedMidwayDoesNotOverwriteTheOwnerSnapshotWithTheFactoryState() {
        snapshot().enterFactoryState()
        // Rodada seguinte, depois de a anterior cair sem chegar ao fim: o DataStore já está de fábrica.
        snapshot().enterFactoryState()

        assertTrue(snapshot().restoreOwnerState())
        runBlocking { assertEquals(ownerBars, repo.barsConfigOnce()) }
        assertFalse(uiPrefs.getBoolean("expanded", true))
    }

    @Test
    fun theOwnersKeyFlowImeIsSwappedForAnotherDuringTheRunAndComesBack() {
        val shell = FakeImeShell(
            default = KEYFLOW_IME,
            enabled = "com.samsung.android.honeyboard/.service.HoneyBoardService;3276809:$KEYFLOW_IME",
        )
        val snapshot = DeviceSettingsSnapshot(repo, uiPrefs, snapshotFile, shell = shell::run)

        snapshot.enterFactoryState()
        assertEquals("com.samsung.android.honeyboard/.service.HoneyBoardService", shell.default)

        assertTrue(snapshot.restoreOwnerState())
        assertEquals(KEYFLOW_IME, shell.default)
    }

    @Test
    fun anOwnerWhoDoesNotUseKeyFlowKeepsTheirKeyboard() {
        val shell = FakeImeShell(default = "com.other/.Ime", enabled = "com.other/.Ime:$KEYFLOW_IME")
        val snapshot = DeviceSettingsSnapshot(repo, uiPrefs, snapshotFile, shell = shell::run)

        snapshot.enterFactoryState()
        assertEquals("com.other/.Ime", shell.default)
        snapshot.restoreOwnerState()
        assertEquals("com.other/.Ime", shell.default)
    }

    /** `settings get` / `ime set` de mentira — o teste não troca o teclado do aparelho de verdade. */
    private class FakeImeShell(var default: String, private val enabled: String) {
        fun run(cmd: String): String = when {
            cmd == "settings get secure default_input_method" -> default
            cmd == "settings get secure enabled_input_methods" -> enabled
            cmd.startsWith("ime set ") -> { default = cmd.removePrefix("ime set "); "" }
            else -> ""
        }
    }

    @Test
    fun restoringWithoutASnapshotLeavesEverythingAlone() {
        assertFalse(snapshot().restoreOwnerState())
        runBlocking { assertEquals(ownerBars, repo.barsConfigOnce()) }
    }
}
