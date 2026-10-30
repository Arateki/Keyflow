package com.keyflow.ui.config

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** O modal e o editor de etapas, com os componentes reais; a persistência isolada só no teste que a pede. */
@RunWith(AndroidJUnit4::class)
class FluxosModalTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var idioma: AppLanguage? = null
    private val local = Settings(source = TranscriptionSource.LOCAL, reasoningSource = ReasoningSource.LOCAL,
        visionSource = VisionSource.LOCAL, falaSource = FalaSource.SYSTEM)

    @Before fun portugues() { instrumentation.runOnMainSync { idioma = AppLocale.pinned; AppLocale.pinned = AppLanguage.PT } }
    @After fun restaurarIdioma() { instrumentation.runOnMainSync { AppLocale.pinned = idioma } }

    private inner class Tela(inicial: Fluxo) {
        var fluxo by mutableStateOf(inicial)
        var estado by mutableStateOf<EstadoExecucao>(EstadoExecucao.Ocioso)
        var visivel by mutableStateOf(true)
        var escuro by mutableStateOf(false)
        var rodou = 0
        var view: View? = null
        fun abrir() = rule.setContent {
            FluxosCena(escuro, { view = it }) {
                if (visivel) FluxosModalConteudo(fluxo, estado, local, onDismiss = {}, onAlterar = { fluxo = it(fluxo) }, onRodar = { rodou++ })
            }
        }
    }

    private fun vazio() = Fluxo("novo", "Novo fluxo", etapas = emptyList())
    private fun tres() = Fluxo("t", "Três", etapas = listOf(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Falar()))
    private fun tipos(f: Fluxo) = f.etapas.map { it.tipo }

    @Test fun fluxoNovoMostraGuiaESoDitarECapturarHabilitadas() {
        val tela = Tela(vazio()); tela.abrir()
        rule.onNodeWithTag("fluxos-modal-guia", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("fluxos-add-etapa").performClick()
        rule.onNodeWithTag("fluxos-nova-etapa-ditar").assertIsEnabled()
        rule.onNodeWithTag("fluxos-nova-etapa-capturar").assertIsEnabled()
        listOf("visao", "perguntar", "agente", "falar", "inserir").forEach { rule.onNodeWithTag("fluxos-nova-etapa-$it").assertIsNotEnabled() }
        rule.onNodeWithTag("fluxos-nova-etapa-ditar").performClick()
        assertEquals(listOf(TipoEtapa.DITAR), tipos(tela.fluxo))
        rule.onNodeWithTag("fluxos-add-etapa").performClick()
        rule.onNodeWithTag("fluxos-nova-etapa-visao").assertIsNotEnabled()
        rule.onNodeWithText("Precisa de uma imagem antes").assertIsDisplayed()
        rule.onNodeWithTag("fluxos-nova-etapa-ditar").assertIsNotEnabled()
        rule.onNodeWithTag("fluxos-nova-etapa-perguntar").assertIsEnabled()
    }

    @Test fun setasReordenamEORemoverDeixaIncompletoComEtapaDestacada() {
        val tela = Tela(tres()); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-0-subir").assertIsNotEnabled()
        rule.onNodeWithTag("fluxos-etapa-2-descer").assertIsNotEnabled()
        rule.onNodeWithTag("fluxos-etapa-1-subir").performClick()
        assertEquals(listOf(TipoEtapa.PERGUNTAR, TipoEtapa.DITAR, TipoEtapa.FALAR), tipos(tela.fluxo))
        rule.onNodeWithTag("fluxos-modal-incompleto", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("fluxos-etapa-0-problema", useUnmergedTree = true).assertTextContains("Precisa de um texto antes", substring = true)
        rule.onNodeWithTag("fluxos-etapa-1-problema", useUnmergedTree = true).assertTextContains("Só pode ser a primeira etapa", substring = true)
        rule.onNodeWithTag("fluxos-etapa-0-descer").performClick()
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR, TipoEtapa.FALAR), tipos(tela.fluxo))
        rule.onNodeWithTag("fluxos-modal-incompleto", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun removerAEtapaDoMeioDeixaOFluxoIncompleto() {
        val tela = Tela(Fluxo("v", "Ler", etapas = listOf(Etapa.Capturar, Etapa.Visao(), Etapa.Inserir))); tela.abrir()
        rule.onNodeWithTag("fluxos-modal-incompleto", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("fluxos-etapa-1-remover").performClick()
        assertEquals(listOf(TipoEtapa.CAPTURAR, TipoEtapa.INSERIR), tipos(tela.fluxo))
        rule.onNodeWithTag("fluxos-modal-incompleto", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("fluxos-etapa-1-problema", useUnmergedTree = true).assertTextContains("Precisa de um texto antes", substring = true)
        rule.onNodeWithTag(FLUXOS_RODAR_TAG).assertIsNotEnabled()
        rule.onNodeWithTag("fluxos-rodar-motivo", useUnmergedTree = true).assertTextContains("Complete as etapas", substring = true)
    }

    @Test fun oitoEtapasDesligamOMaisEtapa() {
        val tela = Tela(Fluxo("o", "Oito", etapas = listOf<Etapa>(Etapa.Ditar()) + List(7) { Etapa.Perguntar() })); tela.abrir()
        rule.onNodeWithTag("fluxos-add-etapa").performScrollTo().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("fluxos-add-etapa-motivo", useUnmergedTree = true).assertTextContains("No máximo 8 etapas")
        assertEquals(8, tela.fluxo.etapas.size)
    }

    @Test fun nomeVazioAoSairVoltaEONovoNomeFica() {
        val tela = Tela(tres()); tela.abrir()
        rule.onNodeWithTag("fluxos-modal-nome").performClick().performTextClearance()
        rule.runOnIdle { tela.visivel = false }; rule.waitForIdle()
        assertEquals("Três", tela.fluxo.nome)
        rule.runOnIdle { tela.visivel = true }; rule.waitForIdle()
        rule.onNodeWithTag("fluxos-modal-nome").performClick().performTextReplacement("Meu fluxo")
        rule.runOnIdle { tela.visivel = false }; rule.waitForIdle()
        assertEquals("Meu fluxo", tela.fluxo.nome)
        assertEquals(listOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR, TipoEtapa.FALAR), tipos(tela.fluxo))
    }

    @Test fun nomeTemNoMaximo40Caracteres() {
        val tela = Tela(tres()); tela.abrir()
        rule.onNodeWithTag("fluxos-modal-nome").performClick().performTextReplacement("a".repeat(55))
        rule.runOnIdle { tela.visivel = false }; rule.waitForIdle()
        assertEquals(40, tela.fluxo.nome.length)
    }

    @Test fun camposDaVisaoEDoPerguntarGravamAteQuinhentos() {
        val tela = Tela(Fluxo("c", "Campos", etapas = listOf(Etapa.Ditar(), Etapa.Perguntar()))); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-0-texto").assertDoesNotExist()
        rule.onNodeWithTag("fluxos-etapa-1-texto").performScrollTo().performClick().performTextReplacement("x".repeat(520))
        rule.runOnIdle { tela.visivel = false }; rule.waitForIdle()
        assertEquals(500, (tela.fluxo.etapas[1] as Etapa.Perguntar).sistema!!.length)
    }

    @Test fun arrastarAAlcaReordena() {
        val tela = Tela(tres()); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-0-alca", useUnmergedTree = true).performScrollTo().performTouchInput {
            down(center); advanceEventTime(900); moveTo(center + Offset(0f, 420f)); advanceEventTime(100); up()
        }
        rule.waitForIdle()
        assertNotEquals(TipoEtapa.DITAR, tela.fluxo.etapas.first().tipo)
        assertEquals(setOf(TipoEtapa.DITAR, TipoEtapa.PERGUNTAR, TipoEtapa.FALAR), tipos(tela.fluxo).toSet())
    }

    @Test fun rodarAgoraChamaOHostUmaVezSemCriarAtalho() {
        val tela = Tela(tres()); tela.abrir()
        rule.onNodeWithTag(FLUXOS_RODAR_TAG).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, tela.rodou)
        assertEquals(tres(), tela.fluxo)
    }

    @Test fun rodarAgoraFicaDesligadoComOutroFluxoAtivo() {
        val tela = Tela(tres()); tela.estado = EstadoExecucao.Rodando("outro", 0, TipoEtapa.DITAR); tela.abrir()
        rule.onNodeWithTag(FLUXOS_RODAR_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("fluxos-rodar-motivo", useUnmergedTree = true).assertTextContains("Já há um fluxo em andamento")
        assertEquals(0, tela.rodou)
    }

    @Test fun osTresPadroesMostramASugestaoCertaSemMudarNada() {
        val esperado = mapOf("ouvir-responder" to "ligar em 2 toques da orbe", "ler-tela-colar" to "ligar em 3 toques da orbe",
            "agente-colar" to "adicionar um botão à barra do teclado")
        val tela = Tela(PadroesFluxo.semear().first()); tela.abrir()
        PadroesFluxo.semear().forEach { padrao ->
            rule.runOnIdle { tela.fluxo = padrao }
            rule.onNodeWithTag("fluxos-modal-sugestao", useUnmergedTree = true).assertTextEquals("Sugestão: ${esperado.getValue(padrao.padrao!!)}")
            assertEquals(padrao, tela.fluxo)
        }
    }

    @Test fun persisteNaHoraSemTocarEmGatilhos() = runBlocking {
        val context = instrumentation.targetContext
        val arquivo = File(context.filesDir, "test-datastore/modal-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = SettingsRepository(context, arquivo, "fluxos-modal-${UUID.randomUUID()}", scope,
            securePrefsFactory = { _, _ -> error("o modal abriu o cofre") }, credentialStoreFactory = { _, _ -> error("o modal abriu o cofre") })
        val fluxo = tres()
        val gatilho = Gatilho(Superficie.Orbe(LugarDaOrbe.FLUTUANTE, 2), fluxo.id)
        try {
            repo.atualizarFluxos { ConfigFluxos(listOf(fluxo, vazio()), listOf(gatilho)) }
            rule.setContent { FluxosCena { FluxosModal(fluxo.id, repo, local, onDismiss = {}) } }
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("fluxos-etapa-1-remover").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("fluxos-etapa-1-subir").performClick()
            rule.waitUntil(15_000) { runBlocking { repo.configFluxosOnce().fluxo("t")!!.etapas.first().tipo == TipoEtapa.PERGUNTAR } }
            val depois = repo.configFluxosOnce()
            assertEquals(listOf(gatilho), depois.gatilhos)
            assertEquals(vazio(), depois.fluxo("novo"))
        } finally { scope.cancel(); arquivo.delete() }
    }

    @Test fun capturasDoModal() {
        val tela = Tela(Fluxo("p", "Ouvir e responder", padrao = "ouvir-responder", etapas = PadroesFluxo.CATALOGO.first().etapas)); tela.abrir()
        rule.onNodeWithTag("fluxos-modal").assertIsDisplayed()
        capturaFluxos("modal-claro", checkNotNull(tela.view))
        rule.runOnIdle { tela.escuro = true }; rule.waitForIdle()
        capturaFluxos("modal-escuro", checkNotNull(tela.view))
        rule.runOnIdle { tela.escuro = false; tela.fluxo = tela.fluxo.copy(etapas = listOf(Etapa.Capturar, Etapa.Inserir)) }; rule.waitForIdle()
        capturaFluxos("modal-incompleto", checkNotNull(tela.view))
        rule.onNodeWithTag("fluxos-add-etapa").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-nova-etapa-ditar").assertIsDisplayed()
        capturaFluxos("modal-nova-etapa", checkNotNull(tela.view))
    }
}
