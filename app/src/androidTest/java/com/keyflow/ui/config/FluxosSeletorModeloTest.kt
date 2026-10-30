package com.keyflow.ui.config

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.keyflow.fluxos.*
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** O seletor de modelo por etapa, aberto pelo modal, com as opções que a fonte (as abas) oferece. */
@RunWith(AndroidJUnit4::class)
class FluxosSeletorModeloTest {
    @get:Rule val rule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var idioma: AppLanguage? = null
    private val local = Settings(source = TranscriptionSource.LOCAL, reasoningSource = ReasoningSource.LOCAL,
        visionSource = VisionSource.LOCAL, falaSource = FalaSource.SYSTEM)
    private val gemma = ModeloOferecido("gemma", "Gemma 4 E2B")
    private val qwen = ModeloOferecido("qwen", "Qwen3 0.6B")
    private val gpt = ModeloOferecido("gpt-4o", "gpt-4o")

    @Before fun portugues() { instrumentation.runOnMainSync { idioma = AppLocale.pinned; AppLocale.pinned = AppLanguage.PT } }
    @After fun restaurarIdioma() { instrumentation.runOnMainSync { AppLocale.pinned = idioma } }

    private inner class Tela(inicial: Fluxo) {
        var fluxo by mutableStateOf(inicial)
        var instalados by mutableStateOf(listOf(gemma, qwen))
        var conectados by mutableStateOf(setOf("OPENAI"))
        var abas = mutableListOf<ConfigTab>()
        var view: View? = null
        fun abrir() = rule.setContent {
            FluxosCena(false, { view = it }) {
                FluxosModalConteudo(fluxo, EstadoExecucao.Ocioso, local, onDismiss = {}, onAlterar = { fluxo = it(fluxo) },
                    fonte = { etapa -> opcoesDeModelo(etapa, local, instalados, conectados, mapOf("OPENAI" to listOf(gpt))) },
                    onAbrirAba = { abas += it })
            }
        }
    }

    private fun fluxo(etapa: Etapa = Etapa.Perguntar()) = Fluxo("f", "Fluxo", etapas = listOf(Etapa.Ditar(), etapa, Etapa.Falar()))

    @Test fun escolherGravaOModeloERestauraDaAbaGravaNulo() {
        val tela = Tela(fluxo()); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-modelo-da-aba").assertIsDisplayed()
        rule.onNodeWithTag("fluxos-modelo-local-qwen").performClick()
        assertEquals(ModeloRef("LOCAL", "qwen"), tela.fluxo.etapas[1].modelo)
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().assertTextContains("Local · Qwen3 0.6B", substring = true)
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performClick()
        rule.onNodeWithTag("fluxos-modelo-openai-gpt-4o").performClick()
        assertEquals(ModeloRef("OPENAI", "gpt-4o"), tela.fluxo.etapas[1].modelo)
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performClick()
        rule.onNodeWithTag("fluxos-modelo-da-aba").performClick()
        assertNull(tela.fluxo.etapas[1].modelo)
    }

    @Test fun linhasTemAlvoDe48dpENaoOferecemDownload() {
        val tela = Tela(fluxo()); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-modelo-da-aba").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("fluxos-modelo-local-gemma").assertHeightIsAtLeast(48.dp)
        rule.onAllNodesWithText("Baixar").assertCountEquals(0)
    }

    @Test fun apagarOModeloEscolhidoDeixaIndisponivelSemMudarOFluxo() {
        val escolhido = fluxo(Etapa.Perguntar(modelo = ModeloRef("LOCAL", "qwen")))
        val tela = Tela(escolhido); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().assertTextContains("Qwen3 0.6B", substring = true).performClick()
        rule.onNodeWithTag("fluxos-modelo-local-qwen").assertIsDisplayed()
        rule.onNodeWithTag("fluxos-modelo-indisponivel").assertDoesNotExist()
        rule.runOnIdle { tela.instalados = listOf(gemma) }
        rule.onNodeWithTag("fluxos-modelo-local-qwen").assertDoesNotExist()
        rule.onNodeWithTag("fluxos-modelo-indisponivel").assertIsDisplayed()
        rule.onNodeWithText("Indisponível: Local · qwen").assertIsDisplayed()
        assertEquals(escolhido, tela.fluxo)
        rule.onNodeWithTag("fluxos-modal-voltar").performClick()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().assertTextContains("Indisponível", substring = true)
        assertEquals(escolhido, tela.fluxo)
    }

    @Test fun provedorDesconectadoMostraConecteNaAbaEAbreAAba() {
        val tela = Tela(fluxo()); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-modelo-conectar-raciocinio-anthropic").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Conecte na aba Raciocínio", substring = true).assertCountEquals(3)
        rule.onNodeWithTag("fluxos-modelo-conectar-raciocinio-anthropic").performClick()
        assertEquals(listOf(ConfigTab.RACIOCINIO), tela.abas)
        assertNull(tela.fluxo.etapas[1].modelo)
    }

    @Test fun semanticaDasLinhasENuvemComProvedor() {
        val tela = Tela(fluxo(Etapa.Perguntar(modelo = ModeloRef("OPENAI", "gpt-4o")))); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-modelo-openai-gpt-4o").assertIsSelected()
        rule.onNodeWithTag("fluxos-modelo-da-aba").assertIsNotSelected()
        rule.onNodeWithTag("fluxos-modelo-openai-gpt-4o").assertTextContains("Nuvem · OpenAI", substring = true)
        rule.onNodeWithTag("fluxos-modelo-conectar-raciocinio-anthropic").performScrollTo()
            .assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))
    }

    @Test fun indisponivelNaoTemRadioENotaDoAgenteDependeDaOrigem() {
        val tela = Tela(fluxo(Etapa.Agente(modelo = ModeloRef("LOCAL", "apagado")))); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-modelo-indisponivel").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.Role))
        rule.onNodeWithText("O Agente só usa ferramentas com chave de API", substring = true).assertIsDisplayed()
    }

    @Test fun notaDoAgenteSomeQuandoAOrigemTemFerramentas() {
        val nuvem = local.copy(reasoningSource = ReasoningSource.ANTHROPIC)
        rule.setContent {
            FluxosCena {
                FluxosModalConteudo(fluxo(Etapa.Agente()), EstadoExecucao.Ocioso, nuvem, onDismiss = {}, onAlterar = {})
            }
        }
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithText("O Agente só usa ferramentas com chave de API", substring = true).assertDoesNotExist()
    }

    @Test fun notasDoAgenteEDoPaddleOcr() {
        val agente = Tela(fluxo(Etapa.Agente())); agente.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithText("O Agente só usa ferramentas com chave de API", substring = true).assertIsDisplayed()
    }

    @Test fun paddleNaVisaoAvisaQueAInstrucaoESeIgnorada() {
        val tela = Tela(Fluxo("v", "Ler", etapas = listOf(Etapa.Capturar, Etapa.Visao(modelo = ModeloRef("LOCAL", OCR_PADDLE_ID)), Etapa.Inserir)))
        tela.instalados = listOf(ModeloOferecido(OCR_PADDLE_ID, "PaddleOCR")); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-modelo-nota-paddle", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun capturasDoSeletor() {
        val tela = Tela(fluxo(Etapa.Perguntar(modelo = ModeloRef("LOCAL", "apagado")))); tela.abrir()
        rule.onNodeWithTag("fluxos-etapa-1-modelo").performScrollTo().performClick()
        rule.onNodeWithTag("fluxos-modelo-indisponivel").assertIsDisplayed()
        capturaFluxos("seletor-modelo", checkNotNull(tela.view))
    }
}
