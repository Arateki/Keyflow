package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keyflow.bancada.redacao.RedatorDeSegredos
import com.keyflow.bancada.adaptadores.BancadaServicos
import com.keyflow.bancada.armazenamento.RepositorioDeExecucoes
import com.keyflow.bancada.corpus.Caso
import com.keyflow.bancada.corpus.Corpus
import com.keyflow.bancada.executor.BancadaRunner
import com.keyflow.bancada.executor.ConfigExecutor
import com.keyflow.bancada.executor.EstadoBancada
import com.keyflow.bancada.executor.ModeloEscolhido
import com.keyflow.bancada.resultado.Aparelho
import com.keyflow.bancada.resultado.AppInfo
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.ExecucaoJson
import com.keyflow.bancada.resultado.ModeloInfo
import com.keyflow.bancada.resultado.MotivoIncompleta
import com.keyflow.bancada.resultado.MotorInfo
import com.keyflow.bancada.resultado.OpcoesRegistradas
import com.keyflow.bancada.resultado.ResultadoCaso
import com.keyflow.bancada.resultado.EstadoCaso
import com.keyflow.bancada.resultado.ResultadoTeste
import com.keyflow.bancada.resultado.isoUtc
import com.keyflow.i18n.tr
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.ui.theme.KeyFlowTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BNC-T13/T14/T15 no emulador, com motor falso (a CI não roda modelo real): a faixa de sub-abas com o Rápido preservado, os motivos de uma sub-aba
 * estar desabilitada, rodar → nota → histórico, a variação só entre execuções comparáveis, execução incompleta marcada, o aviso de custo ANTES de
 * qualquer chamada à nuvem, Parar que cancela o motor, a bateria completa e o JSON exportado.
 */
@RunWith(AndroidJUnit4::class)
class BancadaTestAreaTest {

    @get:Rule
    val rule = createComposeRule()

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val corpus: Corpus = BancadaServicos.carregarCorpus(contexto)!!
    private lateinit var pasta: File
    private lateinit var servicos: BancadaServicos

    private val local = ModeloEscolhido(origem = "local", modelo = "qwen3-0.6b-litertlm", nome = "Qwen3 0.6B", sha256 = "c5f39b41", backend = "CPU")
    private val nuvem = ModeloEscolhido(origem = "openai", modelo = "gpt-4.1", nome = "gpt-4.1", backend = "nuvem")
    private val assinatura = nuvem.copy(origem = "anthropic", modelo = "claude-x", nome = "claude-x", porAssinatura = true)

    @Before
    fun montar() {
        pasta = File(contexto.cacheDir, "bancada-teste-${System.nanoTime()}")
        servicos = criarServicos()
    }

    @After
    fun limpar() {
        servicos.runner.parar()
        pasta.deleteRecursively()
    }

