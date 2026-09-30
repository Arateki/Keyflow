package com.keyflow.reasoning

import kotlinx.coroutines.flow.Flow

/**
 * O que se pede a um modelo de linguagem. Texto → texto: sem histórico de conversa (YAGNI — o Testar é uma frase).
 * [system] é a instrução de comportamento; [user] é o pedido.
 */
data class ReasoningRequest(val system: String? = null, val user: String)

/** O que um [ReasoningEngine] emite enquanto responde: trechos, e no fim [Done] **ou** [Error]. */
sealed interface ReasoningEvent {
    /** Mais um trecho da resposta (chega em fluxo, na ordem). */
    data class Delta(val text: String) : ReasoningEvent

    /** A resposta terminou com sucesso. Nada mais é emitido depois. */
    data object Done : ReasoningEvent

    /** A resposta falhou. O que já chegou em [Delta] continua valendo; nada mais é emitido depois. */
    data class Error(val kind: ReasoningError, val message: String?) : ReasoningEvent
}

/** Por que a resposta falhou — o que a tela traduz em mensagem legível (RTS-04). */
enum class ReasoningError { OOM, MODEL_MISSING, LOCAL, NETWORK, UNAUTHORIZED, RATE_LIMIT, NOT_ELIGIBLE, ENGINE }

/**
 * Quem responde: o modelo local (processo `:reasoning`) ou a OpenAI hoje; qualquer outro amanhã. A UI e os futuros
 * consumidores só conhecem este contrato (RRUN-01).
 *
 * Cancelar a coleta do [Flow] cancela a geração (RRUN-06).
 */
interface ReasoningEngine {
    fun generate(request: ReasoningRequest): Flow<ReasoningEvent>
}
