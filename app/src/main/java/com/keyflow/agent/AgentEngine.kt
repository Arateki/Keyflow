package com.keyflow.agent

import com.keyflow.reasoning.ReasoningError
import kotlinx.coroutines.flow.Flow

/** Por que o modelo parou, no vocabulário do app (cada adaptador traduz o do provedor). */
enum class AgentStopReason {
    /** Terminou a resposta. */
    END_TURN,

    /** Parou para pedir uma ou mais ferramentas. */
    TOOL_USE,

    /** Foi cortado: bateu no limite de tokens da resposta ou a janela de contexto acabou. Uma chamada que estava no meio NÃO é confiável. */
    TRUNCATED,

    /** O provedor recusou responder. As ferramentas desta volta NÃO rodam. */
    REFUSAL,

    /** Qualquer outro motivo que o app não trata. */
    OTHER,
}

/** Tokens gastos numa volta, quando o provedor informa (D10). */
data class TokenUsage(val inputTokens: Int, val outputTokens: Int)

/** O que um [AgentEngine] emite enquanto responde: trechos de texto, chamadas de ferramenta, e no fim [Done] **ou** [Error]. */
sealed interface AgentEvent {
    /** Mais um trecho do texto da resposta (chega em fluxo, na ordem). */
    data class TextDelta(val text: String) : AgentEvent

    /** Uma chamada COMPLETA de ferramenta (o motor junta os pedaços do JSON antes de emitir). Quem executa é o [AgentLoop], não o motor. */
    data class ToolCall(val id: String, val name: String, val argsJson: String) : AgentEvent

    /** A volta terminou com sucesso. Nada mais é emitido depois. */
    data class Done(
        val stopReason: AgentStopReason = AgentStopReason.END_TURN,
        val usage: TokenUsage? = null,
        /** O que o provedor quer de volta junto da volta (ver [ProviderState]); `null` quando não há nada a preservar. */
        val providerState: ProviderState? = null,
    ) : AgentEvent

    /**
     * A volta falhou. O que já chegou continua valendo para a tela, mas a volta NÃO entra no histórico e nenhuma ferramenta dela roda.
     * Os tipos são os de hoje ([ReasoningError]) para a UI traduzir do mesmo jeito (AGT-06).
     */
    data class Error(val kind: ReasoningError, val message: String?) : AgentEvent
}

/**
 * Quem conversa com o modelo COM ferramentas. Contrato à parte do [com.keyflow.reasoning.ReasoningEngine] (texto → texto), que fica
 * intocado (D1, AGT-02). O motor só traduz: monta a requisição do provedor, lê a resposta em fluxo e emite [AgentEvent]. Quem decide o
 * que executar é o [AgentLoop].
 *
 * Cancelar a coleta do [Flow] cancela a geração.
 */
interface AgentEngine {
    /**
     * Uma volta: envia [messages] (que terminam numa mensagem do usuário ou num resultado de ferramenta), com as [tools] que o modelo
     * pode pedir e o prompt de [system].
     */
    fun chat(
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        system: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
    ): Flow<AgentEvent>

    companion object {
        /**
         * O teto de tokens da resposta (A5). Folgado de propósito: uma skin inteira em SVG é longa, e nos modelos com raciocínio os
         * tokens de raciocínio contam neste mesmo teto.
         */
        const val DEFAULT_MAX_TOKENS = 16_384
    }
}
