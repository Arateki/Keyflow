package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import com.keyflow.reasoning.ReasoningError
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Test

/**
 * AGT-01/03/05, AUI-01/02/04: o estado do chat (mensagens, cartões, painel de confirmação, orbe), o histórico e o cancelamento, na JVM, com
 * um motor falso e o executor de verdade (política, filtro, registro). Tudo no `Dispatchers.Unconfined`: a execução anda sozinha até
 * o ponto em que espera o usuário, e o teste responde dali.
 */
class AgentControllerTest {

    private val scopes = CopyOnWriteArrayList<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    /** O conjunto de verdade com o painel de verdade ([ConfirmationGate]) e um motor de mentira. */
    private inner class Env(
        tools: List<AgentTool> = emptyList(),
        config: ToolPolicyConfig = ToolPolicyConfig(),
        limits: AgentLimits = AgentLimits(),
        ready: suspend () -> Unit = {},
    ) {
        @Volatile
        var config: ToolPolicyConfig = config
        val gate = ConfirmationGate()
        val log = InMemoryActionLog()
        val grants = CopyOnWriteArrayList<String>()
        val registry = ToolRegistry(tools)
        val policy = ToolPolicy({ this.config }, onAlwaysAllow = { name ->
            grants += name
            this.config = this.config.copy(alwaysAllowed = this.config.alwaysAllowed + name)
        })
        val executor = ToolExecutor(registry, policy, gate, SecretFilter(), log, clock = { 5_000L }, dispatcher = Dispatchers.Unconfined)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val controller = AgentController(scope, executor, gate, log, limits, ready)

        val items get() = controller.state.value.items
        val cards get() = items.filterIsInstance<ChatItem.ToolCard>()
        val bubbles get() = items.filterIsInstance<ChatItem.AssistantMessage>()
        val activity get() = controller.state.value.activity
        fun withTools(engine: AgentEngine) = AgentBackend.WithTools(engine)
        fun allow(decision: ConfirmationDecision = ConfirmationDecision.ALLOW) = controller.respond(gate.pending.value!!.callId, decision)
    }

    private fun textOnly(vararg parts: String, failWith: String? = null): AgentBackend.TextOnly = AgentBackend.TextOnly { _ ->
        flow {
            parts.forEach { emit(AgentEvent.TextDelta(it)) }
            emit(if (failWith != null) AgentEvent.Error(ReasoningError.NETWORK, failWith) else AgentEvent.Done())
        }
    }

    // ── o começo ─────────────────────────────────────────────────────────────

    @Test
    fun `it starts with the greeting only, idle`() {
        val env = Env()
        assertThat(env.items).hasSize(1)
        assertThat(env.items.single()).isInstanceOf(ChatItem.Greeting::class.java)
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
        assertThat(env.controller.state.value.usage).isNull()
    }

    // ── modo só texto: o chat de hoje ────────────────────────────────────────

    @Test
    fun `text only - the user bubble, the streamed answer, and back to idle`() {
        val env = Env()
        val during = ArrayList<AgentActivity>()
        val backend = AgentBackend.TextOnly { _ -> flow { during += env.activity; emit(AgentEvent.TextDelta("Olá, ")); emit(AgentEvent.TextDelta("tudo bem?")); emit(AgentEvent.Done()) } }
        assertThat(env.controller.send("  oi  ", backend)).isTrue()
        assertThat(env.items.filterIsInstance<ChatItem.UserMessage>().single().text).isEqualTo("oi")
        assertThat(env.bubbles.single().text).isEqualTo("Olá, tudo bem?")
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
        // Durante a geração a atividade foi "pensando".
        assertThat(during).containsExactly(AgentActivity.THINKING)
    }

    @Test
    fun `text only - an error is shown in brackets after what had already arrived, like the old chat`() {
        val env = Env()
        env.controller.send("oi", textOnly("Alô", failWith = "sem conexão"))
        assertThat(env.bubbles.single().text).isEqualTo("Alô\n[sem conexão]")
        // Uma volta que falhou não entra no histórico do modelo.
        assertThat(env.controller.historySnapshot()).containsExactly(AgentMessage.User("oi"))
    }

