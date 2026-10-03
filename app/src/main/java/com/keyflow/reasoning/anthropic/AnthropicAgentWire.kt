package com.keyflow.reasoning.anthropic

import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentHistory
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.ProviderState
import com.keyflow.agent.TokenUsage
import com.keyflow.agent.ToolDefinition
import com.keyflow.reasoning.ReasoningError
import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * O formato da Messages API da Anthropic para o agente: o corpo da requisição (com `tools`, `tool_use` e `tool_result`) e a leitura dos
 * eventos SSE. Puro (JVM): quem fala com a rede é o [AnthropicAgentEngine].
 *
 * Conferido na documentação oficial em 2026-10-03 (nada presumido):
 *  - `platform.claude.com/docs/en/build-with-claude/streaming` — fluxo de eventos (`message_start`, `content_block_start/delta/stop`,
 *    `message_delta`, `message_stop`, `ping`, `error`), `input_json_delta` com `partial_json`, `thinking_delta`/`signature_delta`,
 *    "novos tipos de evento podem surgir; ignore os desconhecidos";
 *  - `…/agents-and-tools/tool-use/handle-tool-calls` — `tool_result` (`tool_use_id`, `content`, `is_error`), os resultados vão numa
 *    ÚNICA mensagem do usuário, com os `tool_result` ANTES de qualquer texto;
 *  - `…/agents-and-tools/tool-use/define-tools` — `name` casa `^[a-zA-Z0-9_-]{1,128}$`, `input_schema`, `eager_input_streaming`;
 *  - `…/build-with-claude/thinking` — numa volta de ferramenta os blocos `thinking`/`redacted_thinking` voltam COMPLETOS e sem mudança;
 *  - `…/build-with-claude/handling-stop-reasons` — os valores de `stop_reason`.
 */
internal object AnthropicAgentWire {
    /** O nome do provedor no [ProviderState]. */
    const val PROVIDER = "anthropic"

    /** O prefixo que o endpoint do login (OAuth) exige no `system`, como no motor de texto ([AnthropicReasoningEngine]). */
    private const val OAUTH_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."

    /** O maior JSON de argumentos de UMA chamada que se aceita juntar em memória (o teto de tokens da resposta já limita; isto é a cinta). */
    const val MAX_TOOL_INPUT_CHARS = 4 * 1024 * 1024

