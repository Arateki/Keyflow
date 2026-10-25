package com.keyflow.ui.config

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentTool
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.InMemoryPolicyConfigStore
import com.keyflow.agent.PolicyGated
import com.keyflow.agent.ToolContext
import com.keyflow.agent.ToolDefinition
import com.keyflow.agent.ToolDescription
import com.keyflow.agent.ToolOutput
import com.keyflow.agent.ToolPreview
import com.keyflow.agent.ToolRisk
import com.keyflow.agenthost.AgentBackends
import com.keyflow.agenthost.AgentHost
import com.keyflow.agenthost.AgentParts
import com.keyflow.agenthost.AgentRuntime
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.i18n.AppLanguage
import com.keyflow.i18n.AppLocale
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.ui.orb.LocalOrbClock
import com.keyflow.ui.theme.KeyFlowTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AGT-T3 (AUI-01/02/04, AGT-05): a aba KeyFlow com o [com.keyflow.agent.AgentController] — o ciclo completo com um motor FALSO (nenhuma
 * chamada de rede) e ferramentas de teste: cartão, painel de confirmação (Permitir / Negar / Permitir sempre), cancelar, limpar a
 * conversa, a orbe/legenda do que o agente faz, e o chat de sempre quando não há agente (ou a origem não suporta ferramentas).
 */
@RunWith(AndroidJUnit4::class)
class KeyflowPanelAgentTest {

    @get:Rule
    val rule = createComposeRule()

    private val scopes = CopyOnWriteArrayList<CoroutineScope>()

    @Before
    fun setUp() {
        AppLocale.pinned = AppLanguage.PT
    }

    @After
    fun tearDown() {
        AppLocale.pinned = null
        scopes.forEach { it.cancel() }
    }

    // ── ferramentas e motor de mentira ───────────────────────────────────────

