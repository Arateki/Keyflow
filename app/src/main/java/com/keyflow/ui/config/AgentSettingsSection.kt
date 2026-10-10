package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.agent.ActionLogEntry
import com.keyflow.agent.ActionOutcome
import com.keyflow.agent.ConfirmationMode
import com.keyflow.agent.ToolOrigin
import com.keyflow.agent.ToolRisk
import com.keyflow.agenthost.AgentRuntime
import com.keyflow.i18n.tr
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

const val AGENT_SETTINGS_TAG = "agent-settings"
const val AGENT_TOOLS_TOGGLE_TAG = "agent-tools-toggle"
const val AGENT_MCP_TOGGLE_TAG = "agent-mcp-toggle"
const val AGENT_LOG_CLEAR_TAG = "agent-log-clear"
const val AGENT_ALWAYS_REVOKE_TAG = "agent-always-revoke"
fun agentToolToggleTag(name: String) = "agent-tool-$name"
fun agentConfirmModeTag(mode: ConfirmationMode) = "agent-confirm-${mode.name.lowercase()}"
fun agentLogUndoTag(id: Long) = "agent-log-undo-$id"

/** Quantas ações o registro mostra nos Ajustes (o arquivo guarda até 300; aqui é a lista recente, sem rolagem própria). */
private const val LOG_SHOWN = 30

/**
 * A seção "Ferramentas do agente" nos Ajustes do chat da aba KeyFlow (AUI-03, TLS-05): a chave geral, o modo de confirmação, uma
 * chave por ferramenta com o risco à vista, o registro de ações (com Desfazer onde a ação tem volta) e a linha do servidor MCP externo,
 * desligada e desabilitada até a fase 3 (MCP-01: desligado por padrão).
 *
 * Só desenha o que [runtime] já tem: a configuração vem do [AgentRuntime.policy] (persistida no DataStore próprio do agente, sem
 * segredo) e o registro do [AgentRuntime.actionLog]. Mudar algo aqui vale na hora para a próxima chamada do modelo — a política lê a
 * configuração a cada decisão.
 */
