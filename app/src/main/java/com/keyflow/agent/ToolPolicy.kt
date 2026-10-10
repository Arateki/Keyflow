package com.keyflow.agent

/** Quando o app pergunta antes de rodar uma ferramenta. */
enum class ConfirmationMode {
    /** O padrão (A2): `READ` roda direto; `WRITE` e `DESTRUCTIVE` pedem permissão. */
    ASK_FOR_CHANGES,

    /** O mais estrito: pergunta até para ler. */
    ASK_FOR_ALL,
}

/**
 * O que os Ajustes do chat dizem sobre as ferramentas (AUI-03): chave geral, modo, uma chave por ferramenta e quais `WRITE` o usuário
 * liberou com "permitir sempre". Dado puro — a persistência é da fase 1B, e o [ToolPolicy] lê esta configuração A CADA decisão, por
 * isso desligar uma ferramenta vale na hora (até para uma conversa em andamento).
 *
 * O padrão de uma ferramenta nova é LIGADA (só as desligadas ficam na lista): ela continua protegida pela confirmação.
 */
data class ToolPolicyConfig(
    /** A chave geral: desligada, o agente não tem ferramenta nenhuma. */
    val toolsEnabled: Boolean = true,
    val mode: ConfirmationMode = ConfirmationMode.ASK_FOR_CHANGES,
    val disabledTools: Set<String> = emptySet(),
    val alwaysAllowed: Set<String> = emptySet(),
)

/** Por que uma ferramenta nem foi considerada. */
enum class DenyReason { TOOLS_OFF, TOOL_OFF }

/** A decisão da [ToolPolicy]: rodar direto, pedir permissão ou negar. */
sealed interface PolicyDecision {
    data object Run : PolicyDecision

    /** [canAlwaysAllow]: o painel pode oferecer "permitir sempre" (só `WRITE`, só no chat). */
    data class Ask(val canAlwaysAllow: Boolean) : PolicyDecision

    data class Deny(val reason: DenyReason) : PolicyDecision
}

/**
 * A política central (D2): a matriz risco × modo × ferramenta ligada × origem → rodar, perguntar ou negar. É o ÚNICO lugar que
 * decide isso; o [ToolExecutor] a consulta antes de cada execução e de novo depois da resposta do usuário.
 *
 *  | ligada? | risco        | modo            | decisão                                                         |
 *  | ------- | ------------ | --------------- | --------------------------------------------------------------- |
 *  | não     | qualquer     | qualquer        | negar                                                           |
 *  | sim     | `READ`       | `ASK_FOR_CHANGES` | rodar                                                         |
 *  | sim     | `READ`       | `ASK_FOR_ALL`   | perguntar (sem "permitir sempre")                               |
 *  | sim     | `WRITE`      | `ASK_FOR_CHANGES` | perguntar — rodar direto só se o usuário liberou "sempre" e veio do chat |
 *  | sim     | `WRITE`      | `ASK_FOR_ALL`   | perguntar (sem "permitir sempre")                               |
 *  | sim     | `DESTRUCTIVE`| qualquer        | perguntar SEMPRE, sem "permitir sempre" — nem se a lista disser que sim |
 *
 * O "permitir sempre" nunca vale para uma chamada vinda de fora ([ToolOrigin.EXTERNAL], o servidor MCP da fase 3): foi concedido no
 * chat, e um cliente externo não herda a confiança do usuário diante da tela.
 */
class ToolPolicy(
    private val config: () -> ToolPolicyConfig,
    /** Avisa quem persiste (os Ajustes) que o usuário escolheu "permitir sempre" para [toolName]. */
    private val onAlwaysAllow: (toolName: String) -> Unit = {},
) {

    /** A ferramenta está ligada (chave geral e chave própria)? É o filtro da lista que vai ao modelo. */
    fun isEnabled(toolName: String): Boolean = config().let { it.toolsEnabled && toolName !in it.disabledTools }

    fun decide(tool: AgentTool, origin: ToolOrigin): PolicyDecision {
        val cfg = config()
        if (!cfg.toolsEnabled) return PolicyDecision.Deny(DenyReason.TOOLS_OFF)
        if (tool.name in cfg.disabledTools) return PolicyDecision.Deny(DenyReason.TOOL_OFF)
        return when (tool.risk) {
            ToolRisk.DESTRUCTIVE -> PolicyDecision.Ask(canAlwaysAllow = false)
            ToolRisk.WRITE -> when {
                cfg.mode == ConfirmationMode.ASK_FOR_ALL -> PolicyDecision.Ask(canAlwaysAllow = false)
                origin == ToolOrigin.CHAT && tool.name in cfg.alwaysAllowed -> PolicyDecision.Run
                else -> PolicyDecision.Ask(canAlwaysAllow = origin == ToolOrigin.CHAT)
            }
            ToolRisk.READ -> if (cfg.mode == ConfirmationMode.ASK_FOR_ALL) PolicyDecision.Ask(canAlwaysAllow = false) else PolicyDecision.Run
        }
    }

    /**
     * O usuário escolheu "permitir sempre". Só registra se a ferramenta é `WRITE` e a chamada veio do chat; devolve se registrou — um
     * `DESTRUCTIVE` que receba esta resposta (um painel com defeito) roda UMA vez e nada se grava.
     */
    fun grantAlways(tool: AgentTool, origin: ToolOrigin): Boolean {
        if (tool.risk != ToolRisk.WRITE || origin != ToolOrigin.CHAT) return false
        onAlwaysAllow(tool.name)
        return true
    }
}
