package com.keyflow.ui.config

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.agent.AgentActivity
import com.keyflow.agent.ChatItem
import com.keyflow.agent.ConfirmationDecision
import com.keyflow.agent.ConfirmationRequest
import com.keyflow.agent.NoticeKind
import com.keyflow.agent.ToolCardPhase
import com.keyflow.agent.ToolImage
import com.keyflow.agent.ToolRisk
import com.keyflow.i18n.tr
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.KeyFlowPalette
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

const val AGENT_CONFIRM_PANEL_TAG = "agent-confirm-panel"
const val AGENT_CONFIRM_ALLOW_TAG = "agent-confirm-allow"
const val AGENT_CONFIRM_ALWAYS_TAG = "agent-confirm-always"
const val AGENT_CONFIRM_DENY_TAG = "agent-confirm-deny"
const val AGENT_MODE_NOTE_TAG = "agent-mode-note"

fun agentCardTag(callId: String) = "agent-card-$callId"
fun agentCardPhaseTag(callId: String) = "agent-card-phase-$callId"
fun agentCardImageTag(callId: String) = "agent-card-image-$callId"

/**
 * Uma linha do chat do agente (AUI-01): balões de conversa, cartões de ferramenta e avisos do app. [labelOf] traduz o nome que o modelo
 * usa de uma ferramenta (`skin_install`) no nome que o usuário lê ("Instalar skin"); sem ele, o próprio nome.
 */
@Composable
fun AgentChatEntry(item: ChatItem, labelOf: (String) -> String = { it }, modifier: Modifier = Modifier) {
    when (item) {
        is ChatItem.Greeting -> ChatMessageItem(
            KeyflowChatMessage(item.id, tr("Olá! Sou o agente KeyFlow. Como posso ajudar com sua produtividade, comandos ou automações hoje?"), isUser = false),
        )
        is ChatItem.UserMessage -> ChatMessageItem(KeyflowChatMessage(item.id, item.text, isUser = true))
        is ChatItem.AssistantMessage -> ChatMessageItem(KeyflowChatMessage(item.id, item.text, isUser = false))
        is ChatItem.ToolCard -> ToolCallCard(item, labelOf(item.toolName), modifier)
        is ChatItem.Notice -> AgentNotice(item.kind, modifier)
    }
}

/**
 * O cartão de uma chamada de ferramenta (AUI-01): o nome, o risco, o estado (pedindo permissão / executando / feito / negado / erro /
 * desligada / cancelado), o resumo que o APP escreveu — nunca a frase do modelo sobre a própria ação — e a pré-visualização da skin quando há.
 */
@Composable
internal fun ToolCallCard(card: ChatItem.ToolCard, label: String, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val phaseColor = phaseColor(card.phase, palette)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.bg.copy(alpha = 0.55f))
                .border(1.dp, phaseColor.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag(agentCardTag(card.callId)),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    card.risk?.let { RiskLabel(it) }
                }
                PhasePill(card.phase, agentCardPhaseTag(card.callId))
            }
            card.summary?.let {
                Text(it, color = palette.muted, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 11.sp, lineHeight = 15.sp)
            }
            card.image?.let { SheetImage(it, Modifier.testTag(agentCardImageTag(card.callId))) }
            card.detail?.let {
                Text(it, color = palette.faint, fontFamily = SpaceMono, fontSize = 9.5.sp, lineHeight = 13.sp, maxLines = 3)
            }
        }
    }
}

/** O risco da ferramenta, em palavras: o usuário vê o que está em jogo antes mesmo de ler o resumo. */
@Composable
internal fun RiskLabel(risk: ToolRisk, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val (text, color) = when (risk) {
        ToolRisk.READ -> tr("só lê") to palette.muted
        ToolRisk.WRITE -> tr("altera o app") to palette.teal
        ToolRisk.DESTRUCTIVE -> tr("remove") to palette.terracotta
    }
    Text(text, color = color, fontFamily = SpaceMono, fontSize = 9.sp, modifier = modifier)
}

@Composable
private fun PhasePill(phase: ToolCardPhase, tag: String, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val color = phaseColor(phase, palette)
    val label = when (phase) {
        ToolCardPhase.AWAITING_CONFIRMATION -> tr("Pedindo permissão")
        ToolCardPhase.RUNNING -> tr("Executando…")
        ToolCardPhase.DONE -> tr("Feito")
        ToolCardPhase.DENIED -> tr("Negado")
        ToolCardPhase.ERROR -> tr("Erro")
        ToolCardPhase.BLOCKED -> tr("Desligada nos Ajustes")
        ToolCardPhase.CANCELLED -> tr("Cancelado")
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (phase == ToolCardPhase.DONE) KfIcon(KfIcons.Check, color, Modifier.size(10.dp), strokeWidth = 2.4f)
        else Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        // A marca de teste fica no TEXTO do estado (é ele que os testes leem).
        Text(label, color = color, fontFamily = SpaceMono, fontSize = 9.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(tag))
    }
}

private fun phaseColor(phase: ToolCardPhase, palette: KeyFlowPalette): Color = when (phase) {
    ToolCardPhase.AWAITING_CONFIRMATION -> palette.record
    ToolCardPhase.RUNNING, ToolCardPhase.DONE -> palette.teal
    ToolCardPhase.ERROR -> palette.terracotta
    ToolCardPhase.DENIED, ToolCardPhase.BLOCKED, ToolCardPhase.CANCELLED -> palette.faint
}