    private fun criarServicos(redigir: (String) -> String = { it }): BancadaServicos {
        val repositorio = RepositorioDeExecucoes(pasta, redigir = redigir)
        return BancadaServicos(
            repositorio = repositorio,
            runner = BancadaRunner(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { repositorio.gravar(it) },
            carregarCorpus = { corpus },
            aparelho = { Aparelho("samsung", "SM-A325M", null, "arm64-v8a", 3700, 33) },
            app = { AppInfo("1.0.0", 1, "debug", null) },
            configLocal = ConfigExecutor.LOCAL.copy(timeoutCasoMs = 20_000),
            configOnline = ConfigExecutor.ONLINE.copy(timeoutCasoMs = 20_000),
            redigir = redigir,
        )
    }

    /** A saída que o modelo perfeito daria a [caso]. */
    private fun perfeita(caso: Caso): String = when (val esperado = caso.esperado) {
        null, JSONObject.NULL -> """{"recusa":"fora do que o app faz"}"""
        else -> esperado.toString()
    }

    private inner class MotorFalso(private val atrasoMs: Long = 0, private val resposta: (Caso) -> String = { perfeita(it) }) : ReasoningEngine {
        val chamadas = AtomicInteger()
        @Volatile var cancelado = false

        override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = flow {
            chamadas.incrementAndGet()
            val caso = corpus.testes.values.flatMap { it.casos }.first { it.entrada == request.user }
            if (atrasoMs > 0) delay(atrasoMs)
            emit(ReasoningEvent.Delta(resposta(caso)))
            emit(ReasoningEvent.Done)
        }.onCompletion { if (it is CancellationException) cancelado = true }
    }

    private fun mostrar(resolution: ReasoningResolution, modelo: ModeloEscolhido?) {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                CompositionLocalProvider(LocalBancadaServicos provides servicos) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { BancadaTestArea(resolution, modelo) }
                }
            }
        }
    }

    private fun abrir(teste: String) {
        rule.onNodeWithTag(bancadaSubAbaTag(teste)).performScrollTo().performClick()
    }

    private fun esperarTag(tag: String, ms: Long = 20_000) {
        rule.waitUntil(ms) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun esperarFim(ms: Long = 30_000) {
        rule.waitUntil(ms) { servicos.runner.estado.value is EstadoBancada.Terminou }
    }

    private fun execucaoAnterior(
        id: String,
        nota: Double,
        estado: EstadoExecucao = EstadoExecucao.COMPLETA,
        corpusVersao: Int = 1,
        minutosAtras: Long = 60,
        modelo: ModeloEscolhido = local,
    ): Execucao = Execucao(
        id = id, iniciada = isoUtc(System.currentTimeMillis() - minutosAtras * 60_000), estado = estado,
        motivo = if (estado == EstadoExecucao.INCOMPLETA) MotivoIncompleta.CANCELADA else null,
        aparelho = Aparelho("samsung", "SM-A325M", null, "arm64-v8a", 3700, 33), app = AppInfo("1.0.0", 1, "debug", null),
        motor = MotorInfo("litert-lm", "0.16.1"), corpus = corpusVersao, perfil = "completo", modelo = modelo.info(),
        opcoes = OpcoesRegistradas(emptyMap(), setOf("temperature", "seed", "maxOutputTokens")),
        testes = listOf(
            ResultadoTeste.agregar("pontuacao", 1, false, listOf(ResultadoCaso("pt-01", EstadoCaso.OK, nota = nota, totalMs = 900, primeiroTrechoMs = 200))),
        ),
    )

    // ── a faixa ──────────────────────────────────────────────────────────────────────────

    @Test
    fun theStripHasQuickAndTheFiveTestsAndQuickIsTheTestOfToday() {
        mostrar(ReasoningResolution.Ready(MotorFalso()), local)

        rule.onNodeWithTag(BANCADA_SUBABAS_TAG).assertIsDisplayed()
        for (id in listOf("rapido", "pontuacao", "reescrita", "json", "acoes", "contexto")) rule.onNodeWithTag(bancadaSubAbaTag(id)).assertExists()
        // O Rápido é o Testar de hoje: as mesmas tags e o mesmo botão.
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertTextEquals("Testar").assertIsEnabled()
        abrir("pontuacao")
        rule.onAllNodesWithTag(REASONING_TEST_BUTTON_TAG).assertCountEquals(0)
        rule.onNodeWithTag(BANCADA_RODAR_TAG).assertTextEquals("Rodar")
        abrir("rapido")
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertTextEquals("Testar")
    }

    @Test
    fun withoutAModelEveryTestIsDisabledWithTheReasonWrittenToday() {
        mostrar(ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL), null)

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertIsNotEnabled()
        for (id in listOf("pontuacao", "reescrita", "json", "acoes", "contexto")) {
            rule.onNodeWithTag(bancadaSubAbaTag(id)).performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
        }
        abrir("json")
        rule.onNodeWithTag(BANCADA_MOTIVO_TAG).assertTextEquals(ReasoningTestMessages.unavailable(UnavailableReason.NO_LOCAL_MODEL))
        rule.onNodeWithTag(BANCADA_RODAR_TAG).assertIsNotEnabled()
    }

    @Test
    fun aWindowSmallerThanTheTestDisablesItWithTheReasonAndNeverBecomesAZero() {
        mostrar(ReasoningResolution.Ready(MotorFalso()), local.copy(contextTokens = 1_024))

        abrir("acoes")
        rule.onNodeWithTag(BANCADA_MOTIVO_TAG).assertTextContains("1024", substring = true)
        rule.onNodeWithTag(BANCADA_RODAR_TAG).assertIsNotEnabled()
        abrir("pontuacao")
        rule.onAllNodesWithTag(BANCADA_MOTIVO_TAG).assertCountEquals(0)
        rule.onNodeWithTag(BANCADA_RODAR_TAG).assertIsEnabled()
    }

    // ── rodar, nota e histórico ──────────────────────────────────────────────────────────

    @Test
    fun runningALocalTestScoresItShowsTheResultAndKeepsTheHistory() {
        val motor = MotorFalso()
        mostrar(ReasoningResolution.Ready(motor), local)
        abrir("pontuacao")

        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        esperarTag(BANCADA_NOTA_TAG)

        rule.onNodeWithTag(BANCADA_NOTA_TAG).assertTextEquals(tr("Nota %s", casas(1.0)))
        rule.onNodeWithTag(BANCADA_MEDIDAS_TAG).assertTextContains(tr("%d casos", 12), substring = true)
        rule.onNodeWithTag(BANCADA_CONTEXTO_TAG).assertTextContains("CPU", substring = true)
        rule.onNodeWithTag(BANCADA_RODAR_TAG).assertTextEquals("Rodar")
        rule.onAllNodesWithTag(BANCADA_VARIACAO_TAG).assertCountEquals(0) // primeira execução: nada a comparar
        // sem rede: o motor local é o único chamado; o histórico ficou em arquivo
        val gravadas = servicos.historico(local)
        assertEquals(1, gravadas.size)
        assertEquals(EstadoExecucao.COMPLETA, gravadas.single().estado)
        assertEquals(1, gravadas.single().corpus)
        assertEquals(12, gravadas.single().testes.single().casos.size)
        assertTrue(motor.chamadas.get() >= 12)
        // o detalhe por caso abre e traz a entrada, o esperado e a saída
        rule.onNodeWithTag(BANCADA_CASOS_TOGGLE_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(bancadaCasoTag("pt-01")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theVariationShowsOnlyAgainstTheLastComparableRun() {
        servicos.repositorio.gravar(execucaoAnterior("antes", nota = 0.5))
        mostrar(ReasoningResolution.Ready(MotorFalso()), local)
        abrir("pontuacao")
        // antes de rodar: o último resultado já é o do histórico
        esperarTag(BANCADA_NOTA_TAG)
        rule.onAllNodesWithTag(BANCADA_VARIACAO_TAG).assertCountEquals(0)

        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        rule.waitUntil(30_000) { servicos.runner.estado.value is EstadoBancada.Terminou }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(BANCADA_VARIACAO_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(BANCADA_VARIACAO_TAG).assertTextEquals(setaDe(0.5))
        rule.onNodeWithTag(BANCADA_NOTA_TAG).assertTextEquals(tr("Nota %s", casas(1.0)))
    }

    @Test
    fun aRunOfAnotherCorpusVersionIsNeverCompared() {
        servicos.repositorio.gravar(execucaoAnterior("outro-corpus", nota = 0.2, corpusVersao = 2))
        mostrar(ReasoningResolution.Ready(MotorFalso()), local)
        abrir("pontuacao")
        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        esperarFim()
        rule.waitUntil(10_000) { servicos.historico(local).size == 2 }
        esperarTag(BANCADA_HISTORICO_TAG)
        rule.onAllNodesWithTag(BANCADA_VARIACAO_TAG).assertCountEquals(0)
    }

    @Test
    fun anIncompleteRunIsMarkedAndDoesNotEnterTheComparison() {
        servicos.repositorio.gravar(execucaoAnterior("parada", nota = 0.1, estado = EstadoExecucao.INCOMPLETA))
        mostrar(ReasoningResolution.Ready(MotorFalso()), local)
        abrir("pontuacao")

        esperarTag(BANCADA_INCOMPLETA_TAG)
        rule.onNodeWithTag(BANCADA_INCOMPLETA_TAG).assertTextContains(tr("você parou a rodada"), substring = true)
        rule.onAllNodesWithTag(BANCADA_VARIACAO_TAG).assertCountEquals(0)
        rule.onNodeWithTag(bancadaHistoricoLinhaTag("parada")).assertTextContains(tr("incompleta"), substring = true)
        // uma execução nova, completa, não se compara com a incompleta
        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        esperarFim()
        rule.waitUntil(10_000) { servicos.historico(local).size == 2 }
        rule.onAllNodesWithTag(BANCADA_VARIACAO_TAG).assertCountEquals(0)
    }

    // ── nuvem: custo e Parar ─────────────────────────────────────────────────────────────

    @Test
    fun aCloudRunAsksBeforeAnyCallAndShowsNoMoneyWithoutAPrice() {
        val motor = MotorFalso()
        mostrar(ReasoningResolution.Ready(motor), nuvem)
        abrir("pontuacao")

        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        rule.onNodeWithTag(BANCADA_CUSTO_TAG).assertIsDisplayed()
        rule.onNodeWithText(tr("Faz %d chamadas com a sua credencial.", 12)).assertIsDisplayed()
        rule.onNodeWithText("(estimativa)", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("R$", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("US$", substring = true).assertCountEquals(0)
        assertEquals("nenhuma chamada antes de confirmar", 0, motor.chamadas.get())

        rule.onNodeWithTag(BANCADA_CUSTO_CANCELAR_TAG).performClick()
        rule.onAllNodesWithTag(BANCADA_CUSTO_TAG).assertCountEquals(0)
        assertEquals(0, motor.chamadas.get())
        assertFalse(servicos.runner.rodando)

        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        rule.onNodeWithTag(BANCADA_CUSTO_CONFIRMAR_TAG).performClick()
        esperarFim()
        assertEquals(12, motor.chamadas.get()) // a nuvem não repete casos para conferir determinismo
        val execucao = servicos.historico(nuvem).single()
        assertEquals("openai/gpt-4.1", execucao.modelo.id)
        assertEquals("nuvem", execucao.modelo.backend)
    }

    @Test
    fun aSubscriptionLoginWarnsAboutThePlanAndRunsTheReducedProfileFirst() {
        val motor = MotorFalso()
        mostrar(ReasoningResolution.Ready(motor), assinatura)
        abrir("pontuacao")

        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        rule.onNodeWithText(tr("Consome o limite do seu plano.")).assertIsDisplayed()
        rule.onNodeWithText(tr("Faz %d chamadas com a sua credencial.", 2)).assertIsDisplayed()
        assertEquals(0, motor.chamadas.get())
        rule.onNodeWithTag(BANCADA_CUSTO_CONFIRMAR_TAG).performClick()
        esperarFim()
        assertEquals(2, motor.chamadas.get())
        assertEquals("fumaca", servicos.historico(assinatura).single().perfil)
    }

    @Test
    fun theFullRunOfASubscriptionNeedsASecondConfirmation() {
        val motor = MotorFalso()
        mostrar(ReasoningResolution.Ready(motor), assinatura)
        abrir("pontuacao")

        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        rule.onNodeWithTag(BANCADA_CUSTO_COMPLETO_TAG).performClick()
        rule.onNodeWithText(tr("Faz %d chamadas com a sua credencial.", 12)).assertIsDisplayed()
        assertEquals(0, motor.chamadas.get())
        rule.onNodeWithTag(BANCADA_CUSTO_CONFIRMAR_TAG).performClick()
        esperarFim()
        assertEquals(12, motor.chamadas.get())
        assertEquals("completo", servicos.historico(assinatura).single().perfil)
    }

    @Test
    fun stopCancelsTheCallForRealAndKeepsWhatWasMeasuredAsIncomplete() {
        val motor = MotorFalso(atrasoMs = 400)
        mostrar(ReasoningResolution.Ready(motor), nuvem)
        abrir("pontuacao")
        rule.onNodeWithTag(BANCADA_RODAR_TAG).performClick()
        rule.onNodeWithTag(BANCADA_CUSTO_CONFIRMAR_TAG).performClick()

        esperarTag(BANCADA_PROGRESSO_TAG)
        rule.waitUntil(10_000) { servicos.historico(nuvem).any { e -> e.testes.firstOrNull()?.casos?.isNotEmpty() == true } }
        rule.onNodeWithTag(BANCADA_RODAR_TAG).assertTextEquals("Parar").performClick()

        esperarFim()
        assertTrue("Parar cancela o fluxo do motor (a chamada HTTP, no motor real)", motor.cancelado)
        val parada = servicos.historico(nuvem).first()
        assertEquals(EstadoExecucao.INCOMPLETA, parada.estado)
        assertEquals(MotivoIncompleta.CANCELADA, parada.motivo)
        assertTrue(parada.testes.single().casos.isNotEmpty())
        assertTrue(parada.testes.single().casos.size < 12)
        esperarTag(BANCADA_INCOMPLETA_TAG)
    }

    // ── bateria e exportação ─────────────────────────────────────────────────────────────

    @Test
    fun theBatteryRunsEveryApplicableTestAndTheJsonExportedValidates() {
        // Um segredo de mentira na saída do modelo não pode chegar ao arquivo exportado (BON-03).
        servicos.runner.parar()
        servicos = criarServicos(redigir = RedatorDeSegredos()::redact)
        val motor = MotorFalso(resposta = { perfeita(it) + " sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789" }) // chave fake
        mostrar(ReasoningResolution.Ready(motor), local)
        abrir("pontuacao")

        rule.onNodeWithTag(BANCADA_BATERIA_TAG).performScrollTo().performClick()
        esperarFim(120_000)

        val execucao = servicos.historico(local).single()
        assertEquals(EstadoExecucao.COMPLETA, execucao.estado)
        assertEquals(listOf("pontuacao", "reescrita", "json", "acoes", "contexto"), execucao.testes.map { it.teste })
        assertEquals(listOf(12, 10, 10, 20, 12), execucao.testes.map { it.casos.size })

        val saida = ByteArrayOutputStream()
        servicos.exportar(execucao, saida)
        val texto = saida.toString(Charsets.UTF_8.name())
        assertEquals(emptyList<String>(), ExecucaoJson.validar(texto))
        assertFalse("a chave de mentira vazou para o JSON exportado", texto.contains("sk-ant-api03"))
        assertTrue(texto.contains(RedatorDeSegredos.MARKER))
        assertEquals(execucao.id, ExecucaoJson.ler(texto).id)
        esperarTag(BANCADA_EXPORTAR_TAG)
        rule.onNodeWithTag(BANCADA_EXPORTAR_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun cancellingTheBatteryKeepsTheTestsAlreadyMeasured() {
        val motor = MotorFalso(atrasoMs = 60)
        mostrar(ReasoningResolution.Ready(motor), nuvem)
        abrir("pontuacao")
        rule.onNodeWithTag(BANCADA_BATERIA_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(BANCADA_CUSTO_CONFIRMAR_TAG).performClick()
        rule.waitUntil(30_000) { servicos.historico(nuvem).any { e -> e.testes.size >= 2 } }
        rule.onNodeWithTag(BANCADA_RODAR_TAG).assertTextEquals("Parar").performClick()
        esperarFim()

        val parcial = servicos.historico(nuvem).first()
        assertEquals(EstadoExecucao.INCOMPLETA, parcial.estado)
        assertNotNull(parcial.testes.firstOrNull { it.teste == "pontuacao" })
        assertTrue(parcial.testes.size < 5)
    }
}
