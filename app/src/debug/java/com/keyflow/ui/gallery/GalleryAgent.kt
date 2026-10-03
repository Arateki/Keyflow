package com.keyflow.ui.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.keyflow.agent.ActionLogEntry
import com.keyflow.agent.ActionOutcome
import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentTool
import com.keyflow.agent.ChatItem
import com.keyflow.agent.ConfirmationRequest
import com.keyflow.agent.InMemoryActionLog
import com.keyflow.agent.InMemoryPolicyConfigStore
import com.keyflow.agent.NoticeKind
import com.keyflow.agent.PolicyGated
import com.keyflow.agent.ToolCardPhase
import com.keyflow.agent.ToolContext
import com.keyflow.agent.ToolDefinition
import com.keyflow.agent.ToolDescription
import com.keyflow.agent.ToolImage
import com.keyflow.agent.ToolOrigin
import com.keyflow.agent.ToolOutput
import com.keyflow.agent.ToolPreview
import com.keyflow.agent.ToolPolicyConfig
import com.keyflow.agent.ToolRisk
import com.keyflow.agent.UndoCall
import com.keyflow.agenthost.AgentBackends
import com.keyflow.agenthost.AgentHost
import com.keyflow.agenthost.AgentParts
import com.keyflow.agenthost.AgentRuntime
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.ui.config.AgentChatEntry
import com.keyflow.ui.config.AgentConfirmationPanel
import com.keyflow.ui.config.AgentSettingsSection
import com.keyflow.ui.config.KeyflowPanel
import com.keyflow.ui.theme.LocalKeyFlowPalette
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * As rotas da galeria do agente (agent-harness, AGT-T3..T5): o chat com os cartões de ferramenta em cada fase, o painel de confirmação
 * e — nas rotas de página inteira — a aba KeyFlow de verdade, com um agente de mentira (motor roteirizado e ferramentas falsas) que roda
 * ANTES da primeira composição, no `Dispatchers.Unconfined`, para o estado do chat já estar pronto no 1º quadro (print determinístico).
 */
object AgentGalleryRoutes {

    val routes: List<Route> = listOf(
        Route("agent-chat-cartoes") { ChatCardsRoute(states = false) },
        Route("agent-chat-cartoes-estados") { ChatCardsRoute(states = true) },
        Route("agent-confirmacao-skin") { ConfirmationRoute(destructive = false) },
        Route("agent-confirmacao-remover") { ConfirmationRoute(destructive = true) },
        Route("agent-keyflow-modo-texto") { PanelRoute(confirming = false, source = ReasoningSource.LOCAL) },
        Route("agent-keyflow-confirmacao", minDelayMs = 400) { PanelRoute(confirming = true, source = ReasoningSource.ANTHROPIC) },
        // AGT-T5: a seção "Ferramentas do agente" dos Ajustes do chat, com uma liberada sempre, uma desligada e o registro com Desfazer.
        Route("agent-ajustes") { SettingsRoute() },
    )

    // ── dados de mentira ─────────────────────────────────────────────────────

    /** Uma "folha de contato" desenhada à mão (4 orbes, 3 quadros de animação, faces do teclado) — o que o app gera de verdade vem do SvgRender. */
    val sheet: ToolImage by lazy {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFF1B1F2A.toInt())
        val colors = intArrayOf(0xFF00E5FF.toInt(), 0xFFFF2E93.toInt(), 0xFF8A5CFF.toInt(), 0xFFFFD400.toInt())
        for (i in colors.indices) {
            paint.color = colors[i]
            canvas.drawCircle(95f + i * 150f, 105f, 55f, paint)
        }
        for (i in 0 until 3) {
            paint.color = colors[i]
            paint.alpha = 255 - i * 70
            canvas.drawCircle(120f + i * 120f, 245f, 32f + i * 6f, paint)
        }
        paint.alpha = 255
        paint.color = 0xFF2E3447.toInt()
        for (i in 0 until 5) canvas.drawRoundRect(40f + i * 112f, 315f, 40f + i * 112f + 92f, 375f, 14f, 14f, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        ToolImage("image/png", out.toByteArray())
    }

    private fun card(id: Long, name: String, risk: ToolRisk?, phase: ToolCardPhase, summary: String? = null, detail: String? = null, image: ToolImage? = null) =
        ChatItem.ToolCard(id, "call-$id", name, risk, summary, phase, detail, image)

    private val labels = mapOf(
        "skin_get_format" to "Ler o formato de skin",
        "skin_preview" to "Pré-visualizar skin",
        "skin_install" to "Instalar skin",
        "skin_select" to "Escolher skin",
        "skin_remove" to "Remover skin",
        "settings_set" to "Mudar ajuste",
    )

    private fun labelOf(name: String) = labels[name] ?: name

    // ── rotas de peças ───────────────────────────────────────────────────────

