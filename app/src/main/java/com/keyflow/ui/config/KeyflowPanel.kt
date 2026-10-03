package com.keyflow.ui.config

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.agent.AgentController
import com.keyflow.agent.ChatItem
import com.keyflow.agent.AgentUiState
import com.keyflow.agenthost.AgentBackends
import com.keyflow.agenthost.AgentMode
import com.keyflow.agenthost.AgentRuntime
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.orb.OrbState
import com.keyflow.ui.orb.VoiceOrb
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val KEYFLOW_PANEL_TAG = "keyflow-panel"
const val KEYFLOW_AJUSTES_TOGGLE_TAG = "keyflow-ajustes-toggle"
const val KEYFLOW_AJUSTES_AREA_TAG = "keyflow-ajustes-area"
const val KEYFLOW_INPUT_TAG = "keyflow-chat-input"
const val KEYFLOW_SEND_BUTTON_TAG = "keyflow-send-btn"
const val KEYFLOW_ORB_TAG = "keyflow-orb"
const val KEYFLOW_CANCEL_BUTTON_TAG = "keyflow-cancel-btn"

/** Mensagem no chat do agente principal. */
data class KeyflowChatMessage(
    val id: Long = System.currentTimeMillis() + (0..9999).random(),
    val text: String,
    val isUser: Boolean,
)

/**
 * Informações da conta e assinatura premium do usuário vindas da API (KeyFlow Pro, créditos, etc.).
 */
data class KeyflowAccountInfo(
    val email: String = "contato@arateki.com",
    val name: String = "Yan",
    val planName: String = "KeyFlow Pro",
    val subscriptionStatus: String = "Assinatura ativa",
    val renewalDate: String = "15/12/2026",
    val availableCredits: Int = 1250,
    val totalCredits: Int = 2000,
    val isLoggedIn: Boolean = true,
)

/**
 * Aba KeyFlow: interação direta com o agente principal através da orbe e chat por texto,
 * com área expansível de ajustes gerais do sistema.
 *
 * A lógica do chat mora no [AgentController] (histórico, cartões de ferramenta, confirmação, cancelamento): este Composable só
 * desenha. Com [agent] o controlador é o do processo (a conversa sobrevive a trocar de aba) e o chat usa ferramentas quando a origem
 * de raciocínio as suporta; sem [agent] é o chat de sempre — uma chamada de texto por mensagem, com o controlador só desta tela.
 */
@Composable
fun KeyflowPanel(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    resolve: (Settings) -> ReasoningResolution,
    modifier: Modifier = Modifier,
    agent: AgentRuntime? = null,
) {
    val scope = rememberCoroutineScope()
    val backends = remember(agent, resolve) { agent?.backends ?: AgentBackends.plain(resolve) }
    val controller = remember(agent) { agent?.controller ?: AgentController(scope) }
    val state by controller.state.collectAsState()
    val pending by controller.pendingConfirmation.collectAsState()
    val mode by remember(backends, settings.reasoningSource, settings.reasoningAnthropicModel) { backends.modeFlow(settings) }
        .collectAsState(initial = backends.mode(settings))
    val labelOf: (String) -> String = { name -> agent?.registry?.get(name)?.label ?: name }

    // Sair da aba com uma pergunta de permissão aberta cancela a execução: ninguém aprova o que não vê. Girar a tela não conta.
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(controller) {
        onDispose { if (activity?.isChangingConfigurations != true) controller.panelHidden() }
    }

    Box(modifier.fillMaxSize()) {
        KeyflowPanelBody(
            settings = settings,
            state = state,
            mode = mode,
            agent = agent,
            labelOf = labelOf,
            onSend = { text -> controller.send(text, backends.resolve(settings)) },
            onCancel = controller::cancel,
            onClear = controller::clear,
            modifier = Modifier.fillMaxSize(),
        )
        // O painel de confirmação fica por cima de tudo, colado ao campo de texto: vale com os Ajustes abertos também.
        pending?.let { request ->
            AgentConfirmationPanel(
                request = request,
                label = labelOf(request.toolName),
                onDecision = { decision -> controller.respond(request.callId, decision) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 62.dp),
            )
        }
    }
}

