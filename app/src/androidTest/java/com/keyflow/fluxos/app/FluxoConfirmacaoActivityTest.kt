package com.keyflow.fluxos.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentController
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentTool
import com.keyflow.agent.ConfirmationGate
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.PolicyGated
import com.keyflow.agent.SecretFilter
import com.keyflow.agent.ToolContext
import com.keyflow.agent.ToolDefinition
import com.keyflow.agent.ToolExecutor
import com.keyflow.agent.ToolOutput
import com.keyflow.agent.ToolPolicy
import com.keyflow.agent.ToolPolicyConfig
import com.keyflow.agent.ToolRegistry
import com.keyflow.agent.ToolRisk
import com.keyflow.data.Settings
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A janela de confirmação do agente de um fluxo (FLX-25), com a activity de verdade por cima do app: **Permitir** continua; **Negar**,
 * voltar e sair da janela (`onStop`) negam e terminam `Cancelada`; sem a janela visível a ferramenta que pede confirmação não roda.
 * A sessão é a de produção (`PortaAgenteApp` + `SessaoDoFluxo`), com um motor roteirizado e uma ferramenta de escrita de teste.
 */
@RunWith(AndroidJUnit4::class)
class FluxoConfirmacaoActivityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    private val escopos = CopyOnWriteArrayList<CoroutineScope>()
    private val execucoes = AtomicInteger()

    private val ferramenta = object : AgentTool {
        override val name = "grava_teste"
        override val description = "Escrita de teste."
        override val inputSchema = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")
        override val risk = ToolRisk.WRITE

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            execucoes.incrementAndGet()
            return ToolOutput.ok("gravado")
        }
    }

    /** Pede a ferramenta na 1ª volta e responde "feito." na 2ª. */
    private val motor = object : AgentEngine {
        private val volta = AtomicInteger()
        override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = flow {
            if (volta.getAndIncrement() == 0) {
                emit(AgentEvent.ToolCall("c1", "grava_teste", "{}"))
                emit(AgentEvent.Done(AgentStopReason.TOOL_USE))
            } else {
                emit(AgentEvent.TextDelta("feito."))
                emit(AgentEvent.Done())
            }
        }
    }

    @Before
    fun setUp() {
        device.pressHome()
    }

    @After
    fun tearDown() {
        escopos.forEach { it.cancel() }
        device.pressHome()
    }

    private fun sessao(): SessaoDoFluxo {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { escopos += it }
        val gate = ConfirmationGate()
        val log = InMemoryActionLog()
        val executor = ToolExecutor(ToolRegistry(listOf(ferramenta)), ToolPolicy({ ToolPolicyConfig() }, onAlwaysAllow = {}), gate, SecretFilter(), log)
        val controller = AgentController(escopo, executor, gate, log)
        return SessaoDoFluxo(controller, gate, { "Gravar (teste)" }) { escopo.cancel() }
    }

    private fun porta(abrir: () -> Boolean = { FluxoConfirmacaoActivity.abrir(context) }) =
        PortaAgenteApp({ Settings() }, { AgentBackend.WithTools(motor) }, ::sessao, abrir) { "Fluxo de teste" }

    private fun esperarJanela(): Boolean = device.wait(Until.hasObject(By.text("Permitir")), 20_000)

    @Test
    fun allowLetsTheToolRunAndTheStepFinishes() = runBlocking {
        val etapa = async(Dispatchers.Default) { porta().executar(Etapa.Agente(), Valor.Texto("grave algo")) }
        assertTrue("a janela de confirmação não apareceu", esperarJanela())
        assertNotNull("o título da janela nomeia o fluxo", device.findObject(By.textContains("Fluxo de teste")))
        assertNotNull("o rótulo da ferramenta aparece", device.findObject(By.textContains("Gravar (teste)")))
        device.findObject(By.text("Permitir")).click()
        val r = withTimeout(60_000) { etapa.await() }
        assertEquals(ResultadoEtapa.Ok(Valor.Texto("feito.")), r)
        assertEquals(1, execucoes.get())
        assertTrue("a janela devia fechar", device.wait(Until.gone(By.text("Permitir")), 10_000))
    }

    @Test
    fun denyCancelsTheStepWithoutRunningTheTool() = runBlocking {
        val etapa = async(Dispatchers.Default) { porta().executar(Etapa.Agente(), Valor.Texto("grave algo")) }
        assertTrue(esperarJanela())
        device.findObject(By.text("Negar")).click()
        assertEquals(ResultadoEtapa.Cancelada, withTimeout(60_000) { etapa.await() })
        assertEquals(0, execucoes.get())
    }

    @Test
    fun backCancelsTheStepWithoutRunningTheTool() = runBlocking {
        val etapa = async(Dispatchers.Default) { porta().executar(Etapa.Agente(), Valor.Texto("grave algo")) }
        assertTrue(esperarJanela())
        device.pressBack()
        assertEquals(ResultadoEtapa.Cancelada, withTimeout(60_000) { etapa.await() })
        assertEquals(0, execucoes.get())
    }

    @Test
    fun leavingTheWindowWithoutAnswerDenies() = runBlocking {
        val etapa = async(Dispatchers.Default) { porta().executar(Etapa.Agente(), Valor.Texto("grave algo")) }
        assertTrue(esperarJanela())
        device.pressHome() // a janela deixa de aparecer: onStop sem resposta
        assertEquals(ResultadoEtapa.Cancelada, withTimeout(60_000) { etapa.await() })
        assertEquals(0, execucoes.get())
    }

    @Test
    fun withoutAVisibleWindowNothingThatAsksForConfirmationRuns() = runBlocking {
        // A "janela" é aberta de mentira (nada aparece): passado o prazo, o pedido é negado e a ferramenta nunca roda.
        val etapa = async(Dispatchers.Default) { porta(abrir = { true }).executar(Etapa.Agente(), Valor.Texto("grave algo")) }
        assertEquals(ResultadoEtapa.Cancelada, withTimeout(PortaAgenteApp.PRAZO_DA_JANELA_MS + 30_000) { etapa.await() })
        assertEquals(0, execucoes.get())
    }

    @Test
    fun theActivityClosesByItselfWhenThereIsNoActiveSession() {
        assertTrue(FluxoConfirmacaoActivity.abrir(context))
        assertTrue("sem sessão ativa a janela não fica", device.wait(Until.gone(By.text("Permitir")), 5_000))
    }
}
