package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.auth.ConnectionState
import com.keyflow.bancada.adaptadores.BancadaServicos
import com.keyflow.bancada.armazenamento.RepositorioDeExecucoes
import com.keyflow.bancada.executor.BancadaRunner
import com.keyflow.bancada.executor.EstadoBancada
import com.keyflow.bancada.executor.ImagemDoCorpus
import com.keyflow.bancada.executor.MotorDeImagem
import com.keyflow.bancada.executor.MotorDeCapacidade
import com.keyflow.bancada.resultado.Aparelho
import com.keyflow.bancada.resultado.AppInfo
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.ui.theme.KeyFlowTheme
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BancadaVisaoAreaTest {
    @get:Rule val rule = createComposeRule()
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val corpus = BancadaServicos.carregarCorpus(contexto)!!
    private lateinit var pasta: File
    private lateinit var servicos: BancadaServicos
    private val vistas = ConcurrentLinkedQueue<String>()
    private val motor = MotorDeImagem { imagem, _ -> flow {
        val caminho = (imagem as ImagemDoCorpus).caminho
        vistas.add(caminho)
        val caso = corpus.testes.values.flatMap { it.casos }.first { it.texto("imagem") == caminho }
        emit(ReasoningEvent.Delta(caso.esperado.toString()))
        emit(ReasoningEvent.Done)
    } }

    @Before fun iniciar() {
        pasta = File(contexto.cacheDir, "bancada-visao-teste-${System.nanoTime()}")
        val repo = RepositorioDeExecucoes(pasta)
        servicos = BancadaServicos(repo, BancadaRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { repo.gravar(it) },
            { corpus }, { Aparelho("teste", "emulador", null, "x86_64", 4000, 34) }, { AppInfo("teste", 1, "debug", null) })
    }

    @After fun limpar() { servicos.runner.parar(); pasta.deleteRecursively() }

    private fun mostrar(settings: Settings, models: LocalModelsUi, escolhido: MotorDeCapacidade = motor) {
        val connection = ConnectionState.Disconnected
        val modelo = modeloDeVisao(settings, models, connection)!!
        rule.setContent { KeyFlowTheme(dark = false) {
            CompositionLocalProvider(LocalBancadaServicos provides servicos) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    BancadaVisaoArea(settings, models, VisionTestAvailability.Ready, connection, escolhido,
                        preparar = { escolhido to modelo }) { Text("Teste rápido") }
                }
            }
        } }
    }

    private fun local(id: String): LocalModelsUi = LocalModelsUi.compose(
        catalog = listOf(if (id == ModelCatalog.OCR_PADDLE_LATIN.id) ModelCatalog.OCR_PADDLE_LATIN else ModelCatalog.GEMMA_4_E2B),
        installed = setOf(id), active = null, failures = emptyMap(), selectedId = id, deviceRamMb = 8000,
    )

    @Test fun ocrRodaComAsImagensDoCorpusEObjetosFicaIndisponivelNoPaddle() {
        val id = ModelCatalog.OCR_PADDLE_LATIN.id
        mostrar(Settings(visionSource = VisionSource.LOCAL, visionLocalModelId = id), local(id))
        rule.onNodeWithTag(bancadaSubAbaTag("objetos")).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(bancadaSubAbaTag("ocr")).performScrollTo().performClick()
        rule.onNodeWithTag(BANCADA_RODAR_TAG).performScrollTo().performClick()
        rule.waitUntil(30_000) { servicos.runner.estado.value is EstadoBancada.Terminou }
        val execucao = (servicos.runner.estado.value as EstadoBancada.Terminou).execucao
        assertEquals(1.0, execucao.testes.single().nota!!, 0.0)
        assertEquals(10, execucao.testes.single().n)
        assertTrue(vistas.all { it.startsWith("imagens/ocr-") })
    }

    @Test fun objetosNaNuvemPedeConfirmacaoAntesDeEnviarImagens() {
        val settings = Settings(visionSource = VisionSource.CUSTOM, visionCustomEndpoint = "https://example.test/v1", visionCustomModel = "fake")
        mostrar(settings, LocalModelsUi.EMPTY_VISION)
        rule.onNodeWithTag(bancadaSubAbaTag("objetos")).performScrollTo().performClick()
        rule.onNodeWithTag(BANCADA_RODAR_TAG).performScrollTo().performClick()
        assertTrue(vistas.isEmpty())
        rule.onNodeWithTag(BANCADA_CUSTO_CONFIRMAR_TAG).performClick()
        rule.waitUntil(30_000) { servicos.runner.estado.value is EstadoBancada.Terminou }
        assertEquals(10, vistas.size)
        assertTrue(vistas.all { it.startsWith("imagens/objetos-") })
        assertEquals(1.0, (servicos.runner.estado.value as EstadoBancada.Terminou).execucao.testes.single().nota!!, 0.0)
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(BANCADA_NOTA_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(BANCADA_NOTA_TAG).performScrollTo().assertTextContains(casas(1.0), substring = true)
    }

    @Test fun pararCancelaAEtapaDeImagemEmAndamento() {
        val entrou = AtomicBoolean(false)
        val cancelou = AtomicBoolean(false)
        val lento = MotorDeImagem { _, _ -> flow {
            try { entrou.set(true); awaitCancellation() } finally { cancelou.set(true) }
        } }
        val id = ModelCatalog.GEMMA_4_E2B.id
        mostrar(Settings(visionSource = VisionSource.LOCAL, visionLocalModelId = id), local(id), lento)
        rule.onNodeWithTag(bancadaSubAbaTag("objetos")).performScrollTo().performClick()
        rule.onNodeWithTag(BANCADA_RODAR_TAG).performScrollTo().performClick()
        rule.waitUntil(10_000) { entrou.get() }
        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        rule.waitUntil(10_000) { cancelou.get() }
        rule.waitUntil(10_000) { servicos.runner.estado.value is EstadoBancada.Terminou }
        assertEquals(com.keyflow.bancada.resultado.MotivoIncompleta.CANCELADA,
            (servicos.runner.estado.value as EstadoBancada.Terminou).execucao.motivo)
    }
}