    private class TestTool(
        override val name: String,
        override val risk: ToolRisk,
        private val summary: String = "resumo de $name",
        private val preview: ToolPreview? = null,
    ) : AgentTool {
        val runs = CopyOnWriteArrayList<String>()
        override val description = "Ferramenta de teste."
        override val inputSchema = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")
        override val label: String get() = "Rótulo $name"

        override suspend fun describe(args: JSONObject, ctx: ToolContext) = ToolDescription(summary, preview)

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext): ToolOutput {
            runs += name
            return ToolOutput.ok("resultado de $name")
        }
    }

    /** O usuário diz o nome da ferramenta; o motor a pede; com o resultado, ele responde "Concluído: <resultado>". */
    private class ScriptEngine : AgentEngine {
        val requests = CopyOnWriteArrayList<List<AgentMessage>>()

        override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = flow {
            requests += messages
            val last = messages.last()
            if (last is AgentMessage.ToolResult) {
                emit(AgentEvent.TextDelta("Concluído: ${last.content.take(60)}"))
                emit(AgentEvent.Done())
            } else {
                val said = (last as AgentMessage.User).text
                val wanted = tools.firstOrNull { said.contains(it.name) }
                if (wanted == null) {
                    emit(AgentEvent.TextDelta("Resposta a: $said"))
                    emit(AgentEvent.Done())
                } else {
                    emit(AgentEvent.TextDelta("Vou usar ${wanted.name}."))
                    emit(AgentEvent.ToolCall("call-${wanted.name}", wanted.name, "{}"))
                    emit(AgentEvent.Done(AgentStopReason.TOOL_USE))
                }
            }
        }
    }

    private val reader = TestTool("ler_algo", ToolRisk.READ)
    private val writer = TestTool("mudar_algo", ToolRisk.WRITE, summary = "Mudar o algo para azul", preview = ToolPreview("image/png", tinyPng(), "Prévia do algo"))
    private val destroyer = TestTool("remover_algo", ToolRisk.DESTRUCTIVE, summary = "Remover o algo para sempre")
    private val engine = ScriptEngine()

    private fun tinyPng(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(32, 20, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF3366CC.toInt())
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private fun runtime(tools: List<AgentTool> = listOf(reader, writer, destroyer), apiKey: Boolean = true): Pair<AgentRuntime, AgentParts> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val parts = AgentHost.assemble(scope, tools, InMemoryPolicyConfigStore(), InMemoryActionLog(), secrets = { emptyList() })
        val backends = AgentBackends(
            hasTools = { tools.isNotEmpty() },
            anthropicApiKeyConnected = { apiKey },
            agentEngine = { engine },
            resolveText = { ReasoningResolution.Ready(object : ReasoningEngine {
                override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = flow {
                    emit(ReasoningEvent.Delta("Texto puro: ${request.user}"))
                    emit(ReasoningEvent.Done)
                }
            }) },
        )
        return parts.runtime(backends) to parts
    }

    private val anthropicKey = Settings(reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = "claude-sonnet-5-5")

    private fun show(agent: AgentRuntime?, settings: Settings = anthropicKey, resolve: (Settings) -> ReasoningResolution = { ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL) }) {
        rule.setContent {
            CompositionLocalProvider(LocalOrbClock provides { 1234f }) {
                KeyFlowTheme(dark = false) {
                    KeyflowPanel(settings = settings, onSettingsChange = {}, resolve = resolve, agent = agent)
                }
            }
        }
    }

    private fun say(text: String) {
        rule.onNodeWithTag(KEYFLOW_INPUT_TAG).performTextInput(text)
        rule.onNodeWithTag(KEYFLOW_SEND_BUTTON_TAG).performClick()
    }

    private fun waitForTag(tag: String, present: Boolean = true) =
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() == present }

    /**
     * Espera [text] na tela. Se não vier, a falha diz o que havia: quantas vezes o motor foi chamado (0 = o envio nem saiu), o
     * que as ferramentas rodaram e os textos na tela — no gate de 2026-10-10 a resposta não veio em 10 s uma vez e passou na
     * repetição, sem rastro; a esteira não reproduziu em 208 voltas da classe.
     */
    private fun waitForText(text: String) {
        try {
            rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val texts = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text))
                .fetchSemanticsNodes()
                .flatMap { node -> node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].map { it.text } }
            throw AssertionError(
                "\"$text\" não apareceu em 10 s. Motor chamado ${engine.requests.size}× (último: ${engine.requests.lastOrNull()?.lastOrNull()}); " +
                    "ferramentas: ${reader.runs + writer.runs + destroyer.runs}; na tela (o campo vazio mostra o texto-guia): $texts",
                e,
            )
        }
    }

    /** O texto dentro do painel de confirmação (o mesmo resumo também aparece no cartão do chat). */
    private fun inPanel(text: String) = rule.onNode(androidx.compose.ui.test.hasText(text) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(AGENT_CONFIRM_PANEL_TAG)))

    private fun waitForPhase(callId: String, label: String) =
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(agentCardPhaseTag(callId)).assertTextContains(label) }.isSuccess }

    // ── o ciclo ──────────────────────────────────────────────────────────────

    @Test
    fun a_read_tool_runs_without_asking_and_its_card_says_done() {
        val (runtime, _) = runtime()
        show(runtime)
        say("use ler_algo")
        waitForText("Concluído: resultado de ler_algo")
        rule.onNodeWithTag(agentCardTag("call-ler_algo")).assertIsDisplayed()
        rule.onNodeWithText("Rótulo ler_algo").assertIsDisplayed()
        waitForPhase("call-ler_algo", "Feito")
        assertTrue(rule.onAllNodesWithTag(AGENT_CONFIRM_PANEL_TAG).fetchSemanticsNodes().isEmpty())
        assertEquals(1, reader.runs.size)
    }

    @Test
    fun a_write_tool_waits_for_the_panel_which_shows_the_app_summary_and_the_preview_then_allow_runs_it() {
        val (runtime, _) = runtime()
        show(runtime)
        say("use mudar_algo")
        waitForTag(AGENT_CONFIRM_PANEL_TAG)
        inPanel("Mudar o algo para azul").assertIsDisplayed()
        inPanel("Prévia do algo").assertIsDisplayed()
        rule.onNodeWithTag(AGENT_CONFIRM_ALWAYS_TAG).assertIsDisplayed()
        waitForPhase("call-mudar_algo", "Pedindo permissão")
        // A legenda sob a orbe e o botão Cancelar: o agente espera o usuário.
        // (Com o teclado aberto a janela rola para cima e a legenda sai da tela: existir basta.)
        rule.onNodeWithText("Aguardando sua permissão…").assertExists()
        rule.onNodeWithTag(KEYFLOW_CANCEL_BUTTON_TAG).assertIsDisplayed()
        assertTrue(writer.runs.isEmpty())

        rule.onNodeWithTag(AGENT_CONFIRM_ALLOW_TAG).performClick()
        waitForText("Concluído: resultado de mudar_algo")
        waitForPhase("call-mudar_algo", "Feito")
        waitForTag(AGENT_CONFIRM_PANEL_TAG, present = false)
        assertEquals(1, writer.runs.size)
    }

    @Test
    fun deny_does_not_run_the_tool_and_tells_the_model() {
        val (runtime, parts) = runtime()
        show(runtime)
        say("use mudar_algo")
        waitForTag(AGENT_CONFIRM_PANEL_TAG)
        rule.onNodeWithTag(AGENT_CONFIRM_DENY_TAG).performClick()
        waitForPhase("call-mudar_algo", "Negado")
        waitForText("Concluído: O usuário negou")
        assertTrue(writer.runs.isEmpty())
        assertTrue(parts.actionLog is InMemoryActionLog)
    }

    @Test
    fun allow_always_is_offered_for_a_write_and_never_for_a_destructive_tool() {
        val (runtime, parts) = runtime()
        show(runtime)
        say("use mudar_algo")
        waitForTag(AGENT_CONFIRM_PANEL_TAG)
        rule.onNodeWithTag(AGENT_CONFIRM_ALWAYS_TAG).performClick()
        waitForText("Concluído: resultado de mudar_algo")
        assertTrue("mudar_algo" in parts.policy.config.value.alwaysAllowed)

        // O mesmo pedido agora roda sem perguntar.
        say("use mudar_algo outra vez")
        waitForText("Concluído: resultado de mudar_algo")
        assertEquals(2, writer.runs.size)

        say("use remover_algo")
        waitForTag(AGENT_CONFIRM_PANEL_TAG)
        assertTrue(rule.onAllNodesWithTag(AGENT_CONFIRM_ALWAYS_TAG).fetchSemanticsNodes().isEmpty())
        inPanel("Remover o algo para sempre").assertIsDisplayed()
        rule.onNodeWithTag(AGENT_CONFIRM_ALLOW_TAG).performClick()
        waitForText("Concluído: resultado de remover_algo")
        assertEquals(1, destroyer.runs.size)
    }

    @Test
    fun cancel_while_the_panel_is_open_closes_it_and_marks_the_card_cancelled() {
        val (runtime, _) = runtime()
        show(runtime)
        say("use mudar_algo")
        waitForTag(AGENT_CONFIRM_PANEL_TAG)
        rule.onNodeWithTag(KEYFLOW_CANCEL_BUTTON_TAG).performClick()
        waitForTag(AGENT_CONFIRM_PANEL_TAG, present = false)
        waitForPhase("call-mudar_algo", "Cancelado")
        // O chat voltou a aceitar mensagem (o botão de enviar está de volta).
        waitForTag(KEYFLOW_SEND_BUTTON_TAG)
        assertTrue(writer.runs.isEmpty())
    }

    @Test
    fun the_history_goes_back_to_the_model_on_every_turn() {
        val (runtime, _) = runtime()
        show(runtime)
        say("primeira mensagem")
        waitForText("Resposta a: primeira mensagem")
        say("segunda mensagem")
        waitForText("Resposta a: segunda mensagem")
        val second = engine.requests.last()
        assertEquals(listOf("primeira mensagem", "Resposta a: primeira mensagem", "segunda mensagem"), second.map {
            when (it) {
                is AgentMessage.User -> it.text
                is AgentMessage.Assistant -> it.text
                is AgentMessage.ToolResult -> it.content
            }
        })
    }

    @Test
    fun clearing_the_conversation_from_settings_resets_the_chat_and_the_history() {
        val (runtime, parts) = runtime()
        show(runtime)
        say("primeira mensagem")
        waitForText("Resposta a: primeira mensagem")
        rule.onNodeWithTag(KEYFLOW_AJUSTES_TOGGLE_TAG).performClick()
        // A seção "Ferramentas do agente" (AGT-T5) fica antes do botão, que agora mora abaixo da dobra da área de Ajustes.
        rule.onNodeWithTag("keyflow-clear-chat").performScrollTo().performClick()
        rule.waitUntil(10_000) { parts.controller.historySnapshot().isEmpty() }
        rule.onNodeWithTag(KEYFLOW_AJUSTES_TOGGLE_TAG).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Resposta a: primeira mensagem", substring = true)).fetchSemanticsNodes().isEmpty() }
        assertTrue(parts.controller.historySnapshot().isEmpty())
    }

    // ── sem ferramentas / origem sem suporte ─────────────────────────────────

    @Test
    fun without_an_agent_the_chat_is_the_plain_one_with_no_note() {
        show(agent = null, settings = Settings(reasoningSource = ReasoningSource.LOCAL), resolve = {
            ReasoningResolution.Ready(object : ReasoningEngine {
                override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = flow {
                    emit(ReasoningEvent.Delta("Eco: ${request.user}"))
                    emit(ReasoningEvent.Done)
                }
            })
        })
        say("olá")
        waitForText("Eco: olá")
        assertTrue(rule.onAllNodesWithTag(AGENT_MODE_NOTE_TAG).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag(KEYFLOW_CANCEL_BUTTON_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun without_an_agent_a_missing_credential_shows_the_reason_in_the_chat() {
        show(agent = null, settings = Settings(reasoningSource = ReasoningSource.OPENAI), resolve = { ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED) })
        say("olá")
        waitForText("Credenciais do modelo não configuradas")
    }

    @Test
    fun another_origin_than_anthropic_by_key_falls_back_to_text_only_with_the_note() {
        val (runtime, _) = runtime()
        show(runtime, settings = Settings(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = "gpt"))
        waitForTag(AGENT_MODE_NOTE_TAG)
        rule.onNodeWithText("Modo só texto · ferramentas disponíveis com Anthropic, OpenAI ou Google por chave de API").assertIsDisplayed()
        say("use ler_algo")
        // Texto puro: a ferramenta nem foi oferecida.
        waitForText("Texto puro: use ler_algo")
        assertTrue(reader.runs.isEmpty())
        assertTrue(rule.onAllNodesWithTag(agentCardTag("call-ler_algo")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anthropic_by_login_instead_of_a_key_also_falls_back_with_the_note() {
        val (runtime, _) = runtime(apiKey = false)
        show(runtime)
        waitForTag(AGENT_MODE_NOTE_TAG)
        say("use ler_algo")
        waitForText("Texto puro: use ler_algo")
        assertTrue(reader.runs.isEmpty())
    }

    @Test
    fun anthropic_by_key_has_no_note() {
        val (runtime, _) = runtime()
        show(runtime)
        assertFalse(rule.onAllNodesWithTag(AGENT_MODE_NOTE_TAG).fetchSemanticsNodes().isNotEmpty())
    }
}
