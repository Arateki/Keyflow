package com.keyflow.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.notificacao.AcaoDaOrbe
import com.keyflow.notificacao.CodecNotificacao
import com.keyflow.notificacao.ConfigNotificacao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Persistência da configuração da notificação (NTF-T3; NTF-36, NTF-38): uma chave própria no DataStore `keyflow_settings`, lida
 * sem o cofre e escrita sempre sobre o valor atual. Padrão do [FluxosRepositoryTest].
 */
@RunWith(AndroidJUnit4::class)
class NotificacaoRepositoryTest {

    private lateinit var context: Context
    private lateinit var dataStoreFile: File
    private lateinit var securePrefsName: String
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val CHAVE = stringPreferencesKey("notification_config_json")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dataStoreFile = File(context.filesDir, "test-datastore/notificacao-$id.preferences_pb")
        securePrefsName = "test_keyflow_secure_notificacao_$id"
    }

    @After
    fun tearDown() {
        escopo.cancel()
        dataStoreFile.delete()
        dataStoreFile.parentFile?.delete()
        context.deleteSharedPreferences(securePrefsName)
        context.deleteSharedPreferences("${SecureCredentialStore.PREFS_NAME}_$securePrefsName")
    }

    private fun repositorioSemCofre() = SettingsRepository(
        context = context,
        dataStoreFile = dataStoreFile,
        securePrefsName = securePrefsName,
        scope = escopo,
        securePrefsFactory = { _, _ -> error("a notificação não pode abrir o cofre") },
        credentialStoreFactory = { _, _ -> error("a notificação não pode abrir o cofre") },
    )

    private fun repositorioCompleto() = SettingsRepository(context = context, dataStoreFile = dataStoreFile, securePrefsName = securePrefsName, scope = escopo)

    private suspend fun prefs(): Preferences = repositorioCompleto().rawPreferencesOnce()

    @Test
    fun readingWithoutTheKeyIsTheDefaultsAndNothingIsWritten() = runBlocking {
        val repo = repositorioSemCofre()
        assertEquals(ConfigNotificacao(), repo.configNotificacaoOnce())
        assertEquals(ConfigNotificacao(), repo.configNotificacaoFlow().first())
        assertNull(prefs()[CHAVE])
    }

    @Test
    fun theFirstChangeWritesTheKey() = runBlocking {
        val repo = repositorioSemCofre()
        val depois = repo.atualizarNotificacao { it.copy(permitirComTelaBloqueada = true, acao1Toque = AcaoDaOrbe.Nenhuma) }
        assertEquals(true, depois.permitirComTelaBloqueada)
        assertNotNull(prefs()[CHAVE])
        assertEquals(depois, repo.configNotificacaoOnce())
        assertEquals(depois, repositorioSemCofre().configNotificacaoOnce())
        assertEquals(CodecNotificacao.codificar(depois), prefs()[CHAVE])
    }

    @Test
    fun twoConcurrentChangesAreBothKept() = runBlocking {
        val repo = repositorioSemCofre()
        listOf(
            async(Dispatchers.IO) { repo.atualizarNotificacao { it.copy(mostrar = false) } },
            async(Dispatchers.IO) { repo.atualizarNotificacao { it.copy(skin = "aero") } },
            async(Dispatchers.IO) { repo.atualizarNotificacao { it.copy(acao3Toques = AcaoDaOrbe.LerTela) } },
        ).awaitAll()
        val c = repo.configNotificacaoOnce()
        assertEquals(false, c.mostrar)
        assertEquals("aero", c.skin)
        assertEquals(AcaoDaOrbe.LerTela, c.acao3Toques)
    }

    @Test
    fun malformedJsonReadsAsDefaultsAndTheNextChangeRepairsIt() = runBlocking {
        val repo = repositorioCompleto()
        repo.replaceRawPreferences(repo.rawPreferencesOnce().toMutablePreferences().apply { this[CHAVE] = "{isto não é json" })
        assertEquals(ConfigNotificacao(), repo.configNotificacaoOnce())
        repo.atualizarNotificacao { it.copy(mostrar = false) }
        assertEquals(ConfigNotificacao(mostrar = false), repo.configNotificacaoOnce())
    }

    @Test
    fun savingTheSettingsDoesNotTouchTheKeyAndViceVersa() = runBlocking {
        val repo = repositorioCompleto()
        val mudada = repo.atualizarNotificacao { it.copy(permitirComTelaBloqueada = true, skin = "glitch") }
        val json = prefs()[CHAVE]

        // salvar as Settings inteiras não encosta na chave
        repo.saveSettings(repo.settingsFlow().first().copy(shortcut2Tap = com.keyflow.core.OrbShortcutAction.NONE))
        assertEquals(json, prefs()[CHAVE])
        assertEquals(mudada, repo.configNotificacaoOnce())

        // e alterar a notificação não encosta em nenhuma outra chave
        val antes = prefs().asMap().filterKeys { it.name != CHAVE.name }
        repo.atualizarNotificacao { it.copy(mostrar = false) }
        val depois = prefs().asMap().filterKeys { it.name != CHAVE.name }
        assertEquals(antes, depois)
    }
}
