package com.keyflow.agenthost

import com.keyflow.agent.ActionLog
import com.keyflow.agent.AgentController
import com.keyflow.agent.PolicyConfigStore
import com.keyflow.agent.ToolRegistry

/**
 * O agente como a aba KeyFlow o vê: o controlador do chat (que vive no PROCESSO, não no Composable — a conversa sobrevive a trocar de
 * aba e a girar a tela), o catálogo de ferramentas (para a lista dos Ajustes), a configuração e o registro de ações persistidos, e quem
 * escolhe o modo da próxima mensagem ([backends]). `null` em `KeyflowPanel` = sem agente: o chat de sempre.
 */
class AgentRuntime(
    val controller: AgentController,
    val registry: ToolRegistry,
    val policy: PolicyConfigStore,
    val actionLog: ActionLog,
    val backends: AgentBackends,
)