    fun body(
        model: String,
        system: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int,
        isOAuth: Boolean,
        eagerInputStreaming: Boolean,
    ): String = JSONObject().apply {
        put("model", model)
        put("max_tokens", maxTokens)
        put("stream", true)
        put("system", if (isOAuth) "$OAUTH_IDENTITY\n\n$system" else system)
        if (tools.isNotEmpty()) {
            put(
                "tools",
                JSONArray().apply {
                    tools.forEach { tool ->
                        put(
                            JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("input_schema", tool.inputSchema)
                                // Só quando pedido: sem isto a API valida cada parâmetro antes de mandar (mais lento para uma skin grande, mas o JSON chega íntegro).
                                if (eagerInputStreaming) put("eager_input_streaming", true)
                            },
                        )
                    }
                },
            )
        }
        put("messages", toApiMessages(messages, flattenTools = tools.isEmpty()))
    }.toString()

    /**
     * O histórico neutro no formato da API. Regras (todas da documentação acima):
     *  - cada chamada do modelo é um bloco `tool_use` e TODOS os resultados da volta vão juntos numa mensagem do usuário, com os
     *    `tool_result` antes do texto — por isso mensagens seguidas do mesmo papel são fundidas;
     *  - mensagem vazia não existe (a API recusa bloco de texto vazio): sai;
     *  - os blocos `thinking` assinados voltam INTACTOS, mas só na volta de ferramenta em andamento (as mensagens depois da última do
     *    usuário): voltas antigas são reconstruídas sem eles, o que a documentação permite ("fora de uso de ferramenta, omita") e que
     *    evita o 400 quando o prefixo mudou (poda, ferramenta ligada/desligada) — o preço é cache de prompt mais curto;
     *  - sem nenhuma ferramenta ativa a API recusa `tool_use`/`tool_result` no histórico ("must define tools"): então as chamadas viram
     *    texto ([flattenTools]).
     * Conserta antes ([AgentHistory.repair]): chamada sem resultado ganha um resultado de erro.
     */
    fun toApiMessages(messages: List<AgentMessage>, flattenTools: Boolean): JSONArray {
        val repaired = AgentHistory.repair(messages).messages
        val lastUser = repaired.indexOfLast { it is AgentMessage.User }
        val names = HashMap<String, String>()
        val turns = ArrayList<Pair<String, MutableList<JSONObject>>>()
        fun append(role: String, block: JSONObject) {
            if (turns.isNotEmpty() && turns.last().first == role) turns.last().second += block else turns += role to mutableListOf(block)
        }
        repaired.forEachIndexed { index, message ->
            when (message) {
                is AgentMessage.User -> if (message.text.isNotBlank()) append("user", textBlock(message.text))
                is AgentMessage.Assistant -> {
                    message.toolCalls.forEach { names[it.id] = it.name }
                    assistantBlocks(message, replayThinking = index > lastUser && !flattenTools, flattenTools).forEach { append("assistant", it) }
                }
                is AgentMessage.ToolResult -> append(
                    "user",
                    if (flattenTools) textBlock("[Resultado da ferramenta ${names[message.callId] ?: "?"}${if (message.isError) " (erro)" else ""}]\n${message.content}") else toolResultBlock(message),
                )
            }
        }
        return JSONArray().apply {
            turns.forEach { (role, blocks) ->
                // Numa mensagem do usuário, os `tool_result` vêm ANTES de qualquer texto (a API recusa o contrário). sortedBy é estável.
                val ordered = if (role == "user") blocks.sortedBy { if (it.optString("type") == "tool_result") 0 else 1 } else blocks
                put(JSONObject().put("role", role).put("content", JSONArray(ordered)))
            }
        }
    }

    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

    private fun toolResultBlock(result: AgentMessage.ToolResult) = JSONObject().apply {
        put("type", "tool_result")
        put("tool_use_id", result.callId)
        if (result.content.isNotBlank()) put("content", result.content)
        if (result.isError) put("is_error", true)
    }

    private fun assistantBlocks(message: AgentMessage.Assistant, replayThinking: Boolean, flattenTools: Boolean): List<JSONObject> {
        val state = message.providerState
        if (replayThinking && state != null && state.provider == PROVIDER) {
            replayed(state, message)?.let { return it }
        }
        val blocks = ArrayList<JSONObject>()
        if (flattenTools) {
            val text = buildString {
                append(message.text)
                message.toolCalls.forEach { append("\n[Ferramenta chamada: ${it.name}]") }
            }.trim()
            if (text.isNotEmpty()) blocks += textBlock(text)
            return blocks
        }
        if (message.text.isNotBlank()) blocks += textBlock(message.text)
        message.toolCalls.forEach { call ->
            blocks += JSONObject().put("type", "tool_use").put("id", call.id).put("name", call.name).put("input", inputOf(call.argsJson))
        }
        return blocks
    }

    /** Os blocos guardados da volta, se ainda batem com as chamadas dela; senão `null` e a mensagem é reconstruída. */
    private fun replayed(state: ProviderState, message: AgentMessage.Assistant): List<JSONObject>? {
        val array = try {
            JSONArray(state.payload)
        } catch (e: JSONException) {
            return null
        }
        val blocks = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        val ids = blocks.filter { it.optString("type") == "tool_use" }.map { it.optString("id") }
        if (ids != message.toolCalls.map { it.id }) return null
        return blocks
    }

    /** O `input` de um `tool_use` TEM de ser um objeto: JSON que não é (o modelo errou) vira `{}` — a ferramenta já devolveu o erro dele. */
    private fun inputOf(argsJson: String): JSONObject = try {
        JSONObject(argsJson.trim().ifEmpty { "{}" })
    } catch (e: JSONException) {
        JSONObject()
    }

    /**
     * Lê os eventos SSE de UMA resposta e devolve os [AgentEvent] correspondentes. Mantém o estado de cada bloco por `index` (texto,
     * raciocínio assinado, JSON parcial dos argumentos) e só emite a [AgentEvent.ToolCall] quando o bloco fecha, com o JSON inteiro.
     */
    class StreamReader {
        private class Block(val type: String) {
            val text = StringBuilder()
            val thinking = StringBuilder()
            val signature = StringBuilder()
            var data: String = ""
            var id: String = ""
            var name: String = ""
            val inputJson = StringBuilder()
        }

        private val blocks = TreeMap<Int, Block>()
        private var stopReason: String? = null
        private var inputTokens: Int? = null
        private var outputTokens: Int? = null

        /** Chegou o `message_stop` ou um `error`: a resposta acabou (bem ou mal). */
        var finished: Boolean = false
            private set

        fun handle(eventName: String?, data: String): List<AgentEvent> {
            val json = try {
                JSONObject(data)
            } catch (e: JSONException) {
                return emptyList()
            }
            return when ((json.opt("type") as? String)?.ifEmpty { null } ?: eventName.orEmpty()) {
                "message_start" -> {
                    inputTokens = json.optJSONObject("message")?.optJSONObject("usage")?.let { intOf(it.opt("input_tokens")) }
                    emptyList()
                }
                "content_block_start" -> blockStart(json)
                "content_block_delta" -> blockDelta(json)
                "content_block_stop" -> blockStop(json)
                "message_delta" -> {
                    (json.optJSONObject("delta")?.opt("stop_reason") as? String)?.let { stopReason = it }
                    json.optJSONObject("usage")?.let { u -> intOf(u.opt("output_tokens"))?.let { outputTokens = it } } // cumulativo: vale o último
                    emptyList()
                }
                "message_stop" -> {
                    finished = true
                    listOf(done())
                }
                "error" -> {
                    finished = true
                    listOf(error(json.optJSONObject("error")))
                }
                // `ping` e tipos novos que a API venha a acrescentar: ignorados (a documentação manda tratar o desconhecido sem falhar).
                else -> emptyList()
            }
        }

        private fun blockStart(json: JSONObject): List<AgentEvent> {
            val index = json.optInt("index", -1)
            val cb = json.optJSONObject("content_block") ?: return emptyList()
            val block = Block(cb.opt("type") as? String ?: "")
            when (block.type) {
                "text" -> block.text.append(cb.opt("text") as? String ?: "")
                "thinking" -> {
                    block.thinking.append(cb.opt("thinking") as? String ?: "")
                    block.signature.append(cb.opt("signature") as? String ?: "")
                }
                "redacted_thinking" -> block.data = cb.opt("data") as? String ?: ""
                // O `input` do `content_block_start` é o marcador `{}`: os argumentos de verdade vêm nos `input_json_delta`.
                "tool_use" -> {
                    block.id = cb.opt("id") as? String ?: ""
                    block.name = cb.opt("name") as? String ?: ""
                }
            }
            blocks[index] = block
            return if (block.type == "text" && block.text.isNotEmpty()) listOf(AgentEvent.TextDelta(block.text.toString())) else emptyList()
        }

        private fun blockDelta(json: JSONObject): List<AgentEvent> {
            val block = blocks[json.optInt("index", -1)] ?: return emptyList()
            val delta = json.optJSONObject("delta") ?: return emptyList()
            when (delta.opt("type") as? String) {
                "text_delta" -> {
                    val text = delta.opt("text") as? String ?: return emptyList()
                    block.text.append(text)
                    if (text.isNotEmpty()) return listOf(AgentEvent.TextDelta(text))
                }
                "input_json_delta" -> {
                    block.inputJson.append(delta.opt("partial_json") as? String ?: "")
                    if (block.inputJson.length > MAX_TOOL_INPUT_CHARS) {
                        finished = true
                        return listOf(AgentEvent.Error(ReasoningError.ENGINE, "Os argumentos de uma ferramenta passaram de ${MAX_TOOL_INPUT_CHARS / 1024} KB; a resposta foi interrompida."))
                    }
                }
                "thinking_delta" -> block.thinking.append(delta.opt("thinking") as? String ?: "")
                "signature_delta" -> block.signature.append(delta.opt("signature") as? String ?: "")
            }
            return emptyList()
        }

        private fun blockStop(json: JSONObject): List<AgentEvent> {
            val block = blocks[json.optInt("index", -1)] ?: return emptyList()
            if (block.type != "tool_use") return emptyList()
            return listOf(AgentEvent.ToolCall(block.id, block.name, block.inputJson.toString().ifBlank { "{}" }))
        }

        private fun done(): AgentEvent.Done {
            val hasCalls = blocks.values.any { it.type == "tool_use" }
            val reason = when (stopReason) {
                "end_turn" -> AgentStopReason.END_TURN
                "tool_use" -> AgentStopReason.TOOL_USE
                // `model_context_window_exceeded`: a documentação manda tratar a resposta como cortada.
                "max_tokens", "model_context_window_exceeded" -> AgentStopReason.TRUNCATED
                "refusal" -> AgentStopReason.REFUSAL
                null -> if (hasCalls) AgentStopReason.TOOL_USE else AgentStopReason.END_TURN
                else -> AgentStopReason.OTHER
            }
            val usage = if (inputTokens != null || outputTokens != null) TokenUsage(inputTokens ?: 0, outputTokens ?: 0) else null
            return AgentEvent.Done(reason, usage, providerState())
        }

        /**
         * Os blocos da resposta como a API os mandou, guardados para voltarem intactos na volta de ferramenta seguinte — só quando
         * há raciocínio assinado (`thinking`/`redacted_thinking`); sem ele, reconstruir do texto e das chamadas dá o mesmo.
         */
        private fun providerState(): ProviderState? {
            if (blocks.values.none { it.type == "thinking" || it.type == "redacted_thinking" }) return null
            val array = JSONArray()
            for (block in blocks.values) {
                when (block.type) {
                    "thinking" -> array.put(JSONObject().put("type", "thinking").put("thinking", block.thinking.toString()).put("signature", block.signature.toString()))
                    "redacted_thinking" -> array.put(JSONObject().put("type", "redacted_thinking").put("data", block.data))
                    "text" -> if (block.text.isNotEmpty()) array.put(textBlock(block.text.toString()))
                    "tool_use" -> array.put(JSONObject().put("type", "tool_use").put("id", block.id).put("name", block.name).put("input", inputOf(block.inputJson.toString())))
                }
            }
            return ProviderState(PROVIDER, array.toString())
        }

        private fun error(error: JSONObject?): AgentEvent.Error {
            val type = error?.opt("type") as? String
            val message = error?.opt("message") as? String
            val kind = when (type) {
                "overloaded_error", "api_error", "timeout_error" -> ReasoningError.NETWORK
                "rate_limit_error" -> ReasoningError.RATE_LIMIT
                "authentication_error", "permission_error" -> ReasoningError.UNAUTHORIZED
                else -> ReasoningError.ENGINE
            }
            return AgentEvent.Error(kind, "Anthropic error: ${listOfNotNull(type, message).joinToString(": ")}")
        }

        private fun intOf(value: Any?): Int? = (value as? Number)?.toInt()
    }
}
