package com.keyflow.fluxos.app

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentController
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentLimits
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.ConfirmationDecision
import com.keyflow.agent.ConfirmationGate
import com.keyflow.agent.FakeTool
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.InMemoryPolicyConfigStore
import com.keyflow.agent.ScriptedEngine
import com.keyflow.agent.SecretFilter
import com.keyflow.agent.ToolExecutor
import com.keyflow.agent.ToolPolicy
import com.keyflow.agent.ToolPolicyConfig
import com.keyflow.agent.ToolRegistry
import com.keyflow.agent.ToolRisk
import com.keyflow.agent.call
import com.keyflow.agent.done
import com.keyflow.agent.errorEvent
import com.keyflow.agent.text
import com.keyflow.agenthost.AgentHost
import com.keyflow.data.Settings
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloRef
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import com.keyflow.reasoning.ReasoningError
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * A etapa Agente (FLX-24, FLX-25): sessão exclusiva por execução, separada do chat da aba, e a confirmação por uma janela que, sem
 * resposta, nega e cancela. O executor de ferramentas é o de verdade; o motor, falso.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PortaAgenteAppTest {

    private val escopos = CopyOnWriteArrayList<CoroutineScope>()

    @After
    fun tearDown() {
        escopos.forEach { it.cancel() }
        SessaoAtiva.atual?.let { SessaoAtiva.limpar(it.sessao) }
    }

    private fun escopo() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { escopos += it }

    /** Um controlador com gate e executor próprios sobre o MESMO catálogo de ferramentas — como `AgentParts.novaSessao`. */
    private inner class Partes(val registry: ToolRegistry, val config: () -> ToolPolicyConfig = { ToolPolicyConfig() }, limites: AgentLimits = AgentLimits()) {
        val gate = ConfirmationGate()
        val log = InMemoryActionLog()
        val executor = ToolExecutor(registry, ToolPolicy(config, onAlwaysAllow = {}), gate, SecretFilter(), log, clock = { 1L }, dispatcher = Dispatchers.Unconfined)
        val scope = escopo()
        val controller = AgentController(scope, executor, gate, log, limites)
        var encerrada = false
        val sessao = SessaoDoFluxo(controller, gate, aoEncerrar = { encerrada = true; scope.cancel() })
    }

    private fun porta(
        backend: () -> AgentBackend,
        sessoes: () -> SessaoDoFluxo,
        abrir: () -> Boolean = { true },
        settings: () -> Settings = { Settings() },
        resolvidos: MutableList<Settings> = mutableListOf(),
    ) = PortaAgenteApp({ settings() }, { resolvidos += it; backend() }, sessoes, abrir) { "Pedir ao agente" }

    private fun ok(r: ResultadoEtapa<Valor>) = (r as ResultadoEtapa.Ok).valor
    private fun falha(r: ResultadoEtapa<Valor>) = (r as ResultadoEtapa.Falha).erro

    private val registro = ToolRegistry(emptyList())

    @Test
    fun `the final assistant answer is the step output`() = runTest {
        val partes = Partes(registro)
        val motor = ScriptedEngine(text("Pronto, ", "feito.") + done())
        val r = porta({ AgentBackend.WithTools(motor) }, { partes.sessao }).executar(Etapa.Agente(), Valor.Texto("faça isso"))
        assertThat(ok(r)).isEqualTo(Valor.Texto("Pronto, feito."))
        assertThat(partes.encerrada).isTrue() // todo fim libera a sessão
        assertThat(SessaoAtiva.atual).isNull()
    }

    @Test
    fun `the flow request carries no chat history and the chat stays intact`() = runTest {
        val registroCompartilhado = registro
        val chat = Partes(registroCompartilhado)
        val motorDoChat = ScriptedEngine(text("resposta do chat") + done())
        chat.controller.send("conversa antiga", AgentBackend.WithTools(motorDoChat))
        val antes = chat.controller.state.value.items

        val fluxo = Partes(registroCompartilhado)
        val motor = ScriptedEngine(text("ok") + done())
        porta({ AgentBackend.WithTools(motor) }, { fluxo.sessao }).executar(Etapa.Agente(), Valor.Texto("pedido do fluxo"))

        val mensagens = motor.requests.single().messages
        assertThat(mensagens).hasSize(1)
        assertThat(mensagens.single().toString()).contains("pedido do fluxo")
        assertThat(mensagens.toString()).doesNotContain("conversa antiga")
        assertThat(chat.controller.state.value.items).isEqualTo(antes) // nem lido, nem limpo, nem acrescido
        assertThat(chat.controller.historySnapshot()).hasSize(2)
    }

    @Test
    fun `a second run starts empty`() = runTest {
        val motor = ScriptedEngine(text("resposta") + done())
        val p = porta({ AgentBackend.WithTools(motor) }, { Partes(registro).sessao })
        p.executar(Etapa.Agente(), Valor.Texto("primeiro"))
        p.executar(Etapa.Agente(), Valor.Texto("segundo"))
        assertThat(motor.requests).hasSize(2)
        assertThat(motor.requests[1].messages).hasSize(1)
        assertThat(motor.requests[1].messages.toString()).doesNotContain("primeiro")
    }

    @Test
    fun `cancelling the flow cancels its agent but not the chat`() = runTest {
        val chat = Partes(registro)
        val parado = AgentBackend.WithTools(object : AgentEngine {
            override fun chat(messages: List<com.keyflow.agent.AgentMessage>, tools: List<com.keyflow.agent.ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> =
                flow { awaitCancellation() }
        })
        chat.controller.send("pergunta longa do chat", parado)
        assertThat(chat.controller.isBusy).isTrue()

        val fluxo = Partes(registro)
        val job = launch { porta({ parado }, { fluxo.sessao }).executar(Etapa.Agente(), Valor.Texto("x")) }
        runCurrent()
        assertThat(fluxo.controller.isBusy).isTrue()
        job.cancel()
        advanceUntilIdle()
        assertThat(fluxo.controller.isBusy).isFalse()
        assertThat(fluxo.encerrada).isTrue()
        assertThat(chat.controller.isBusy).isTrue() // o chat segue
    }

    @Test
    fun `gates of different sessions do not receive crossed answers`() = runTest {
        val escrita = FakeTool("grava", ToolRisk.WRITE)
        val reg = ToolRegistry(listOf(escrita))
        val chat = Partes(reg)
        val fluxo = Partes(reg)
        fun pedindo() = AgentBackend.WithTools(ScriptedEngine(listOf(call("c1", "grava"), done(AgentStopReason.TOOL_USE)), text("fim") + done()))
        chat.controller.send("a", pedindo())
        fluxo.controller.send("b", pedindo())
        val doChat = chat.gate.pending.value!!
        val doFluxo = fluxo.gate.pending.value!!
        // Mesmo callId nos dois: a resposta de um gate nunca fecha o pedido do outro.
        assertThat(doChat.callId).isEqualTo(doFluxo.callId)
        assertThat(fluxo.sessao.responder(doFluxo.callId, ConfirmationDecision.DENY)).isTrue()
        assertThat(chat.gate.pending.value).isNotNull()
        assertThat(fluxo.gate.pending.value).isNull()
    }

    @Test
    fun `a busy controller fails with the busy phrase`() = runTest {
        val partes = Partes(registro)
        val parado = AgentBackend.WithTools(object : AgentEngine {
            override fun chat(messages: List<com.keyflow.agent.AgentMessage>, tools: List<com.keyflow.agent.ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> =
                flow { awaitCancellation() }
        })
        partes.controller.send("ocupando", parado)
        val erro = falha(porta({ parado }, { partes.sessao }).executar(Etapa.Agente(), Valor.Texto("x")))
        assertThat(erro.causa).isEqualTo(CausaErro.MOTOR)
        assertThat(erro.mensagem).isEqualTo("O agente está ocupado.")
    }

    @Test
    fun `the step limit fails with the notice phrase`() = runTest {
        val ferramenta = FakeTool("le")
        val partes = Partes(ToolRegistry(listOf(ferramenta)), limites = AgentLimits(maxSteps = 1))
        val motor = ScriptedEngine(listOf(call("c1", "le"), done(AgentStopReason.TOOL_USE)))
        val erro = falha(porta({ AgentBackend.WithTools(motor) }, { partes.sessao }).executar(Etapa.Agente(), Valor.Texto("x")))
        assertThat(erro.causa).isEqualTo(CausaErro.MOTOR)
        assertThat(erro.mensagem).isEqualTo("Cheguei ao limite de passos desta mensagem. Peça para continuar, se quiser.")
    }

    @Test
    fun `an engine error becomes a typed failure and not bracket text`() = runTest {
        val partes = Partes(registro)
        val motor = ScriptedEngine(listOf(errorEvent(ReasoningError.RATE_LIMIT, "corpo cru do provedor")))
        val erro = falha(porta({ AgentBackend.WithTools(motor) }, { partes.sessao }).executar(Etapa.Agente(), Valor.Texto("x")))
        assertThat(erro.causa).isEqualTo(CausaErro.LIMITE)
        assertThat(erro.mensagem).doesNotContain("corpo cru")
    }

    @Test
    fun `an unavailable backend fails as unavailable and no session is opened`() = runTest {
        var sessoes = 0
        val erro = falha(porta({ AgentBackend.Unavailable("Credenciais do modelo não configuradas.") }, { sessoes++; Partes(registro).sessao })
            .executar(Etapa.Agente(), Valor.Texto("x")))
        assertThat(erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(erro.destino).isEqualTo(DestinoAba.RACIOCINIO)
        assertThat(sessoes).isEqualTo(0)
    }

    @Test
    fun `the step model is applied before resolving the backend and an invalid one never resolves`() = runTest {
        val vistos = mutableListOf<Settings>()
        val partes = Partes(registro)
        val motor = ScriptedEngine(text("a") + done())
        val p = porta({ AgentBackend.WithTools(motor) }, { partes.sessao }, resolvidos = vistos)
        p.executar(Etapa.Agente(modelo = ModeloRef("ANTHROPIC", "claude-x")), Valor.Texto("x"))
        assertThat(vistos.single().reasoningAnthropicModel).isEqualTo("claude-x")
        vistos.clear()
        val erro = falha(p.executar(Etapa.Agente(modelo = ModeloRef("CREDITS")), Valor.Texto("x")))
        assertThat(erro.causa).isEqualTo(CausaErro.INDISPONIVEL)
        assertThat(vistos).isEmpty()
    }

    // ---- a janela de confirmação ----

    private fun pedindoEscrita(escrita: FakeTool) =
        AgentBackend.WithTools(ScriptedEngine(listOf(call("c1", escrita.name), done(AgentStopReason.TOOL_USE)), text("terminei") + done()))

    @Test
    fun `Allow in the window lets the write tool run and the step continues`() = runTest {
        val escrita = FakeTool("grava", ToolRisk.WRITE)
        val partes = Partes(ToolRegistry(listOf(escrita)))
        val job = launch {
            val r = porta({ pedindoEscrita(escrita) }, { partes.sessao }, abrir = { true }).executar(Etapa.Agente(), Valor.Texto("x"))
            assertThat(ok(r)).isEqualTo(Valor.Texto("terminei"))
        }
        runCurrent()
        assertThat(SessaoAtiva.atual?.nomeDoFluxo).isEqualTo("Pedir ao agente")
        partes.sessao.marcarVisivel(true) // a janela apareceu
        runCurrent()
        val pedido = partes.gate.pending.value!!
        partes.sessao.responder(pedido.callId, ConfirmationDecision.ALLOW)
        advanceUntilIdle()
        assertThat(escrita.runs).hasSize(1)
        assertThat(job.isCompleted).isTrue()
    }

    @Test
    fun `Deny in the window skips the tool and the step ends cancelled`() = runTest {
        val escrita = FakeTool("grava", ToolRisk.WRITE)
        val partes = Partes(ToolRegistry(listOf(escrita)))
        var resultado: ResultadoEtapa<Valor>? = null
        launch { resultado = porta({ pedindoEscrita(escrita) }, { partes.sessao }).executar(Etapa.Agente(), Valor.Texto("x")) }
        runCurrent()
        partes.sessao.marcarVisivel(true)
        runCurrent()
        partes.sessao.responder(partes.gate.pending.value!!.callId, ConfirmationDecision.DENY)
        advanceUntilIdle()
        assertThat(resultado).isEqualTo(ResultadoEtapa.Cancelada)
        assertThat(escrita.runs).isEmpty()
    }

    @Test
    fun `leaving the window without answering denies the request`() = runTest {
        val escrita = FakeTool("grava", ToolRisk.WRITE)
        val partes = Partes(ToolRegistry(listOf(escrita)))
        var resultado: ResultadoEtapa<Valor>? = null
        launch { resultado = porta({ pedindoEscrita(escrita) }, { partes.sessao }).executar(Etapa.Agente(), Valor.Texto("x")) }
        runCurrent()
        partes.sessao.marcarVisivel(true)
        runCurrent()
        partes.sessao.marcarVisivel(false)
        partes.sessao.negarPendente() // o onStop da activity
        advanceUntilIdle()
        assertThat(resultado).isEqualTo(ResultadoEtapa.Cancelada)
        assertThat(escrita.runs).isEmpty()
    }

    @Test
    fun `with no window visible nothing that asks for confirmation runs`() = runTest {
        val escrita = FakeTool("grava", ToolRisk.WRITE)
        val partes = Partes(ToolRegistry(listOf(escrita)))
        var resultado: ResultadoEtapa<Valor>? = null
        launch { resultado = porta({ pedindoEscrita(escrita) }, { partes.sessao }, abrir = { true }).executar(Etapa.Agente(), Valor.Texto("x")) }
        runCurrent()
        assertThat(partes.gate.pending.value).isNotNull() // esperando a janela
        advanceTimeBy(PortaAgenteApp.PRAZO_DA_JANELA_MS + 1)
        advanceUntilIdle()
        assertThat(resultado).isEqualTo(ResultadoEtapa.Cancelada)
        assertThat(escrita.runs).isEmpty()
    }

    @Test
    fun `a window that cannot open denies at once`() = runTest {
        val escrita = FakeTool("grava", ToolRisk.WRITE)
        val partes = Partes(ToolRegistry(listOf(escrita)))
        var resultado: ResultadoEtapa<Valor>? = null
        launch { resultado = porta({ pedindoEscrita(escrita) }, { partes.sessao }, abrir = { false }).executar(Etapa.Agente(), Valor.Texto("x")) }
        runCurrent()
        assertThat(resultado).isEqualTo(ResultadoEtapa.Cancelada)
        assertThat(escrita.runs).isEmpty()
        assertThat(partes.gate.pending.value).isNull()
    }

    @Test
    fun `a read tool does not need the window`() = runTest {
        val leitura = FakeTool("le", ToolRisk.READ)
        val partes = Partes(ToolRegistry(listOf(leitura)))
        var janelas = 0
        val r = porta({ pedindoEscrita(leitura) }, { partes.sessao }, abrir = { janelas++; true }).executar(Etapa.Agente(), Valor.Texto("x"))
        assertThat(ok(r)).isEqualTo(Valor.Texto("terminei"))
        assertThat(janelas).isEqualTo(0)
        assertThat(leitura.runs).hasSize(1)
    }

    @Test
    fun `answers only count for the open request of the session`() = runTest {
        val partes = Partes(registro)
        assertThat(partes.sessao.responder("nenhum", ConfirmationDecision.ALLOW)).isFalse()
        assertThat(partes.sessao.negada).isFalse()
    }

    @Test
    fun `a new session shares the tool names and the policy but never the controller, the gate or the executor`() = runTest {
        val leitura = FakeTool("le")
        val policy = InMemoryPolicyConfigStore()
        val log = InMemoryActionLog()
        val chat = AgentHost.assemble(escopo(), listOf(leitura), policy, log, secrets = { emptyList() })
        val sessao = chat.novaSessao(escopo())
        assertThat(sessao.registry).isNotSameInstanceAs(chat.registry)
        assertThat(sessao.registry.all().map { it.name }).containsExactlyElementsIn(chat.registry.all().map { it.name })
        assertThat(sessao.policy).isSameInstanceAs(chat.policy)
        assertThat(sessao.actionLog).isSameInstanceAs(chat.actionLog)
        assertThat(sessao.secretFilter).isSameInstanceAs(chat.secretFilter)
        assertThat(sessao.controller).isNotSameInstanceAs(chat.controller)
        assertThat(sessao.gate).isNotSameInstanceAs(chat.gate)
        assertThat(sessao.executor).isNotSameInstanceAs(chat.executor)
        assertThat(sessao.controller.pendingConfirmation).isNotSameInstanceAs(chat.controller.pendingConfirmation)
        // A sessão nasce vazia: só a saudação, sem histórico, e o chat não ganhou nada.
        assertThat(sessao.controller.historySnapshot()).isEmpty()
        assertThat(chat.controller.historySnapshot()).isEmpty()
        // "Permitir sempre" feito na janela do fluxo vale na mesma configuração do chat.
        policy.update { it.copy(alwaysAllowed = it.alwaysAllowed + "le") }
        assertThat(sessao.policy.config.value.alwaysAllowed).contains("le")
    }
}
