package com.keyflow.agent

/** Uma chamada de ferramenta que o modelo pediu: o id (para casar com o resultado), o nome e os argumentos como o JSON que ele escreveu. */
data class AgentToolCall(val id: String, val name: String, val argsJson: String)

/**
 * Dado opaco que UM provedor exige de volta, intacto, na volta seguinte — hoje, os blocos `thinking`/`redacted_thinking` assinados
 * da Anthropic, que numa volta de ferramenta têm de ser reenviados sem mudança (senão a API recusa com 400). O app não interpreta:
 * só guarda junto da mensagem e devolve ao mesmo [provider]. Ao trocar de origem no meio da conversa, o dado do outro provedor é
 * ignorado e a mensagem é reconstruída do texto e das chamadas (D8).
 */
data class ProviderState(val provider: String, val payload: String)

/**
 * O histórico no formato NEUTRO do app (D8): cada adaptador converte para o formato do provedor. Três papéis bastam — o resultado de
 * uma ferramenta é uma mensagem própria, ligada à chamada pelo id.
 */
sealed interface AgentMessage {
    /** O que o usuário escreveu no chat. É a única origem de instrução (TLS-04). */
    data class User(val text: String) : AgentMessage

    /** Uma volta do modelo: texto e/ou chamadas de ferramenta. */
    data class Assistant(
        val text: String,
        val toolCalls: List<AgentToolCall> = emptyList(),
        val providerState: ProviderState? = null,
    ) : AgentMessage

    /** O resultado de [callId]: DADO devolvido ao modelo, nunca instrução. [content] já passou pelo filtro de segredos. */
    data class ToolResult(val callId: String, val content: String, val isError: Boolean = false) : AgentMessage
}