/** A folha de contato (ou qualquer prévia PNG) do app. Decodificada na hora: é uma imagem pequena e o quadro tem de sair completo. */
@Composable
internal fun SheetImage(image: ToolImage, modifier: Modifier = Modifier, maxHeight: Dp = 200.dp) {
    val palette = LocalKeyFlowPalette.current
    val bitmap: ImageBitmap? = remember(image) {
        runCatching { BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.asImageBitmap() }.getOrNull()
    }
    val description = tr("Pré-visualização")
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, palette.line2, RoundedCornerShape(8.dp)),
        )
    }
}

/** Aviso do app no chat (limite de passos, resposta cortada, recusa): discreto, não é fala do modelo. */
@Composable
internal fun AgentNotice(kind: NoticeKind, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val text = when (kind) {
        NoticeKind.STEP_LIMIT -> tr("Cheguei ao limite de passos desta mensagem. Peça para continuar, se quiser.")
        NoticeKind.TRUNCATED -> tr("A resposta foi cortada por limite de tamanho.")
        NoticeKind.REFUSED -> tr("O modelo recusou responder a isto.")
        NoticeKind.GENERATION_ERROR -> tr("Erro durante a geração.")
    }
    Text(
        text,
        color = palette.faint,
        fontFamily = SpaceMono,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/**
 * A nota discreta do modo só texto (AGT-05): o chat segue como sempre, e uma linha diz onde as ferramentas existem — em vez de elas
 * simplesmente não aparecerem.
 */
@Composable
internal fun AgentModeNote(modifier: Modifier = Modifier) {
    Text(
        tr("Modo só texto · ferramentas disponíveis com a Anthropic por chave de API"),
        color = LocalKeyFlowPalette.current.faint,
        fontFamily = SpaceMono,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        textAlign = TextAlign.Center,
        modifier = modifier.testTag(AGENT_MODE_NOTE_TAG),
    )
}

/** A legenda sob a orbe: o que o agente está fazendo (AUI-04). */
@Composable
internal fun agentCaption(activity: AgentActivity, listening: Boolean): String = when (activity) {
    AgentActivity.THINKING -> tr("Pensando…")
    AgentActivity.RUNNING_TOOL -> tr("Executando ferramenta…")
    AgentActivity.AWAITING_CONFIRMATION -> tr("Aguardando sua permissão…")
    AgentActivity.IDLE -> if (listening) tr("Ouvindo…") else tr("Pronto · Digite abaixo para interagir")
}

/**
 * O painel de confirmação (AUI-02, TLS-02): o que o agente quer fazer, com o resumo e a prévia que o APP escreveu — nunca a frase do
 * modelo —, e **Negar** / **Permitir**; **Permitir sempre esta ferramenta** só quando o pedido o oferece (nunca para quem remove algo).
 * Fica por cima do chat, colado ao campo de texto: ninguém aprova o que não vê, nem com os Ajustes abertos.
 */
@Composable
fun AgentConfirmationPanel(
    request: ConfirmationRequest,
    label: String,
    onDecision: (ConfirmationDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val accent = if (request.risk == ToolRisk.DESTRUCTIVE) palette.terracotta else palette.record
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp)
            .clip(shape)
            .background(palette.card)
            .border(1.5.dp, accent, shape)
            .padding(14.dp)
            .testTag(AGENT_CONFIRM_PANEL_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr("O agente quer fazer isto"), color = accent, fontFamily = SpaceMono, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                RiskLabel(request.risk)
            }
            Text(request.summary, color = palette.fg2, fontFamily = SpaceGrotesk, fontSize = 12.sp, lineHeight = 17.sp)
            request.preview?.let { preview ->
                val image = remember(preview) { runCatching { ToolImage(preview.mediaType, preview.bytes) }.getOrNull() }
                if (image != null) SheetImage(image, maxHeight = 140.dp)
                preview.caption?.let {
                    Text(it, color = palette.muted, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Light, fontSize = 10.5.sp, lineHeight = 15.sp)
                }
            }
            if (request.risk == ToolRisk.DESTRUCTIVE) {
                Text(
                    tr("Esta ação remove algo. O agente sempre pergunta antes, e nenhum ajuste muda isso."),
                    color = palette.terracotta,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Light,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = tr("Negar"),
                tag = AGENT_CONFIRM_DENY_TAG,
                onClick = { onDecision(ConfirmationDecision.DENY) },
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = tr("Permitir"),
                tag = AGENT_CONFIRM_ALLOW_TAG,
                onClick = { onDecision(ConfirmationDecision.ALLOW) },
                modifier = Modifier.weight(1f),
                icon = KfIcons.Check,
            )
        }
        if (request.canAlwaysAllow) {
            Text(
                tr("Permitir sempre esta ferramenta"),
                color = palette.teal,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onDecision(ConfirmationDecision.ALLOW_ALWAYS) }
                    .padding(vertical = 6.dp)
                    .testTag(AGENT_CONFIRM_ALWAYS_TAG),
            )
        }
    }
}