@Composable
private fun KeyflowPanelBody(
    settings: Settings,
    state: AgentUiState,
    mode: AgentMode,
    agent: AgentRuntime?,
    labelOf: (String) -> String,
    onSend: (String) -> Boolean,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    var ajustesExpanded by remember { mutableStateOf(false) }

    // A orbe é do chat enquanto ele trabalha (pensando, executando, esperando a permissão); parado, um toque a alterna (como antes).
    var manualOrb by remember { mutableStateOf<OrbState>(OrbState.Idle) }
    val orbState: OrbState = if (state.busy) OrbState.Transcribing else manualOrb
    var account by remember { mutableStateOf(KeyflowAccountInfo()) }
    var inputText by remember { mutableStateOf("") }
    val isGenerating = state.busy

    val listState = rememberLazyListState()

    val tail = state.items.lastOrNull()
    LaunchedEffect(state.items.size, (tail as? ChatItem.AssistantMessage)?.text, (tail as? ChatItem.ToolCard)?.phase) {
        if (state.items.isNotEmpty()) {
            listState.animateScrollToItem(state.items.size - 1)
        }
    }

    fun sendMessage() {
        val prompt = inputText.trim()
        if (prompt.isBlank() || isGenerating) return
        if (onSend(prompt)) inputText = ""
    }

    Column(
        modifier
            .fillMaxSize()
            .testTag(KEYFLOW_PANEL_TAG)
            .padding(top = 10.dp, bottom = 12.dp),
    ) {
        // ── Cabeçalho com título e botão de ajustes no topo direito
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "KeyFlow",
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                )
                Text(
                    tr("Agente Principal"),
                    color = palette.teal,
                    fontFamily = SpaceMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (ajustesExpanded) palette.teal.copy(alpha = 0.2f) else palette.panel)
                    .border(1.dp, if (ajustesExpanded) palette.teal else palette.line2, CircleShape)
                    .clickable { ajustesExpanded = !ajustesExpanded }
                    .testTag(KEYFLOW_AJUSTES_TOGGLE_TAG),
                contentAlignment = Alignment.Center,
            ) {
                KfIcon(
                    KfIcons.Adjust,
                    tint = if (ajustesExpanded) palette.teal else palette.fg,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        // ── Área expansível de Ajustes Gerais
        AnimatedVisibility(
            visible = ajustesExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, palette.line2, RoundedCornerShape(16.dp))
                    .background(palette.panel)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
                    .testTag(KEYFLOW_AJUSTES_AREA_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. Cabeçalho de Conta & Logout
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (account.isLoggedIn) palette.teal.copy(alpha = 0.15f) else palette.bg)
                                .border(1.dp, if (account.isLoggedIn) palette.teal.copy(alpha = 0.5f) else palette.line2, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (account.isLoggedIn) {
                                Text(
                                    account.name.take(1).uppercase(),
                                    color = palette.teal,
                                    fontFamily = SpaceGrotesk,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                )
                            } else {
                                KfIcon(KfIcons.User, tint = palette.muted, modifier = Modifier.size(18.dp))
                            }
                        }

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (account.isLoggedIn) account.name else tr("Conta não conectada"),
                                    color = palette.fg,
                                    fontFamily = SpaceGrotesk,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                )
                                if (account.isLoggedIn) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(palette.teal.copy(alpha = 0.2f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            "PRO",
                                            color = palette.teal,
                                            fontFamily = SpaceMono,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            Text(
                                if (account.isLoggedIn) account.email else tr("Conecte para sincronizar planos e créditos"),
                                color = palette.muted,
                                fontFamily = SpaceMono,
                                fontSize = 10.sp,
                            )
                        }
                    }

                    if (account.isLoggedIn) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { account = account.copy(isLoggedIn = false) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                KfIcon(KfIcons.Exit, tint = palette.faint, modifier = Modifier.size(14.dp))
                                Text(
                                    tr("Sair"),
                                    color = palette.faint,
                                    fontFamily = SpaceMono,
                                    fontSize = 10.5.sp,
                                )
                            }
                        }
                    }
                }

                // 2. Detalhes de Assinatura Premium e Créditos da API
                if (account.isLoggedIn) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.bg.copy(alpha = 0.7f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    tr("Plano e Assinatura"),
                                    color = palette.muted,
                                    fontFamily = SpaceMono,
                                    fontSize = 9.5.sp,
                                )
                                Text(
                                    account.planName,
                                    color = palette.fg,
                                    fontFamily = SpaceGrotesk,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    tr("Status"),
                                    color = palette.muted,
                                    fontFamily = SpaceMono,
                                    fontSize = 9.5.sp,
                                )
                                Text(
                                    tr("Assinatura ativa"),
                                    color = palette.teal,
                                    fontFamily = SpaceGrotesk,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 12.sp,
                                )
                            }
                        }

                        // Medidor de créditos da API
                        Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    tr("Créditos Premium"),
                                    color = palette.muted,
                                    fontFamily = SpaceMono,
                                    fontSize = 9.5.sp,
                                )
                                Text(
                                    "${account.availableCredits} / ${account.totalCredits}",
                                    color = palette.fg,
                                    fontFamily = SpaceMono,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(palette.line2),
                            ) {
                                val fraction = (account.availableCredits.toFloat() / account.totalCredits.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(palette.teal),
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    tr("Renovação em %s", account.renewalDate),
                                    color = palette.faint,
                                    fontFamily = SpaceMono,
                                    fontSize = 9.sp,
                                )
                                Text(
                                    tr("Saldo na API"),
                                    color = palette.faint,
                                    fontFamily = SpaceMono,
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }

                    SecondaryButton(
                        text = tr("Gerenciar assinatura"),
                        tag = "keyflow-manage-sub",
                        onClick = { },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    PrimaryButton(
                        text = tr("Conectar conta KeyFlow"),
                        tag = "keyflow-login-btn",
                        onClick = { account = account.copy(isLoggedIn = true) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = KfIcons.User,
                    )
                }

                // 3. Capacidades Ativas do Agente
                Text(
                    tr("Capacidades Ativas do Agente"),
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )

                // Visualização das capacidades e modelos ativos atualmente
                val reasoningDesc = when (settings.reasoningSource) {
                    ReasoningSource.CREDITS -> tr("Créditos")
                    ReasoningSource.LOCAL -> settings.reasoningLocalModelId ?: "Local"
                    ReasoningSource.OPENAI -> settings.reasoningOpenAiModel.ifBlank { "OpenAI" }
                    ReasoningSource.ANTHROPIC -> settings.reasoningAnthropicModel.ifBlank { "Anthropic" }
                    ReasoningSource.GOOGLE -> settings.reasoningGoogleModel.ifBlank { "Google" }
                    ReasoningSource.CUSTOM -> "Custom"
                }
                val transDesc = when (settings.source) {
                    com.keyflow.core.TranscriptionSource.CREDITS -> tr("Créditos")
                    com.keyflow.core.TranscriptionSource.LOCAL -> "Sherpa ONNX"
                    com.keyflow.core.TranscriptionSource.OPENAI -> "OpenAI Whisper"
                    com.keyflow.core.TranscriptionSource.GROQ -> "Groq Whisper"
                    com.keyflow.core.TranscriptionSource.CUSTOM -> "Custom"
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProfileModelChip(tr("Raciocínio"), reasoningDesc, Modifier.weight(1f))
                        ProfileModelChip(tr("Transcrição"), transDesc, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProfileModelChip(tr("Visão"), settings.visionSource.name, Modifier.weight(1f))
                        ProfileModelChip(tr("Fala"), settings.falaSource.name, Modifier.weight(1f))
                    }
                }

                SettingSub(
                    tr("A configuração e autenticação dos modelos são feitas diretamente na aba de cada capacidade específica."),
                )

                // 4. Ferramentas do agente: só com o agente do processo (sem ele o chat é o de texto de sempre, sem nada a configurar).
                agent?.let { AgentSettingsSection(it) }

                SecondaryButton(
                    text = tr("Limpar conversa"),
                    tag = "keyflow-clear-chat",
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }

        // ── Orbe no topo
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.testTag(KEYFLOW_ORB_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    VoiceOrb(
                        state = orbState,
                        modifier = Modifier
                            .size(76.dp)
                            .clickable {
                                manualOrb = if (orbState == OrbState.Idle) OrbState.Listening else OrbState.Idle
                            },
                    )
                }

                Text(
                    agentCaption(state.activity, listening = manualOrb == OrbState.Listening),
                    color = palette.muted,
                    fontFamily = SpaceMono,
                    fontSize = 10.5.sp,
                )
                if (mode == AgentMode.TEXT_ONLY) AgentModeNote(Modifier.padding(horizontal = 8.dp))
            }
        }

        // ── Área de mensagens de chat (ocupa o espaço intermediário)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                AgentChatEntry(item, labelOf)
            }
        }

        // ── Campo de texto na base para enviar mensagens
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(22.dp))
                .border(1.dp, palette.line2, RoundedCornerShape(22.dp))
                .background(palette.panel)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .testTag(KEYFLOW_INPUT_TAG),
                textStyle = TextStyle(
                    color = palette.fg,
                    fontFamily = SpaceGrotesk,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(palette.teal),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                decorationBox = { innerTextField ->
                    if (inputText.isEmpty()) {
                        Text(
                            tr("Converse com o KeyFlow…"),
                            color = palette.muted,
                            fontFamily = SpaceGrotesk,
                            fontSize = 13.sp,
                        )
                    }
                    innerTextField()
                },
            )

            if (isGenerating) {
                // Durante a geração e a execução o botão vira Cancelar: interrompe o modelo e a ferramenta em andamento.
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(palette.terracotta)
                        .clickable { onCancel() }
                        .semantics { contentDescription = tr("Cancelar") }
                        .testTag(KEYFLOW_CANCEL_BUTTON_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    KfIcon(KfIcons.Close, tint = palette.bg, modifier = Modifier.size(15.dp), strokeWidth = 2.2f)
                }
            } else {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (inputText.isNotBlank()) palette.teal else palette.line2.copy(alpha = 0.5f))
                        .clickable(enabled = inputText.isNotBlank()) { sendMessage() }
                        .testTag(KEYFLOW_SEND_BUTTON_TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    KfIcon(
                        KfIcons.Return,
                        tint = if (inputText.isNotBlank()) palette.bg else palette.muted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileModelChip(category: String, model: String, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.bg.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(category, color = palette.muted, fontFamily = SpaceMono, fontSize = 9.sp)
        Text(
            model,
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ChatMessageItem(message: KeyflowChatMessage) {
    val palette = LocalKeyFlowPalette.current
    val isUser = message.isUser

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.85f)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 14.dp,
                    ),
                )
                .background(if (isUser) palette.teal.copy(alpha = 0.16f) else palette.panel)
                .border(
                    1.dp,
                    if (isUser) palette.teal.copy(alpha = 0.4f) else palette.line2,
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 14.dp,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(
                message.text,
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Normal,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
            )
        }
    }
}
