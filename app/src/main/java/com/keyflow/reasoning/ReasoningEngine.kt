package com.keyflow.reasoning

import kotlinx.coroutines.flow.Flow

/**
 * Como gerar, quando quem pede quer controlar (a bancada de testes: temperatura 0 e semente fixa). **Tudo nulo = o padrão do motor =
 * o comportamento de sempre**: nenhum consumidor existente passa opções e nenhum corpo de requisição muda (BEX-02). O motor responde
 * com um [ReasoningEvent.Metrics] dizendo o que aplicou e o que ignorou — nunca ignora em silêncio.
 */
data class GenerationOptions(
    val temperature: Double? = null,
    val topK: Int? = null,
    val topP: Double? = null,
    val seed: Int? = null,
    val maxOutputTokens: Int? = null,
    val thinking: Boolean? = null,
) {
    /** Nenhuma opção pedida: o motor se comporta como sempre. */
    val isDefault: Boolean get() = this == DEFAULT

    /** Os nomes das opções pedidas (as que não são nulas), na ordem fixa — o que o motor precisa aplicar ou declarar ignorado. */
    fun requestedNames(): List<String> = buildList {
        if (temperature != null) add(TEMPERATURE)
        if (topK != null) add(TOP_K)
        if (topP != null) add(TOP_P)
        if (seed != null) add(SEED)
        if (maxOutputTokens != null) add(MAX_OUTPUT_TOKENS)
        if (thinking != null) add(THINKING)
    }

    /** As opções pedidas como `nome → valor` em texto, para gravar no resultado. */
    fun requestedValues(): Map<String, String> = buildMap {
        temperature?.let { put(TEMPERATURE, it.toString()) }
        topK?.let { put(TOP_K, it.toString()) }
        topP?.let { put(TOP_P, it.toString()) }
        seed?.let { put(SEED, it.toString()) }
        maxOutputTokens?.let { put(MAX_OUTPUT_TOKENS, it.toString()) }
        thinking?.let { put(THINKING, it.toString()) }
    }

    companion object {
        val DEFAULT = GenerationOptions()
        const val TEMPERATURE = "temperature"
        const val TOP_K = "topK"
        const val TOP_P = "topP"
        const val SEED = "seed"
        const val MAX_OUTPUT_TOKENS = "maxOutputTokens"
        const val THINKING = "thinking"
    }
}

/**
 * O que se pede a um modelo de linguagem. Texto → texto: sem histórico de conversa (YAGNI — o Testar é uma frase).
 * [system] é a instrução de comportamento; [user] é o pedido; [options] só a bancada preenche (nulo = padrão do motor).
 */
data class ReasoningRequest(val system: String? = null, val user: String, val options: GenerationOptions = GenerationOptions())

/** O que um [ReasoningEngine] emite enquanto responde: trechos, e no fim [Done] **ou** [Error]. */
sealed interface ReasoningEvent {
    /** Mais um trecho da resposta (chega em fluxo, na ordem). */
    data class Delta(val text: String) : ReasoningEvent

    /**
     * O que o motor fez com as [GenerationOptions] e o que mediu, emitido **antes** de [Done] (ou de [Error]) por quem sabe dizer; um
     * motor que não emite nada deixa a bancada marcar tudo o que pediu como ignorado. Quem só quer o texto (a tela do Testar, o chat)
     * ignora este evento. Valor que o motor não conhece vai `null` — nunca inventado.
     */
    data class Metrics(
        /** `nome → valor` das opções que o motor realmente usou. */
        val applied: Map<String, String> = emptyMap(),
        /** Os nomes das opções pedidas que o motor não usou. */
        val ignored: Set<String> = emptySet(),
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val tokensPerSecond: Double? = null,
        /** Tempo até o 1º trecho medido PELO motor (o LiteRT-LM o informa); a bancada mede o seu por fora. */
        val engineFirstTokenMs: Long? = null,
    ) : ReasoningEvent

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
