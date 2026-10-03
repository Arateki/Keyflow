package com.keyflow.agent

/**
 * Consertos e poda do histórico neutro, antes de ele ir ao modelo. Puro (JVM). O que é mostrado ao usuário não passa por aqui: estas
 * funções devolvem a CÓPIA que sai do app.
 */
object AgentHistory {

    /** O resultado de [repair]: a lista consertada e os resultados sintéticos que foram acrescentados (quem guarda o histórico os guarda também). */
    class Repaired(val messages: List<AgentMessage>, val added: List<AgentMessage.ToolResult>)

    const val INTERRUPTED = "Interrompida: a conversa seguiu antes desta ferramenta devolver um resultado (o usuário cancelou)."

    /**
     * Garante a invariante que as APIs de ferramenta exigem: toda chamada de uma volta do modelo tem exatamente um resultado logo
     * depois, e nenhum resultado sobra sem chamada. Um cancelamento no meio de uma volta deixa chamadas sem resultado; um defeito
     * pode deixar resultados órfãos. Aqui as chamadas sem resultado ganham um resultado de erro ([INTERRUPTED]) e os órfãos saem —
     * sem isso a próxima requisição seria recusada com 400 e a conversa travaria.
     */
    fun repair(messages: List<AgentMessage>): Repaired {
        val out = ArrayList<AgentMessage>(messages.size)
        val added = ArrayList<AgentMessage.ToolResult>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            when (m) {
                is AgentMessage.Assistant -> {
                    out += m
                    i++
                    // O grupo de resultados que segue esta volta.
                    val wanted = m.toolCalls.map { it.id }
                    val seen = LinkedHashSet<String>()
                    while (i < messages.size && messages[i] is AgentMessage.ToolResult) {
                        val r = messages[i] as AgentMessage.ToolResult
                        if (r.callId in wanted && seen.add(r.callId)) out += r
                        i++
                    }
                    for (id in wanted) {
                        if (id !in seen) {
                            val synthetic = AgentMessage.ToolResult(id, INTERRUPTED, isError = true)
                            out += synthetic
                            added += synthetic
                        }
                    }
                }
                // Resultado que não segue uma volta com a chamada correspondente: órfão.
                is AgentMessage.ToolResult -> i++
                is AgentMessage.User -> {
                    out += m
                    i++
                }
            }
        }
        return Repaired(out, added)
    }

    /** O que sobra de um resultado ANTIGO de ferramenta quando a conversa aperta. */
    private const val OLD_RESULT_KEEP = 400

    const val OMITTED_NOTE = "[Nota do app: parte do meio desta conversa foi omitida para caber no contexto.]"

    /**
     * A poda por tamanho aproximado (D10, AGT-01): se a conversa passa de [maxChars] caracteres, (1) resume os resultados GRANDES de
     * ferramenta das voltas antigas e, se ainda passar, (2) tira as voltas mais antigas inteiras — mantendo a primeira mensagem do
     * usuário (o início) e as voltas mais recentes, e deixando uma nota no lugar. A volta atual (a última) nunca é tocada, e voltas
     * saem INTEIRAS, então chamada e resultado nunca se separam. Chamar só no começo de uma execução: mexer no histórico no meio dela
     * mudaria o prefixo que o provedor já viu.
     */
    fun prune(messages: List<AgentMessage>, maxChars: Int): List<AgentMessage> {
        if (messages.sumOf(::sizeOf) <= maxChars) return messages
        val turns = turnsOf(messages)
        if (turns.size <= 1) return messages

        // 1. Resultados grandes das voltas antigas, resumidos.
        val trimmed = turns.mapIndexed { index, turn -> if (index == turns.lastIndex) turn else turn.map(::trimOldResult) }
        if (trimmed.sumOf { t -> t.sumOf(::sizeOf) } <= maxChars) return trimmed.flatten()

        // 2. Voltas inteiras, da mais antiga para a mais nova, até caber — sempre guardando a última e o começo da primeira.
        val first = trimmed.first().first()
        var budget = maxChars - sizeOf(first) - OMITTED_NOTE.length
        val kept = ArrayDeque<List<AgentMessage>>()
        for (turn in trimmed.drop(1).asReversed()) {
            val size = turn.sumOf(::sizeOf)
            if (kept.isNotEmpty() && size > budget) break
            kept.addFirst(turn)
            budget -= size
        }
        val keptCount = kept.size
        val droppedAny = keptCount < trimmed.size - 1 || trimmed.first().size > 1
        val head = if (droppedAny) listOf(first, AgentMessage.User(OMITTED_NOTE)) else trimmed.first()
        return head + kept.flatten()
    }

    /** As voltas: cada uma começa numa mensagem do usuário e vai até antes da seguinte. */
    private fun turnsOf(messages: List<AgentMessage>): List<List<AgentMessage>> {
        val turns = ArrayList<MutableList<AgentMessage>>()
        for (m in messages) {
            if (m is AgentMessage.User || turns.isEmpty()) turns += mutableListOf<AgentMessage>()
            turns.last() += m
        }
        return turns
    }

    private fun trimOldResult(m: AgentMessage): AgentMessage {
        if (m !is AgentMessage.ToolResult || m.content.length <= OLD_RESULT_KEEP) return m
        val omitted = m.content.length - OLD_RESULT_KEEP
        return m.copy(content = m.content.take(OLD_RESULT_KEEP) + "\n[… resultado antigo resumido: $omitted caracteres omitidos]")
    }

    private fun sizeOf(m: AgentMessage): Int = when (m) {
        is AgentMessage.User -> m.text.length
        is AgentMessage.Assistant -> m.text.length + m.toolCalls.sumOf { it.name.length + it.argsJson.length }
        is AgentMessage.ToolResult -> m.content.length
    }
}