    @Test
    fun `text only - an error without a message shows the kind name`() {
        val env = Env()
        env.controller.send("oi", AgentBackend.TextOnly { flow { emit(AgentEvent.Error(ReasoningError.RATE_LIMIT, null)) } })
        assertThat(env.bubbles.single().text).isEqualTo("\n[RATE_LIMIT]")
    }

    @Test
    fun `text only - sends the prompt alone, with no history, one call per message`() {
        val env = Env()
        val prompts = CopyOnWriteArrayList<String>()
        val backend = AgentBackend.TextOnly { prompt -> prompts += prompt; flow { emit(AgentEvent.TextDelta("ok")); emit(AgentEvent.Done()) } }
        env.controller.send("primeira", backend)
        env.controller.send("segunda", backend)
        assertThat(prompts).containsExactly("primeira", "segunda").inOrder()
    }

    @Test
    fun `an unavailable backend puts the reason in the chat and starts nothing`() {
        val env = Env()
        assertThat(env.controller.send("oi", AgentBackend.Unavailable("Nenhum modelo selecionado."))).isTrue()
        assertThat(env.bubbles.single().text).isEqualTo("Nenhum modelo selecionado.")
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
        assertThat(env.controller.historySnapshot()).isEmpty()
    }

    @Test
    fun `a blank message is ignored`() {
        val env = Env()
        assertThat(env.controller.send("   \n ", textOnly("x"))).isFalse()
        assertThat(env.items).hasSize(1)
    }

    @Test
    fun `while a run is going another message is refused`() {
        val env = Env()
        val release = CompletableDeferred<Unit>()
        val slow = AgentBackend.TextOnly { flow { emit(AgentEvent.TextDelta("a")); release.await(); emit(AgentEvent.Done()) } }
        assertThat(env.controller.send("um", slow)).isTrue()
        assertThat(env.controller.isBusy).isTrue()
        assertThat(env.controller.send("dois", slow)).isFalse()
        assertThat(env.items.filterIsInstance<ChatItem.UserMessage>()).hasSize(1)
        release.complete(Unit)
        assertThat(env.controller.isBusy).isFalse()
        assertThat(env.controller.send("dois", textOnly("b"))).isTrue()
    }

    // ── agente com ferramentas ───────────────────────────────────────────────

    @Test
    fun `the agent keeps the history and sends it back on every turn`() {
        val env = Env()
        val engine = ScriptedEngine(text("Oi, Yan.") + done(), text("Claro.") + done())
        env.controller.send("oi, sou o Yan", env.withTools(engine))
        env.controller.send("e agora?", env.withTools(engine))
        assertThat(engine.requests[0].messages).containsExactly(user("oi, sou o Yan"))
        assertThat(engine.requests[1].messages).containsExactly(user("oi, sou o Yan"), AgentMessage.Assistant("Oi, Yan."), user("e agora?")).inOrder()
        assertThat(env.bubbles.map { it.text }).containsExactly("Oi, Yan.", "Claro.").inOrder()
    }

    @Test
    fun `a read tool shows a card that goes running to done with one line of the result, with no question`() {
        val during = ArrayList<AgentActivity>()
        lateinit var env: Env
        val read = FakeTool("skin_list", onRun = { during += env.activity; ToolOutput.ok("2 skins instaladas\nlinha dois") })
        env = Env(listOf(read))
        val engine = ScriptedEngine(text("Vou olhar.") + call("c1", "skin_list") + done(AgentStopReason.TOOL_USE), text("Tem duas.") + done())
        env.controller.send("quais skins?", env.withTools(engine))

        val card = env.cards.single()
        assertThat(card.toolName).isEqualTo("skin_list")
        assertThat(card.risk).isEqualTo(ToolRisk.READ)
        assertThat(card.phase).isEqualTo(ToolCardPhase.DONE)
        assertThat(card.detail).isEqualTo("2 skins instaladas")
        assertThat(env.gate.pending.value).isNull()
        // A ordem no chat: usuário, fala do modelo, cartão, fala final.
        assertThat(env.items.drop(1).map { it::class.simpleName }).containsExactly("UserMessage", "AssistantMessage", "ToolCard", "AssistantMessage").inOrder()
        assertThat(during).containsExactly(AgentActivity.RUNNING_TOOL)
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
    }

