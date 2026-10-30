package com.keyflow.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarDef
import com.keyflow.fluxos.CodecFluxos
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.Gatilho
import com.keyflow.fluxos.PadroesFluxo
import com.keyflow.fluxos.PoliticaGatilhos
import com.keyflow.fluxos.Superficie
import com.keyflow.orbe.LugarDaOrbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.UUID

/**
 * Persistência dos fluxos (FLX-45, FLX-48..50): duas chaves próprias no DataStore `keyflow_settings`, lidas sem o cofre,
 * escritas sempre sobre o valor atual e numa só transação com as barras quando preciso. Padrão do [SettingsRepositoryTest].
 */
@RunWith(AndroidJUnit4::class)
class FluxosRepositoryTest {

    private lateinit var context: Context
    private lateinit var dataStoreFile: File
    private lateinit var securePrefsName: String
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dataStoreFile = File(context.filesDir, "test-datastore/fluxos-$id.preferences_pb")
        securePrefsName = "test_keyflow_secure_fluxos_$id"
    }

    @After
    fun tearDown() {
        escopo.cancel()
        dataStoreFile.delete()
        dataStoreFile.parentFile?.delete()
        context.deleteSharedPreferences(securePrefsName)
        context.deleteSharedPreferences("${SecureCredentialStore.PREFS_NAME}_$securePrefsName")
    }

    /** Um repositório cujo cofre explode se alguém o abrir: prova "leitura leve, sem cofre" (FLX-49). */
    private fun repositorioSemCofre() = SettingsRepository(
        context = context,
        dataStoreFile = dataStoreFile,
        securePrefsName = securePrefsName,
        scope = escopo,
        securePrefsFactory = { _, _ -> error("os fluxos não podem abrir o cofre") },
        credentialStoreFactory = { _, _ -> error("os fluxos não podem abrir o cofre") },
    )

    private fun repositorioCompleto() = SettingsRepository(context = context, dataStoreFile = dataStoreFile, securePrefsName = securePrefsName, scope = escopo)

    /** O que está gravado agora, espiado por um segundo repositório sobre o mesmo arquivo (o DataStore é um só por caminho). */
    private suspend fun prefs(): Preferences = repositorioCompleto().rawPreferencesOnce()

    /** Planta chaves no DataStore como um build antigo, uma restauração ou uma exclusão feita por fora as deixariam. */
    private suspend fun plantar(vararg chaves: Preferences.Pair<String>) {
        val repo = repositorioCompleto()
        val atual = repo.rawPreferencesOnce().toMutablePreferences()
        atual.putAll(*chaves)
        repo.replaceRawPreferences(atual)
    }

    private fun fluxo(id: String) = Fluxo(id, "Fluxo $id", etapas = listOf(Etapa.Ditar(), Etapa.Falar()))

    private val flut2 = Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2)

    private val FLOWS_JSON = stringPreferencesKey("flows_json")
    private val FLOW_TRIGGERS_JSON = stringPreferencesKey("flow_triggers_json")
    private val BARS_CONFIG_JSON = stringPreferencesKey("bars_config_json")

    private val semente get() = ConfigFluxos(PadroesFluxo.semear(), emptyList())

    @Test
    fun readingWithoutKeysIsTheSeedAndNothingIsWritten() = runBlocking {
        val repo = repositorioSemCofre()
        assertEquals(semente, repo.configFluxosOnce())
        assertEquals(semente, repo.configFluxosFlow().first())
        assertNull(prefs()[FLOWS_JSON])
        assertNull(prefs()[FLOW_TRIGGERS_JSON])
    }

    @Test
    fun theFirstChangeWritesTheWholeList() = runBlocking {
        val repo = repositorioSemCofre()
        val depois = repo.atualizarFluxos { it.copy(fluxos = it.fluxos + fluxo("novo")) }
        assertEquals(listOf("s-ouvir-responder", "s-ler-tela-colar", "s-agente-colar", "novo"), depois.fluxos.map { it.id })
        assertNotNull(prefs()[FLOWS_JSON])
        assertEquals(depois, repo.configFluxosOnce())
        assertEquals(depois, repositorioSemCofre().configFluxosOnce()) // outra instância lê o mesmo
    }

    @Test
    fun anEmptyPersistedListReadsEmpty() = runBlocking {
        val repo = repositorioSemCofre()
        repo.atualizarFluxos { it.copy(fluxos = emptyList()) }
        assertEquals(ConfigFluxos(emptyList(), emptyList()), repo.configFluxosOnce())
    }

    @Test
    fun malformedJsonReadsAsTheSeedAndTheNextChangeRepairsIt() = runBlocking {
        val repo = repositorioSemCofre()
        plantar(FLOWS_JSON to "{isto não é json", FLOW_TRIGGERS_JSON to "[[")
        assertEquals(semente, repo.configFluxosOnce())
        repo.atualizarFluxos { it }
        assertTrue(prefs()[FLOWS_JSON]!!.startsWith("{\"")) // JSON de verdade agora
        assertEquals(semente, repo.configFluxosOnce())
    }

    @Test
    fun theFlowEmitsEveryChange() = runBlocking {
        val repo = repositorioSemCofre()
        val vistos = Collections.synchronizedList(mutableListOf<ConfigFluxos>())
        val coleta = launch(Dispatchers.IO) { repo.configFluxosFlow().toList(vistos) }
        try {
            repo.atualizarFluxos { it.copy(fluxos = it.fluxos + fluxo("a")) }
            repo.atualizarFluxos { PoliticaGatilhos.ligar(it, "a", flut2) }
            withTimeout(10_000) { while (vistos.none { it.gatilhos.isNotEmpty() }) delay(20) }
        } finally {
            coleta.cancel()
        }
        assertEquals(semente, vistos.first())
        assertEquals(listOf(Gatilho(flut2, "a")), vistos.last().gatilhos)
    }

    @Test
    fun twoConcurrentChangesBothSurvive() = runBlocking {
        val repo = repositorioSemCofre()
        // Cada alteração parte do valor gravado no momento, então nenhuma sobrescreve a outra, mesmo com estado velho na mão
        // (o modal aberto enquanto a barra muda).
        (1..8).map { i ->
            async(Dispatchers.Default) { repo.atualizarFluxos { it.copy(fluxos = it.fluxos + fluxo("c$i")) } }
        }.awaitAll()
        val ids = repo.configFluxosOnce().fluxos.map { it.id }
        assertEquals((1..8).map { "c$it" }.toSet(), ids.filter { it.startsWith("c") }.toSet())
        assertEquals(3 + 8, ids.size)
    }

    @Test
    fun barsAndTriggersAreWrittenInOneTransaction() = runBlocking {
        val repo = repositorioSemCofre()
        repo.atualizarFluxos { it.copy(fluxos = it.fluxos + fluxo("a")) }
        val barrasAntes = repo.barsConfigOnce().collapsed.size
        val vistos = Collections.synchronizedList(mutableListOf<Preferences>())
        val coleta = launch(Dispatchers.IO) { repo.rawPreferencesFlow().toList(vistos) }
        try {
            repo.atualizarFluxosEBarras { config, bars ->
                PoliticaGatilhos.ligar(config, "a", Superficie.Barra("b81f02")) to
                    bars.copy(collapsed = bars.collapsed + BarDef(listOf(BarButton.Paste)))
            }
            withTimeout(10_000) { while (vistos.none { it[BARS_CONFIG_JSON] != null }) delay(20) }
        } finally {
            coleta.cancel()
        }
        // Nenhuma leitura viu as barras novas sem o gatilho novo, nem o contrário.
        for (p in vistos.toList()) {
            val temBarra = p[BARS_CONFIG_JSON] != null
            val temGatilho = p[FLOW_TRIGGERS_JSON]?.contains("bar:b81f02") == true
            assertEquals("barras e gatilho gravados em momentos diferentes", temBarra, temGatilho)
        }
        assertEquals(listOf(Gatilho(Superficie.Barra("b81f02"), "a")), repo.configFluxosOnce().gatilhos)
        assertEquals(barrasAntes + 1, repo.barsConfigOnce().collapsed.size)
    }

    @Test
    fun savingSettingsDoesNotTouchTheFlowsAndViceVersa() = runBlocking {
        val repo = repositorioCompleto()
        val config = repo.atualizarFluxos { PoliticaGatilhos.ligar(it.copy(fluxos = it.fluxos + fluxo("a")), "a", flut2) }
        val fluxosJson = prefs()[FLOWS_JSON]
        val gatilhosJson = prefs()[FLOW_TRIGGERS_JSON]

        repo.saveSettings(Settings(shortcut2Tap = OrbShortcutAction.SWITCH_IME, shortcut3Tap = OrbShortcutAction.VISION_MODE))

        assertEquals(fluxosJson, prefs()[FLOWS_JSON])
        assertEquals(gatilhosJson, prefs()[FLOW_TRIGGERS_JSON])
        assertEquals(config, repo.configFluxosOnce())

        repo.atualizarFluxos { it.copy(fluxos = it.fluxos.filter { f -> f.id != "s-ler-tela-colar" }) }
        val lidas = repo.settingsFlow().first()
        // o gatilho sombreia a ação do gesto, não a sobrescreve (D12): as Settings ficam como foram salvas
        assertEquals(OrbShortcutAction.SWITCH_IME, lidas.shortcut2Tap)
        assertEquals(OrbShortcutAction.VISION_MODE, lidas.shortcut3Tap)
    }

    @Test
    fun anOrphanTriggerIsIgnoredOnReadAndDroppedOnTheNextWrite() = runBlocking {
        val repo = repositorioSemCofre()
        repo.atualizarFluxos { it.copy(fluxos = it.fluxos + fluxo("a")) }
        // Planta um gatilho de fluxo inexistente direto no JSON (como uma exclusão feita por fora deixaria).
        plantar(FLOW_TRIGGERS_JSON to CodecFluxos.codificarGatilhos(listOf(Gatilho(flut2, "a"), Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 3), "sumiu"))))
        assertEquals(2, repo.configFluxosOnce().gatilhos.size) // a leitura só observa: o órfão continua lá
        repo.atualizarFluxos { it }
        assertEquals(listOf(Gatilho(flut2, "a")), repo.configFluxosOnce().gatilhos) // a gravação seguinte o remove
    }

    @Test
    fun excludingAFlowDropsItsTriggersInTheSameWrite() = runBlocking {
        val repo = repositorioSemCofre()
        repo.atualizarFluxos { PoliticaGatilhos.ligar(it.copy(fluxos = it.fluxos + fluxo("a")), "a", flut2) }
        repo.atualizarFluxos { it.copy(fluxos = it.fluxos.filter { f -> f.id != "a" }) }
        assertTrue(repo.configFluxosOnce().gatilhos.isEmpty())
    }

    @Test
    fun anUnchangedUpdateDoesNotRewriteTheKeys() = runBlocking {
        val repo = repositorioSemCofre()
        repo.atualizarFluxos { it.copy(fluxos = it.fluxos + fluxo("a")) }
        val vistos = Collections.synchronizedList(mutableListOf<Preferences>())
        val coleta = launch(Dispatchers.IO) { repo.rawPreferencesFlow().toList(vistos) }
        try {
            withTimeout(10_000) { while (vistos.isEmpty()) delay(20) }
            repo.atualizarFluxos { it }
            delay(300)
        } finally {
            coleta.cancel()
        }
        assertEquals("regravou sem mudança", 1, vistos.size) // só a leitura inicial
    }
}