@Composable
internal fun AgentSettingsSection(runtime: AgentRuntime, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val scope = rememberCoroutineScope()
    val config by runtime.policy.config.collectAsStateOnMain()
    val loaded by runtime.policy.loaded.collectAsStateOnMain()
    val log by remember(runtime) { runtime.actionLog.observe() }.collectAsStateOnMain(emptyList())
    val tools = remember(runtime) { runtime.registry.all() }

    Column(modifier.fillMaxWidth().testTag(AGENT_SETTINGS_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            tr("Ferramentas do agente"),
            color = palette.fg,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 2.dp),
        )

        ToggleRow(
            label = tr("Deixar o modelo agir no app"),
            sub = tr("Com as ferramentas, o modelo cria, mostra e instala skins dentro do KeyFlow. O que muda algo sempre pede a sua permissão."),
            checked = config.toolsEnabled,
            onCheckedChange = { on -> runtime.policy.update { it.copy(toolsEnabled = on) } },
            tag = AGENT_TOOLS_TOGGLE_TAG,
            enabled = loaded,
        )

        if (config.toolsEnabled) {
            Eyebrow(tr("Pedir permissão para"), Modifier.padding(top = 4.dp, bottom = 6.dp))
            Segmented(
                options = ConfirmationMode.entries.toList(),
                selected = config.mode,
                label = { mode -> confirmModeLabel(mode) },
                tag = ::agentConfirmModeTag,
                onSelect = { mode -> runtime.policy.update { it.copy(mode = mode) } },
                allowTwoRows = false,
            )

            Eyebrow(tr("Ferramentas"), Modifier.padding(top = 10.dp))
            for (tool in tools) {
                val on = tool.name !in config.disabledTools
                val always = tool.name in config.alwaysAllowed
                ToggleRow(
                    label = tool.label,
                    sub = riskLabel(tool.risk) + if (always) " · " + tr("permitida sempre") else "",
                    checked = on,
                    onCheckedChange = { enable ->
                        runtime.policy.update {
                            it.copy(disabledTools = if (enable) it.disabledTools - tool.name else it.disabledTools + tool.name)
                        }
                    },
                    tag = agentToolToggleTag(tool.name),
                )
            }
            if (config.alwaysAllowed.isNotEmpty()) {
                SecondaryButton(
                    text = tr("Voltar a perguntar sempre (%d)", config.alwaysAllowed.size),
                    tag = AGENT_ALWAYS_REVOKE_TAG,
                    onClick = { runtime.policy.update { it.copy(alwaysAllowed = emptySet()) } },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        // O registro vale com as ferramentas desligadas também: o que já aconteceu continua visível e desfazível.
        Eyebrow(tr("Registro de ações"), Modifier.padding(top = 12.dp, bottom = 2.dp))
        if (log.isEmpty()) {
            SettingSub(tr("Nenhuma ação do agente até agora."))
        } else {
            for (entry in log.take(LOG_SHOWN)) {
                ActionLogRow(entry, label = runtime.registry[entry.toolName]?.label ?: entry.toolName, onUndo = { runtime.controller.undo(entry) })
            }
            SecondaryButton(
                text = tr("Limpar registro"),
                tag = AGENT_LOG_CLEAR_TAG,
                onClick = { scope.launch { runtime.actionLog.clear() } },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // MCP-01: o servidor externo nasce desligado; a linha existe para ninguém procurar a opção, mas só a fase 3 a liga.
        ToggleRow(
            label = tr("Servidor MCP externo"),
            sub = tr("Expõe as mesmas ferramentas a um cliente MCP no computador, com permissão pedida aqui no aparelho. Em breve — desligado."),
            checked = false,
            onCheckedChange = {},
            tag = AGENT_MCP_TOGGLE_TAG,
            enabled = false,
        )
    }
}

/** Uma ação do registro: quando, o quê, o resumo que o APP escreveu, como terminou e, se houver volta, o Desfazer. */
@Composable
private fun ActionLogRow(entry: ActionLogEntry, label: String, onUndo: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val time = remember(entry.atMs) { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(entry.atMs)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, color = palette.faint, fontFamily = SpaceMono, fontSize = 9.5.sp)
            Text(
                label,
                color = palette.fg,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                outcomeLabel(entry.outcome) + if (entry.origin == ToolOrigin.EXTERNAL) " · " + tr("externa") else "",
                color = if (entry.outcome == ActionOutcome.OK) palette.teal else palette.muted,
                fontFamily = SpaceGrotesk,
                fontSize = 10.sp,
            )
        }
        if (entry.summary.isNotBlank()) {
            Text(
                entry.summary,
                color = palette.muted,
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.Light,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (entry.undo != null && entry.outcome == ActionOutcome.OK) {
            SecondaryButton(
                text = tr("Desfazer"),
                tag = agentLogUndoTag(entry.id),
                onClick = onUndo,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun confirmModeLabel(mode: ConfirmationMode): String = when (mode) {
    ConfirmationMode.ASK_FOR_CHANGES -> tr("O que altera algo")
    ConfirmationMode.ASK_FOR_ALL -> tr("Tudo, até ler")
}

private fun riskLabel(risk: ToolRisk): String = when (risk) {
    ToolRisk.READ -> tr("Só lê — não muda nada")
    ToolRisk.WRITE -> tr("Altera algo — pede permissão")
    ToolRisk.DESTRUCTIVE -> tr("Apaga — pede permissão sempre")
}

private fun outcomeLabel(outcome: ActionOutcome): String = when (outcome) {
    ActionOutcome.OK -> tr("feita")
    ActionOutcome.ERROR -> tr("erro")
    ActionOutcome.DENIED -> tr("negada")
    ActionOutcome.CANCELLED -> tr("cancelada")
}