    @Test
    fun `a write tool waits for the panel - the card shows the app summary and preview, and allow runs it`() {
        val preview = ToolPreview("image/png", byteArrayOf(1, 2, 3), "folha de contato")
        val write = FakeTool("skin_install", ToolRisk.WRITE, summary = { "Instalar a skin Neon" }, preview = preview, onRun = { ToolOutput.ok("Instalada.", undo = UndoCall("skin_remove", """{"id":"neon"}""")) })
        val env = Env(listOf(write))
        val engine = ScriptedEngine(call("c1", "skin_install", """{"text":"o modelo jura que é seguro"}""").let { listOf(it, done(AgentStopReason.TOOL_USE)) }, text("Pronto.") + done())
        env.controller.send("instale", env.withTools(engine))

        // Parou esperando o usuário.
        val request = env.controller.pendingConfirmation.value!!
        assertThat(request.summary).isEqualTo("Instalar a skin Neon")
        assertThat(request.summary).doesNotContain("jura")
        assertThat(request.canAlwaysAllow).isTrue()
        assertThat(env.activity).isEqualTo(AgentActivity.AWAITING_CONFIRMATION)
        val waiting = env.cards.single()
        assertThat(waiting.phase).isEqualTo(ToolCardPhase.AWAITING_CONFIRMATION)
        assertThat(waiting.summary).isEqualTo("Instalar a skin Neon")
        assertThat(waiting.image).isEqualTo(ToolImage("image/png", byteArrayOf(1, 2, 3)))
        assertThat(write.runs).isEmpty()
        assertThat(env.controller.isBusy).isTrue()

        assertThat(env.allow()).isTrue()
        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.DONE)
        assertThat(write.runs).hasSize(1)
        assertThat(env.controller.pendingConfirmation.value).isNull()
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
        assertThat(env.log.snapshot().single().undo).isEqualTo(UndoCall("skin_remove", """{"id":"neon"}"""))
    }

    @Test
    fun `deny - the tool does not run, the card says denied and the model is told`() {
        val write = FakeTool("w", ToolRisk.WRITE, summary = { "Mudar algo" })
        val env = Env(listOf(write))
        val engine = ScriptedEngine(listOf(call("c1", "w"), done(AgentStopReason.TOOL_USE)), text("Tudo bem, não faço.") + done())
        env.controller.send("faça", env.withTools(engine))
        env.allow(ConfirmationDecision.DENY)

        assertThat(write.runs).isEmpty()
        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.DENIED)
        assertThat(env.cards.single().detail).isNull()
        assertThat((engine.requests[1].messages.last() as AgentMessage.ToolResult).content).contains("O usuário negou")
        assertThat(env.log.snapshot().single().outcome).isEqualTo(ActionOutcome.DENIED)
    }

    @Test
    fun `allow always is offered only to a write, and a destructive tool never gets it`() {
        val write = FakeTool("w", ToolRisk.WRITE, summary = { "w" })
        val destroy = FakeTool("d", ToolRisk.DESTRUCTIVE, summary = { "d" })
        val env = Env(listOf(write, destroy))
        val engine = ScriptedEngine(listOf(call("c1", "w"), call("c2", "d"), done(AgentStopReason.TOOL_USE)), text("fim") + done())
        env.controller.send("vai", env.withTools(engine))

        assertThat(env.controller.pendingConfirmation.value!!.canAlwaysAllow).isTrue()
        env.allow(ConfirmationDecision.ALLOW_ALWAYS)
        assertThat(env.grants).containsExactly("w")
        val second = env.controller.pendingConfirmation.value!!
        assertThat(second.toolName).isEqualTo("d")
        assertThat(second.canAlwaysAllow).isFalse()
        assertThat(second.risk).isEqualTo(ToolRisk.DESTRUCTIVE)
        env.allow()
        assertThat(env.cards.map { it.phase }).containsExactly(ToolCardPhase.DONE, ToolCardPhase.DONE)
    }

    @Test
    fun `a disabled tool is a blocked card, and nothing is asked`() {
        val write = FakeTool("w", ToolRisk.WRITE)
        val env = Env(listOf(write), ToolPolicyConfig(disabledTools = setOf("w")))
        val engine = ScriptedEngine(listOf(call("c1", "w"), done(AgentStopReason.TOOL_USE)), text("ok") + done())
        env.controller.send("vai", env.withTools(engine))
        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.BLOCKED)
        assertThat(env.gate.pending.value).isNull()
        assertThat(write.runs).isEmpty()
    }

    @Test
    fun `a tool that fails is an error card with the first line of the reason`() {
        val bad = FakeTool("r", onRun = { ToolOutput.error("O arquivo passa de 64 KB.\nmais detalhe") })
        val env = Env(listOf(bad))
        val engine = ScriptedEngine(listOf(call("c1", "r"), done(AgentStopReason.TOOL_USE)), text("vou corrigir") + done())
        env.controller.send("vai", env.withTools(engine))
        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.ERROR)
        assertThat(env.cards.single().detail).isEqualTo("O arquivo passa de 64 KB.")
    }

    @Test
    fun `an unknown tool is an error card without a risk`() {
        val env = Env(listOf(FakeTool("r")))
        val engine = ScriptedEngine(listOf(call("c1", "rm_rf"), done(AgentStopReason.TOOL_USE)), text("ok") + done())
        env.controller.send("vai", env.withTools(engine))
        assertThat(env.cards.single().risk).isNull()
        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.ERROR)
    }

    @Test
    fun `an image returned by a tool is kept on its card`() {
        val png = ToolImage("image/png", byteArrayOf(9, 9))
        val env = Env(listOf(FakeTool("skin_preview", onRun = { ToolOutput.ok("Válida.", image = png) })))
        val engine = ScriptedEngine(listOf(call("c1", "skin_preview"), done(AgentStopReason.TOOL_USE)), text("viu?") + done())
        env.controller.send("mostre", env.withTools(engine))
        assertThat(env.cards.single().image).isEqualTo(png)
    }

    // ── cancelar ─────────────────────────────────────────────────────────────

    @Test
    fun `cancel while the user is deciding - the question closes, the card says cancelled and the chat is free`() {
        val write = FakeTool("w", ToolRisk.WRITE, summary = { "Mudar" })
        val env = Env(listOf(write))
        val engine = ScriptedEngine(listOf(call("c1", "w"), done(AgentStopReason.TOOL_USE)), text("depois") + done())
        env.controller.send("vai", env.withTools(engine))
        assertThat(env.controller.pendingConfirmation.value).isNotNull()

        env.controller.cancel()

        assertThat(env.controller.pendingConfirmation.value).isNull()
        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.CANCELLED)
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
        assertThat(env.controller.isBusy).isFalse()
        assertThat(write.runs).isEmpty()
        assertThat(env.log.snapshot().single().outcome).isEqualTo(ActionOutcome.CANCELLED)
    }

    @Test
    fun `cancel while a tool runs interrupts it`() {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val slow = FakeTool("r", onRun = { started.complete(Unit); never.await(); ToolOutput.ok("nunca") })
        val env = Env(listOf(slow))
        val engine = ScriptedEngine(listOf(call("c1", "r"), done(AgentStopReason.TOOL_USE)), text("x") + done())
        env.controller.send("vai", env.withTools(engine))
        assertThat(started.isCompleted).isTrue()
        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.RUNNING)
        assertThat(env.activity).isEqualTo(AgentActivity.RUNNING_TOOL)

        env.controller.cancel()

        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.CANCELLED)
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
    }

    @Test
    fun `after a cancel the next run keeps the synthetic result right after its call and sends it to the model`() {
        val write = FakeTool("w", ToolRisk.WRITE, summary = { "Mudar" })
        val env = Env(listOf(write))
        val first = ScriptedEngine(listOf(call("c1", "w"), done(AgentStopReason.TOOL_USE)))
        env.controller.send("vai", env.withTools(first))
        env.controller.cancel()
        // O histórico ficou com a chamada sem resultado.
        assertThat(env.controller.historySnapshot().map { it::class.simpleName }).containsExactly("User", "Assistant").inOrder()

        val second = ScriptedEngine(text("Oi de novo.") + done())
        env.controller.send("deixa pra lá", env.withTools(second))

        val sent = second.requests.single().messages
        assertThat(sent.map { it::class.simpleName }).containsExactly("User", "Assistant", "ToolResult", "User").inOrder()
        val synthetic = sent[2] as AgentMessage.ToolResult
        assertThat(synthetic.callId).isEqualTo("c1")
        assertThat(synthetic.isError).isTrue()
        assertThat(synthetic.content).isEqualTo(AgentHistory.INTERRUPTED)
        // E o controlador GUARDOU o resultado sintético, no lugar certo (não no fim).
        assertThat(env.controller.historySnapshot().map { it::class.simpleName }).containsExactly("User", "Assistant", "ToolResult", "User", "Assistant").inOrder()
        // Uma terceira volta não acrescenta outro.
        val third = ScriptedEngine(text("ok") + done())
        env.controller.send("e agora", env.withTools(third))
        assertThat(third.requests.single().messages.count { it is AgentMessage.ToolResult }).isEqualTo(1)
    }

    @Test
    fun `leaving the tab with a question open cancels the run`() {
        val write = FakeTool("w", ToolRisk.WRITE, summary = { "Mudar" })
        val env = Env(listOf(write))
        env.controller.send("vai", env.withTools(ScriptedEngine(listOf(call("c1", "w"), done(AgentStopReason.TOOL_USE)))))
        assertThat(env.controller.pendingConfirmation.value).isNotNull()
        env.controller.panelHidden()
        assertThat(env.controller.pendingConfirmation.value).isNull()
        assertThat(env.controller.isBusy).isFalse()
        assertThat(write.runs).isEmpty()
    }

    @Test
    fun `leaving the tab with nothing to decide leaves the run alone`() {
        val env = Env()
        val release = CompletableDeferred<Unit>()
        env.controller.send("oi", AgentBackend.TextOnly { flow { release.await(); emit(AgentEvent.TextDelta("x")); emit(AgentEvent.Done()) } })
        env.controller.panelHidden()
        assertThat(env.controller.isBusy).isTrue()
        release.complete(Unit)
        assertThat(env.bubbles.single().text).isEqualTo("x")
    }

    @Test
    fun `an answer for another call is ignored and the question stays open`() {
        val write = FakeTool("w", ToolRisk.WRITE, summary = { "Mudar" })
        val env = Env(listOf(write))
        env.controller.send("vai", env.withTools(ScriptedEngine(listOf(call("c1", "w"), done(AgentStopReason.TOOL_USE)), text("ok") + done())))
        assertThat(env.controller.respond("outra", ConfirmationDecision.ALLOW)).isFalse()
        assertThat(env.controller.pendingConfirmation.value).isNotNull()
        assertThat(write.runs).isEmpty()
        env.allow()
        assertThat(write.runs).hasSize(1)
    }

    // ── erros e limites ──────────────────────────────────────────────────────

    @Test
    fun `a failure of the model is shown like the old chat and the failed turn stays out of the history`() {
        val env = Env()
        val engine = ScriptedEngine(text("Começo") + errorEvent(ReasoningError.RATE_LIMIT, "limite de taxa"))
        env.controller.send("oi", env.withTools(engine))
        // A volta que falhou não chega a entrar no histórico, e o texto parcial que chegou continua na tela.
        assertThat(env.bubbles.single().text).isEqualTo("Começo\n[limite de taxa]")
        assertThat(env.controller.historySnapshot()).containsExactly(user("oi"))
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
    }

    @Test
    fun `a motor that throws becomes text in the chat, not a crash`() {
        val env = Env()
        val broken = object : AgentEngine {
            override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = flow { error("boom") }
        }
        env.controller.send("oi", env.withTools(broken))
        assertThat(env.bubbles.single().text).contains("O motor do modelo falhou")
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
    }

    @Test
    fun `an unexpected defect with a message shows in brackets, one without leaves a generic notice`() {
        val env = Env()
        env.controller.send("a", AgentBackend.TextOnly { flow { throw IllegalStateException("quebrou") } })
        assertThat(env.bubbles.single().text).contains("[quebrou]")
        val env2 = Env()
        env2.controller.send("a", AgentBackend.TextOnly { flow { throw IllegalStateException() } })
        assertThat(env2.items.filterIsInstance<ChatItem.Notice>().single().kind).isEqualTo(NoticeKind.GENERATION_ERROR)
        assertThat(env2.activity).isEqualTo(AgentActivity.IDLE)
    }

    @Test
    fun `the step limit leaves a notice in the chat`() {
        val loopTool = FakeTool("r")
        val env = Env(listOf(loopTool), limits = AgentLimits(maxSteps = 2))
        val engine = ScriptedEngine { _, _ -> listOf(call("c${System.nanoTime()}", "r"), done(AgentStopReason.TOOL_USE)) }
        env.controller.send("vai", env.withTools(engine))
        assertThat(env.items.filterIsInstance<ChatItem.Notice>().single().kind).isEqualTo(NoticeKind.STEP_LIMIT)
        assertThat(env.cards).hasSize(2)
    }

    @Test
    fun `a truncated or refused answer leaves its notice`() {
        val env = Env()
        env.controller.send("oi", env.withTools(ScriptedEngine(text("cortad") + done(AgentStopReason.TRUNCATED))))
        env.controller.send("oi", env.withTools(ScriptedEngine(text("não") + done(AgentStopReason.REFUSAL))))
        assertThat(env.items.filterIsInstance<ChatItem.Notice>().map { it.kind }).containsExactly(NoticeKind.TRUNCATED, NoticeKind.REFUSED).inOrder()
    }

    @Test
    fun `the tokens of the runs are summed`() {
        val env = Env()
        env.controller.send("a", env.withTools(ScriptedEngine(text("x") + done(usage = TokenUsage(10, 4)))))
        env.controller.send("b", env.withTools(ScriptedEngine(text("y") + done(usage = TokenUsage(30, 6)))))
        assertThat(env.controller.state.value.usage).isEqualTo(TokenUsage(40, 10))
    }

    // ── limpar e trocar de origem ────────────────────────────────────────────

    @Test
    fun `clearing resets the chat and the history, and cancels what was running`() {
        val write = FakeTool("w", ToolRisk.WRITE, summary = { "Mudar" })
        val env = Env(listOf(write))
        env.controller.send("vai", env.withTools(ScriptedEngine(listOf(call("c1", "w"), done(AgentStopReason.TOOL_USE)))))
        assertThat(env.controller.pendingConfirmation.value).isNotNull()

        env.controller.clear()

        assertThat(env.controller.pendingConfirmation.value).isNull()
        assertThat(env.controller.isBusy).isFalse()
        assertThat(env.items.single()).isInstanceOf(ChatItem.Greeting::class.java)
        assertThat(env.controller.historySnapshot()).isEmpty()
        assertThat(env.activity).isEqualTo(AgentActivity.IDLE)
        // A conversa nova não herda a anterior.
        val engine = ScriptedEngine(text("oi") + done())
        env.controller.send("nova", env.withTools(engine))
        assertThat(engine.requests.single().messages).containsExactly(user("nova"))
    }

    @Test
    fun `the text only exchanges also go into the history, so going back to the agent keeps the conversation`() {
        val env = Env()
        env.controller.send("meu nome é Yan", textOnly("Prazer, Yan."))
        val engine = ScriptedEngine(text("Oi, Yan.") + done())
        env.controller.send("qual é meu nome?", env.withTools(engine))
        assertThat(engine.requests.single().messages).containsExactly(user("meu nome é Yan"), AgentMessage.Assistant("Prazer, Yan."), user("qual é meu nome?")).inOrder()
    }

    // ── estado do turno e preparo ────────────────────────────────────────────

    private class Scoped(name: String) : AgentTool, TurnScoped {
        val ended = AtomicInteger()
        override val name = name
        override val description = "x"
        override val inputSchema: JSONObject = JSONObject(TEXT_SCHEMA)
        override val risk = ToolRisk.READ

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext) = ToolOutput.ok("ok")

        override fun turnEnded() {
            ended.incrementAndGet()
        }
    }

    @Test
    fun `tools that hold turn state are told when the turn ends, on success, on cancel and on clear`() {
        val scoped = Scoped("s")
        val env = Env(listOf(scoped))
        env.controller.send("a", env.withTools(ScriptedEngine(text("x") + done())))
        assertThat(scoped.ended.get()).isEqualTo(1)

        val release = CompletableDeferred<Unit>()
        env.controller.send("b", AgentBackend.TextOnly { flow { release.await(); emit(AgentEvent.Done()) } })
        env.controller.cancel()
        assertThat(scoped.ended.get()).isEqualTo(2)

        env.controller.clear()
        assertThat(scoped.ended.get()).isEqualTo(3)
    }

    private class ScopedSplit(name: String) : AgentTool, TurnScoped {
        val startedGen = CopyOnWriteArrayList<Long>()
        val endedGen = CopyOnWriteArrayList<Long>()
        val cancelledGen = CopyOnWriteArrayList<Long>()
        val ended = AtomicInteger()
        val cancelled = AtomicInteger()
        override val name = name
        override val description = "x"
        override val inputSchema: JSONObject = JSONObject(TEXT_SCHEMA)
        override val risk = ToolRisk.READ

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext) = ToolOutput.ok("ok")

        override fun turnStarted(generation: Long) {
            startedGen += generation
        }

        override fun turnEnded(generation: Long) {
            endedGen += generation
            turnEnded()
        }

        override fun turnCancelled(generation: Long) {
            cancelledGen += generation
            turnCancelled()
        }

        override fun turnEnded() {
            ended.incrementAndGet()
        }

        override fun turnCancelled() {
            cancelled.incrementAndGet()
        }
    }

    @Test
    fun `a tool that tells ended from cancelled hears ended on success and cancelled on cancel and on clear`() {
        val scoped = ScopedSplit("s")
        val env = Env(listOf(scoped))
        env.controller.send("a", env.withTools(ScriptedEngine(text("x") + done())))
        assertThat(scoped.ended.get()).isEqualTo(1)
        assertThat(scoped.cancelled.get()).isEqualTo(0)

        val release = CompletableDeferred<Unit>()
        env.controller.send("b", AgentBackend.TextOnly { flow { release.await(); emit(AgentEvent.Done()) } })
        env.controller.cancel()
        assertThat(scoped.ended.get()).isEqualTo(1)
        assertThat(scoped.cancelled.get()).isEqualTo(1)

        env.controller.clear()
        assertThat(scoped.ended.get()).isEqualTo(1)
        assertThat(scoped.cancelled.get()).isEqualTo(2)
    }

    @Test
    fun `when a job dies after clear has bumped runNumber, turn state is still released as cancelled`() {
        val scoped = ScopedSplit("s")
        val env = Env(listOf(scoped))
        val release = CompletableDeferred<Unit>()
        env.controller.send("x", AgentBackend.TextOnly { flow { release.await(); emit(AgentEvent.Done()) } })
        assertThat(scoped.cancelled.get()).isEqualTo(0)

        env.controller.clear()
        // O job cancelado garante que releaseTurnState(cancelled = true) foi chamado para a geração 1
        assertThat(scoped.cancelled.get()).isAtLeast(1)
        assertThat(scoped.cancelledGen).contains(1L)
        assertThat(env.controller.isBusy).isFalse()

        env.controller.send("y", env.withTools(ScriptedEngine(text("oi") + done())))
        assertThat(scoped.ended.get()).isEqualTo(1)
        // O turno novo (geração 3) conclui e recebe ended(3), e a soltura de 1 nunca tocou em 3
        assertThat(scoped.endedGen).contains(3L)
        assertThat(scoped.cancelledGen).doesNotContain(3L)
    }

    @Test
    fun `nothing runs before the policy is ready`() {
        val gate = CompletableDeferred<Unit>()
        val read = FakeTool("r")
        val env = Env(listOf(read), ready = { gate.await() })
        val engine = ScriptedEngine(listOf(call("c1", "r"), done(AgentStopReason.TOOL_USE)), text("ok") + done())
        env.controller.send("vai", env.withTools(engine))
        assertThat(engine.requests).isEmpty()
        assertThat(read.runs).isEmpty()
        assertThat(env.controller.isBusy).isTrue()
        gate.complete(Unit)
        assertThat(read.runs).hasSize(1)
        assertThat(engine.requests).hasSize(2)
    }

    // ── desfazer ─────────────────────────────────────────────────────────────

    @Test
    fun `undo runs the undo call through the same panel, shows a card and consumes the undo of the log entry`() {
        val removed = CopyOnWriteArrayList<String>()
        val remove = FakeTool(
            "skin_remove", ToolRisk.DESTRUCTIVE,
            schema = """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}""",
            summary = { "Remover ${it.getString("id")}" },
            onRun = { removed += it.getString("id"); ToolOutput.ok("Removida.") },
        )
        val env = Env(listOf(remove))
        val entry = kotlinx.coroutines.runBlocking { env.log.append(ActionLogEntry(atMs = 1, toolName = "skin_install", risk = ToolRisk.WRITE, summary = "Instalar neon", outcome = ActionOutcome.OK, detail = "ok", origin = ToolOrigin.CHAT, undo = UndoCall("skin_remove", """{"id":"neon"}"""))) }

        assertThat(env.controller.undo(entry)).isTrue()
        // Passa pela confirmação como qualquer chamada (DESTRUCTIVE: sempre pergunta, sem "permitir sempre").
        val request = env.controller.pendingConfirmation.value!!
        assertThat(request.summary).isEqualTo("Remover neon")
        assertThat(request.canAlwaysAllow).isFalse()
        assertThat(removed).isEmpty()
        env.allow()

        assertThat(removed).containsExactly("neon")
        assertThat(env.cards.single().phase).isEqualTo(ToolCardPhase.DONE)
        assertThat(env.log.snapshot().first { it.id == entry.id }.undo).isNull()
        // O desfazer também ficou no registro (é DESTRUCTIVE).
        assertThat(env.log.snapshot().map { it.toolName }).contains("skin_remove")
    }

    @Test
    fun `undo is refused with no undo call, with no executor or while a run is going`() {
        val env = Env()
        val plain = ActionLogEntry(id = 1, atMs = 1, toolName = "t", risk = ToolRisk.WRITE, summary = "s", outcome = ActionOutcome.OK, detail = "d", origin = ToolOrigin.CHAT)
        assertThat(env.controller.undo(plain)).isFalse()

        val withUndo = plain.copy(undo = UndoCall("t", "{}"))
        val release = CompletableDeferred<Unit>()
        env.controller.send("oi", AgentBackend.TextOnly { flow { release.await(); emit(AgentEvent.Done()) } })
        assertThat(env.controller.undo(withUndo)).isFalse()
        release.complete(Unit)

        val bare = AgentController(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it })
        assertThat(bare.undo(withUndo)).isFalse()
    }

    @Test
    fun `a controller with no tools is the plain chat`() {
        val bare = AgentController(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it })
        bare.send("oi", textOnly("olá"))
        assertThat(bare.state.value.items.filterIsInstance<ChatItem.AssistantMessage>().single().text).isEqualTo("olá")
        assertThat(bare.state.value.items.filterIsInstance<ChatItem.ToolCard>()).isEmpty()
    }
}