    @Composable
    private fun ChatCardsRoute(states: Boolean) {
        val items: List<ChatItem> = if (!states) {
            listOf(
                ChatItem.UserMessage(1, "Crie uma skin cyberpunk e instale."),
                ChatItem.AssistantMessage(2, "Vou ler o formato, desenhar a skin e mostrar uma prévia antes de instalar."),
                card(3, "skin_get_format", ToolRisk.READ, ToolCardPhase.DONE, detail = "Formato do pacote de skin (FORMAT.md)."),
                card(4, "skin_preview", ToolRisk.READ, ToolCardPhase.DONE, detail = "Skin «Cyberpunk» válida: orbe de 4 estados, 46 quadros; teclado com 5 peças.", image = sheet),
                card(5, "skin_install", ToolRisk.WRITE, ToolCardPhase.AWAITING_CONFIRMATION, summary = "Instalar a skin «Cyberpunk» (orbe: 4 estados, 46 quadros; teclado: bandeja + 4 faces). Nada é escolhido sozinho.", image = sheet),
                card(6, "skin_select", ToolRisk.WRITE, ToolCardPhase.RUNNING, summary = "Usar «Cyberpunk» no teclado e na orbe."),
            )
        } else {
            listOf(
                card(1, "skin_remove", ToolRisk.DESTRUCTIVE, ToolCardPhase.DENIED, summary = "Remover a skin «Neon» (está em uso na orbe)."),
                card(2, "skin_preview", ToolRisk.READ, ToolCardPhase.ERROR, detail = "orb_idle_01.svg: o SVG tem <script>; scripts não são permitidos."),
                card(3, "settings_set", ToolRisk.WRITE, ToolCardPhase.BLOCKED),
                card(4, "skin_install", ToolRisk.WRITE, ToolCardPhase.CANCELLED, summary = "Instalar a skin «Cyberpunk»."),
                ChatItem.Notice(5, NoticeKind.STEP_LIMIT),
                ChatItem.AssistantMessage(6, "Parei antes de instalar. Quer que eu continue?"),
            )
        }
        Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg).padding(horizontal = 16.dp, vertical = 28.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { AgentChatEntry(it, ::labelOf) }
            }
        }
    }

    @Composable
    private fun ConfirmationRoute(destructive: Boolean) {
        val request = if (destructive) {
            ConfirmationRequest("c1", "skin_remove", ToolRisk.DESTRUCTIVE, "Remover a skin «Neon». Ela está em uso na orbe, que volta ao Ateliê.", null, canAlwaysAllow = false, ToolOrigin.CHAT)
        } else {
            ConfirmationRequest(
                "c1", "skin_install", ToolRisk.WRITE,
                "Instalar a skin «Cyberpunk» (orbe: 4 estados, 46 quadros; teclado: bandeja + 4 faces). Ela aparece em «Suas skins»; nada é escolhido sozinho.",
                ToolPreview("image/png", sheet.bytes, "2 avisos: usa <filter>, que não tem efeito neste app."), canAlwaysAllow = true, ToolOrigin.CHAT,
            )
        }
        Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg).padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            AgentConfirmationPanel(request, labelOf(request.toolName), onDecision = {})
        }
    }

    // ── a aba KeyFlow inteira ────────────────────────────────────────────────

    /** A ferramenta falsa: o resumo e a prévia que o "app" mostra, e um resultado fixo. Nunca é executada nas rotas (ninguém responde ao painel). */
    private class GalleryTool(
        override val name: String,
        override val risk: ToolRisk,
        private val summary: String,
        private val preview: ToolPreview? = null,
    ) : AgentTool {
        override val description = "Ferramenta da galeria."
        override val inputSchema = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")
        override val label: String get() = labelOf(name)

        override suspend fun describe(args: JSONObject, ctx: ToolContext) = ToolDescription(summary, preview)

        @PolicyGated
        override suspend fun run(args: JSONObject, ctx: ToolContext) = ToolOutput.ok("Feito.")
    }

    /** Um motor roteirizado: a 1ª volta pede uma ferramenta; a seguinte (nunca chega, o painel fica aberto) fecharia a conversa. */
    private fun scriptedEngine(vararg calls: Pair<String, String>): AgentEngine = object : AgentEngine {
        override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = flow {
            if (messages.none { it is AgentMessage.ToolResult }) {
                emit(AgentEvent.TextDelta("Vou preparar a skin e pedir a sua permissão."))
                calls.forEachIndexed { i, (id, name) -> emit(AgentEvent.ToolCall(id.ifEmpty { "c$i" }, name, "{}")) }
                emit(AgentEvent.Done(AgentStopReason.TOOL_USE))
            } else {
                emit(AgentEvent.TextDelta("Pronto."))
                emit(AgentEvent.Done())
            }
        }
    }

    @Composable
    private fun PanelRoute(confirming: Boolean, source: ReasoningSource) {
        val settings = FakeSettings.settings.copy(reasoningSource = source, reasoningAnthropicModel = "claude-sonnet-5-5")
        val runtime = remember(confirming) { buildRuntime(confirming) }
        Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg).padding(horizontal = 16.dp, vertical = 24.dp)) {
            KeyflowPanel(
                settings = settings,
                onSettingsChange = {},
                resolve = { ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED) },
                agent = runtime,
            )
        }
    }

    @Composable
    private fun SettingsRoute() {
        val runtime = remember { settingsRuntime() }
        Box(Modifier.fillMaxSize().background(LocalKeyFlowPalette.current.bg).padding(horizontal = 16.dp, vertical = 24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState())) { AgentSettingsSection(runtime) }
        }
    }

    /** Um registro com três ações (uma com Desfazer, uma negada, uma vinda de fora) e a configuração com uma ferramenta desligada e uma liberada sempre. */
    private fun settingsRuntime(): AgentRuntime {
        val tools = listOf(
            GalleryTool("skin_preview", ToolRisk.READ, "Pré-visualizar a skin."),
            GalleryTool("skin_install", ToolRisk.WRITE, "Instalar a skin."),
            GalleryTool("skin_remove", ToolRisk.DESTRUCTIVE, "Remover a skin."),
        )
        val log = InMemoryActionLog()
        kotlinx.coroutines.runBlocking {
            log.append(ActionLogEntry(atMs = SETTINGS_T0, toolName = "skin_install", risk = ToolRisk.WRITE, summary = "Instalar a skin «Cyberpunk» (orbe: 4 estados, 46 quadros).", outcome = ActionOutcome.DENIED, detail = "O usuário negou.", origin = ToolOrigin.CHAT))
            log.append(ActionLogEntry(atMs = SETTINGS_T0 + 60_000, toolName = "skin_install", risk = ToolRisk.WRITE, summary = "Instalar a skin «Neon Noturno» (orbe: 4 estados, 45 quadros; teclado: bandeja + 4 faces).", outcome = ActionOutcome.OK, detail = "Instalada.", origin = ToolOrigin.CHAT, undo = UndoCall("skin_remove", """{"id":"neon-noturno"}""")))
            log.append(ActionLogEntry(atMs = SETTINGS_T0 + 120_000, toolName = "skin_remove", risk = ToolRisk.DESTRUCTIVE, summary = "Remover a skin «Aurora».", outcome = ActionOutcome.OK, detail = "Removida.", origin = ToolOrigin.EXTERNAL))
        }
        val parts: AgentParts = AgentHost.assemble(
            scope = CoroutineScope(Dispatchers.Unconfined),
            tools = tools,
            policy = InMemoryPolicyConfigStore(ToolPolicyConfig(disabledTools = setOf("skin_remove"), alwaysAllowed = setOf("skin_install"))),
            actionLog = log,
            secrets = { emptyList() },
        )
        val backends = AgentBackends(
            hasTools = { true },
            anthropicApiKeyConnected = { true },
            agentEngine = { scriptedEngine() },
            resolveText = { ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED) },
        )
        return parts.runtime(backends)
    }

    private fun buildRuntime(confirming: Boolean): AgentRuntime {
        val tools = listOf(
            GalleryTool("skin_preview", ToolRisk.READ, "Pré-visualizar a skin."),
            GalleryTool("skin_install", ToolRisk.WRITE, "Instalar a skin «Cyberpunk» (orbe: 4 estados, 46 quadros; teclado: bandeja + 4 faces). Nada é escolhido sozinho.", ToolPreview("image/png", sheet.bytes, "2 avisos: usa <filter>, que não tem efeito neste app.")),
        )
        val parts: AgentParts = AgentHost.assemble(
            scope = CoroutineScope(Dispatchers.Unconfined),
            tools = tools,
            policy = InMemoryPolicyConfigStore(),
            actionLog = InMemoryActionLog(),
            secrets = { emptyList() },
        )
        val backends = AgentBackends(
            hasTools = { true },
            anthropicApiKeyConnected = { true },
            agentEngine = { scriptedEngine("c1" to "skin_install") },
            resolveText = { ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED) },
        )
        if (confirming) {
            // Roda já: o Unconfined avança até o painel esperar o usuário, e o 1º quadro nasce com a conversa pronta.
            parts.controller.send("Crie uma skin cyberpunk e instale.", AgentBackend.WithTools(scriptedEngine("c1" to "skin_install")))
        }
        return parts.runtime(backends)
    }

    /** 2 de outubro de 2026, 12:00 UTC (09:00 no fuso do emulador de captura): o registro da galeria tem horário fixo; só o fuso do aparelho muda o desenho. */
    private const val SETTINGS_T0 = 1_790_942_400_000L
}
