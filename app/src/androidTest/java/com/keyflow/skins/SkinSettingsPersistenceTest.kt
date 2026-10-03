package com.keyflow.skins

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * R2 da revisão da fase 1: "ao gravar `keyboardSkin` de dentro de uma instrumentação o valor novo aparecia na memória, mas o ARQUIVO do DataStore
 * continuava `system`/`atelie`". **Não é o app**: `pack:<id>` chega ao arquivo na hora em que `saveSettings` retorna (primeiro teste, que lê os BYTES do
 * arquivo do DataStore do app, e não o DataStore). O que desfazia a escolha era o `FactorySettingsListener` (`com.keyflow.testing`), que guarda a config
 * do dono do aparelho no início da rodada e a DEVOLVE em `testRunFinished` — então tudo que uma instrumentação grava nos ajustes some quando ela termina,
 * e quem olhava o arquivo depois da rodada via o valor antigo. O segundo teste (só com `-e skpHold <segundos>`) grava a skin e fica parado, para quem está
 * de fora ler o arquivo com `run-as` ENQUANTO a rodada está de pé (lá o arquivo tem o `pack:`) e de novo depois que ela termina (lá já voltou).
 */
class SkinSettingsPersistenceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get() = SettingsRepository(context)
    private lateinit var saved: Settings

    /** Falso só na medição: ela NÃO devolve os ajustes (quem os devolve, no fim da rodada, é o listener — é isso que se quer observar). */
    private var restore = true

    @Before
    fun setUp() {
        saved = runBlocking { repo.settingsFlow().first() }
    }

    @After
    fun tearDown() {
        if (restore) runBlocking { repo.saveSettings(saved) }
    }

    /** Os bytes do arquivo do DataStore do app (`filesDir/datastore/keyflow_settings.preferences_pb`) — protobuf com as strings em claro. */
    private fun dataStoreFileText(): String {
        val file = context.preferencesDataStoreFile("keyflow_settings")
        assertTrue("o arquivo do DataStore existe em ${file.absolutePath}", file.isFile)
        return String(file.readBytes(), Charsets.ISO_8859_1)
    }

    @Test
    fun aPackSkinIsOnTheDataStoreFileAsSoonAsSaveSettingsReturns() {
        val keyboard = SkinIds.forPack("persistencia-teclado-0000aaaa")
        val orb = SkinIds.forPack("persistencia-orbe-0000bbbb")
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardSkin = keyboard, orbSkin = orb)) }

        val onDisk = dataStoreFileText()
        assertTrue("keyboard_skin no arquivo", "keyboard_skin" in onDisk && keyboard in onDisk)
        assertTrue("orb_skin no arquivo", "orb_skin" in onDisk && orb in onDisk)
        // E o caminho de leitura leve do IME (`keyboardSkinOnce`, só DataStore) devolve o mesmo.
        assertEquals(keyboard, runBlocking { repo.keyboardSkinOnce() })
    }

    @Test
    fun theFileFollowsALaterChangeBackToTheDefaults() {
        val skin = SkinIds.forPack("persistencia-teclado-0000cccc")
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardSkin = skin)) }
        assertTrue(skin in dataStoreFileText())
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardSkin = "system", orbSkin = "atelie")) }
        assertTrue("o valor antigo saiu do arquivo", skin !in dataStoreFileText())
    }

    /** Medição (não roda nas rodadas normais): grava `pack:` e espera `-e skpHold <segundos>` para o arquivo ser lido por fora. */
    @Test
    fun holdForOutsideInspection() {
        val seconds = InstrumentationRegistry.getArguments().getString("skpHold")?.toLongOrNull()
        assumeTrue(seconds != null)
        restore = false
        runBlocking { repo.saveSettings(repo.settingsFlow().first().copy(keyboardSkin = "pack:persistencia-fora-0000dddd")) }
        android.util.Log.i("SKP", "SKP-R2 gravado: ${File(context.filesDir, "datastore")} ; esperando $seconds s")
        Thread.sleep(seconds!! * 1000)
    }
}
