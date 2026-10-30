package com.keyflow.ui.config

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.fluxos.*
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.orbe.LugarDaOrbe
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Lista real de componentes e persistência isolada: sem alterar o DataStore ou o cofre usados pelo teclado. */
@RunWith(AndroidJUnit4::class)
class FluxosPanelTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var idioma: AppLanguage? = null
    private val local = Settings(source = TranscriptionSource.LOCAL, reasoningSource = ReasoningSource.LOCAL,
        visionSource = VisionSource.LOCAL, falaSource = FalaSource.SYSTEM)
    private val semente = ConfigFluxos(PadroesFluxo.semear(), emptyList())

    @Before fun portugues() { instrumentation.runOnMainSync { idioma = AppLocale.pinned; AppLocale.pinned = AppLanguage.PT } }
    @After fun restaurarIdioma() { instrumentation.runOnMainSync { AppLocale.pinned = idioma } }

    private inner class Tela(inicial: ConfigFluxos = semente) {
        var config by mutableStateOf(inicial)
        var settings by mutableStateOf(local)
        var estado by mutableStateOf<EstadoExecucao>(EstadoExecucao.Ocioso)
        var escuro by mutableStateOf(false)
        var aberto: String? = null
        var restauracoes = 0
        var cancelamentos = 0
        var view: View? = null
        fun abrir() = rule.setContent {
            FluxosCena(escuro, { view = it }) {
                FluxosLista(config, settings, estado, onOpen = { aberto = it }, onNovo = {
                    novoFluxo(config, "Novo fluxo")?.let { config = config.copy(fluxos = config.fluxos + it); aberto = it.id }
                }, onRestaurar = { restauracoes++; config = RestauradorFluxos.restaurar(config) }, onCancelar = { cancelamentos++ })
            }
        }
    }

    @Test fun primeiraAberturaMostraOsTresPadroes() {
        val tela = Tela(); tela.abrir()
        rule.onNodeWithText("Ouvir e responder").assertIsDisplayed().performClick()
        assertEquals(semente.fluxos.first().id, tela.aberto)
        rule.onNodeWithText("Ler a tela e colar").assertIsDisplayed()
        rule.onNodeWithText("Pedir ao agente e colar").assertIsDisplayed()
        rule.onAllNodesWithText("Sem atalho").assertCountEquals(3)
        rule.onNodeWithTag("fluxos-card-${semente.fluxos.first().id}").assertHeightIsAtLeast(48.dp)
    }

    @Test fun novoRecebeNomeLivreEAbreSemAtalho() {
        val tela = Tela(semente.copy(fluxos = semente.fluxos + Fluxo("meu", "Novo fluxo", etapas = emptyList())))
        tela.abrir()
        rule.onNodeWithTag(FLUXOS_NEW_BUTTON_TAG).performScrollTo().performClick()
        rule.runOnIdle {
            val novo = tela.config.fluxos.last()
            assertEquals("Novo fluxo 2", novo.nome)
            assertEquals(novo.id, tela.aberto)
            assertTrue(novo.etapas.isEmpty())
            assertTrue(tela.config.gatilhos.isEmpty())
        }
    }

    @Test fun limiteDeVinteDesligaOCriarEMostraMotivo() {
        val tela = Tela(ConfigFluxos((1..20).map { Fluxo("f$it", "Fluxo $it", etapas = listOf(Etapa.Ditar())) }, emptyList()))
        tela.abrir()
        rule.onNodeWithTag(FLUXOS_NEW_BUTTON_TAG).performScrollTo().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Limite de 20 fluxos").performScrollTo().assertIsDisplayed()
        assertEquals(20, tela.config.fluxos.size)
    }

    @Test fun vazioOfereceCriarERestaurar() {
        val tela = Tela(ConfigFluxos(emptyList(), emptyList())); tela.abrir()
        rule.onNodeWithText("Você ainda não tem fluxos. Crie um com “+” ou restaure os padrão.").assertIsDisplayed()
        rule.onNodeWithTag(FLUXOS_NEW_BUTTON_TAG).assertIsEnabled()
        rule.onNodeWithTag("fluxos-restaurar").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun restaurarMostraPreviaERecriaSemLigarAtalhos() {
        val primeiro = semente.fluxos.first().copy(nome = "Editado", etapas = listOf(Etapa.Ditar()))
        val meu = Fluxo("meu", "Meu fluxo", etapas = listOf(Etapa.Ditar(), Etapa.Inserir))
        val gatilhos = listOf(Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), primeiro.id))
        val tela = Tela(ConfigFluxos(listOf(primeiro, meu), gatilhos)); tela.abrir()
        rule.onNodeWithTag("fluxos-restaurar").performScrollTo().performClick()
        rule.onNodeWithText("Será recriado: Ler a tela e colar, Pedir ao agente e colar").assertIsDisplayed()
        rule.onNodeWithText("Será reescrito: Editado").assertIsDisplayed()
        rule.onNodeWithText("Não muda: seus fluxos e todos os atalhos").assertIsDisplayed()
        rule.onNodeWithTag("fluxos-restaurar-confirmar").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(gatilhos, tela.config.gatilhos)
            assertTrue(tela.config.fluxos.contains(meu))
            val recriado = tela.config.fluxos.single { it.padrao == "ler-tela-colar" }
            assertFalse(tela.config.gatilhos.any { it.fluxoId == recriado.id })
            assertEquals(1, tela.restauracoes)
        }
    }

    @Test fun restaurarIgualNaoGravaENaoDizIgualQuandoFaltaEspaco() {
        val tela = Tela(); tela.abrir()
        rule.onNodeWithTag("fluxos-restaurar").performScrollTo().performClick()
        rule.onNodeWithText("Os fluxos padrão já estão como no original").assertIsDisplayed()
        rule.onNodeWithTag("fluxos-restaurar-confirmar").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("fluxos-restaurar-fechar").performClick()
        rule.runOnIdle { tela.config = ConfigFluxos((1..20).map { Fluxo("f$it", "Meu $it", etapas = emptyList()) }, emptyList()) }
        rule.onNodeWithTag("fluxos-restaurar").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-restaurar-sem-espaco", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Os fluxos padrão já estão como no original").assertDoesNotExist()
        rule.onNodeWithTag("fluxos-restaurar-confirmar").performScrollTo().assertIsNotEnabled()
        assertEquals(0, tela.restauracoes)
    }

    @Test fun seloAcompanhaSettingsEIncompletoTemTexto() {
        val tela = Tela(semente.copy(fluxos = semente.fluxos + Fluxo("vazio", "Inacabado", etapas = emptyList())))
        tela.abrir()
        rule.onNodeWithTag("fluxos-selo-${semente.fluxos.first().id}", useUnmergedTree = true).assertTextEquals("100% no aparelho")
        rule.runOnIdle { tela.settings = local.copy(reasoningSource = ReasoningSource.OPENAI) }
        rule.onNodeWithTag("fluxos-selo-${semente.fluxos.first().id}", useUnmergedTree = true).assertTextEquals("Nuvem · OpenAI")
        rule.onNodeWithTag("fluxos-incompleto-vazio", useUnmergedTree = true).performScrollTo().assertTextContains("Incompleto", substring = true)
    }

    @Test fun cardEmExecucaoOfereceCancelar() {
        val tela = Tela(); tela.estado = EstadoExecucao.Rodando(semente.fluxos.first().id, 1, TipoEtapa.PERGUNTAR); tela.abrir()
        rule.onNodeWithText("Em execução · Perguntar").assertIsDisplayed()
        rule.onNodeWithTag("fluxos-cancelar-${semente.fluxos.first().id}").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, tela.cancelamentos)
        assertNull(tela.aberto)
    }

    @Test fun painelLeDoRepositorioECriarPersisteSemAbrirCofre() = runBlocking {
        val context = instrumentation.targetContext
        val arquivo = File(context.filesDir, "test-datastore/lista-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(context, arquivo, "fluxos-lista-${UUID.randomUUID()}", scope,
            securePrefsFactory = { _, _ -> error("a lista abriu o cofre") }, credentialStoreFactory = { _, _ -> error("a lista abriu o cofre") })
        var aberto: String? = null
        try {
            rule.setContent { FluxosCena { FluxosPanel(local, repo, onOpen = { aberto = it }) } }
            rule.waitUntil(15_000) { rule.onAllNodesWithText("Ouvir e responder").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(repo.rawPreferencesOnce().asMap().isEmpty())
            rule.onNodeWithTag(FLUXOS_NEW_BUTTON_TAG).performScrollTo().performClick()
            rule.waitUntil(15_000) { aberto != null }
            assertEquals(aberto, repo.configFluxosOnce().fluxos.last().id)
            assertEquals(setOf("flows_json", "flow_triggers_json"), repo.rawPreferencesOnce().asMap().keys.map { it.name }.toSet())
        } finally { scope.cancel(); arquivo.delete() }
    }

    @Test fun capturasDaListaEmClaroEscuroEVazio() {
        val tela = Tela(); tela.abrir()
        rule.onNodeWithText("Ouvir e responder").assertIsDisplayed()
        capturaFluxos("lista-claro", checkNotNull(tela.view))
        rule.runOnIdle { tela.escuro = true }; rule.waitForIdle()
        capturaFluxos("lista-escuro", checkNotNull(tela.view))
        rule.runOnIdle { tela.escuro = false; tela.config = ConfigFluxos(emptyList(), emptyList()) }; rule.waitForIdle()
        capturaFluxos("lista-vazio", checkNotNull(tela.view))
    }
}
