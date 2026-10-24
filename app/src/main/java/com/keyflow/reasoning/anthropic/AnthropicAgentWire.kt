package com.keyflow.reasoning.anthropic

import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentHistory
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.ProviderState
import com.keyflow.agent.TokenUsage
import com.keyflow.agent.ToolDefinition
import com.keyflow.agent.ToolImage
import com.keyflow.reasoning.ReasoningError
import java.util.TreeMap
import okio.ByteString.Companion.toByteString
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
 *  - `…/build-with-claude/handling-stop-reasons` — os valores de `stop_reason`;
 *  - `…/agents-and-tools/tool-use/handle-tool-calls` (exemplo "tool result with images") e `…/build-with-claude/vision` — o `content` de
 *    um `tool_result` pode ser uma lista de blocos `text`/`image`, e o bloco é `{"type":"image","source":{"type":"base64","media_type":
 *    "image/png","data":"…"}}` (JPEG, PNG, GIF e WebP; 10 MB em base64 por imagem; "todos os modelos atuais aceitam imagem"; a partir de
 *    20 imagens na requisição vale um limite de dimensão mais duro, contando também as de turnos antigos — por isso só as últimas
 *    [MAX_IMAGES_IN_HISTORY] imagens do histórico são reenviadas).
 */
internal object AnthropicAgentWire {
    /** O nome do provedor no [ProviderState]. */
    const val PROVIDER = "anthropic"

    /** O prefixo que o endpoint do login (OAuth) exige no `system`, como no motor de texto ([AnthropicReasoningEngine]). */
    private const val OAUTH_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."

    /** O maior JSON de argumentos de UMA chamada que se aceita juntar em memória (o teto de tokens da resposta já limita; isto é a cinta). */
    const val MAX_TOOL_INPUT_CHARS = 4 * 1024 * 1024

    /**
     * Quantas imagens de resultado de ferramenta o histórico reenvia (as MAIS RECENTES). Cada requisição reenvia a conversa inteira; uma
     * folha de contato por pré-visualização acumularia centenas de KB por volta e levaria a conversa ao limite de imagens da API. As
     * mais antigas ficam só como texto (com uma nota).
     */
    const val MAX_IMAGES_IN_HISTORY = 2

    private val CLAUDE_FAMILY = Regex("^claude-(?:opus|sonnet|haiku|fable|mythos)-(\\d+)")
    private val CLAUDE_3_WITH_VISION = Regex("^claude-3-(?:5-sonnet|7-sonnet|opus|sonnet|haiku)")

    /**
     * O modelo aceita imagem na entrada? A documentação (`models/overview`, 2026-10-03) diz que TODOS os modelos atuais aceitam texto e
     * imagem; aqui a regra é CONSERVADORA — só os que se sabe aceitarem — porque mandar uma imagem a um modelo que não a aceita vira um
     * HTTP 400 que derruba a volta, e deixá-la de fora só tira do modelo a pré-visualização (ele continua com o relatório em texto):
     * as famílias `claude-<opus|sonnet|haiku|fable|mythos>-<4 ou mais>` e as do Claude 3 que a aceitam desde o lançamento (Sonnet 3.5 e
     * 3.7, Opus 3, Sonnet 3, Haiku 3). Id que não reconhece (o Haiku 3.5, um modelo novo de outra família) fica só com o texto. A
     * consulta exata (`capabilities` do Models API) é da validação com o modelo de verdade (AGT-T10).
     */
    fun acceptsImages(model: String): Boolean {
        val id = model.trim().lowercase()
        CLAUDE_FAMILY.find(id)?.let { return it.groupValues[1].toIntOrNull()?.let { version -> version >= 4 } == true }
        return CLAUDE_3_WITH_VISION.containsMatchIn(id)
    }

    fun body(
        model: String,
        system: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int,
        isOAuth: Boolean,
        eagerInputStreaming: Boolean,
        sendImages: Boolean = acceptsImages(model),
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
        put("messages", toApiMessages(messages, flattenTools = tools.isEmpty(), sendImages = sendImages))
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
     *    texto ([flattenTools]) e as imagens somem;
     *  - a imagem de um resultado vira um bloco `image` DENTRO do `content` do `tool_result` (depois do texto), só se [sendImages] (o modelo
     *    aceita imagem) e só nas [MAX_IMAGES_IN_HISTORY] mais recentes; as outras ficam como texto, com uma nota.
     * Conserta antes ([AgentHistory.repair]): chamada sem resultado ganha um resultado de erro.
     */
    fun toApiMessages(messages: List<AgentMessage>, flattenTools: Boolean, sendImages: Boolean = true): JSONArray {
        val repaired = AgentHistory.repair(messages).messages
        val lastUser = repaired.indexOfLast { it is AgentMessage.User }
        // Os índices das imagens que voltam: as mais recentes. Com `sendImages` falso (ou sem ferramentas) nenhuma.
        val keptImages: Set<Int> = if (!sendImages || flattenTools) emptySet() else
            repaired.withIndex().filter { (_, m) -> m is AgentMessage.ToolResult && m.image != null }.map { it.index }.takeLast(MAX_IMAGES_IN_HISTORY).toSet()
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
                    if (flattenTools) {
                        textBlock("[Resultado da ferramenta ${names[message.callId] ?: "?"}${if (message.isError) " (erro)" else ""}]\n${message.content}")
                    } else {
                        toolResultBlock(message, image = message.image?.takeIf { index in keptImages }, imageLeftOut = message.image != null && index !in keptImages && sendImages)
                    },
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

    /** Nota que fica no lugar de uma imagem antiga que o histórico não reenvia (só as mais recentes vão; ver [MAX_IMAGES_IN_HISTORY]). */
    private const val IMAGE_LEFT_OUT_NOTE = "[A imagem deste resultado foi omitida do histórico para economizar contexto.]"

    private fun toolResultBlock(result: AgentMessage.ToolResult, image: ToolImage?, imageLeftOut: Boolean) = JSONObject().apply {
        put("type", "tool_result")
        put("tool_use_id", result.callId)
        val text = if (imageLeftOut) listOf(result.content, IMAGE_LEFT_OUT_NOTE).filter { it.isNotBlank() }.joinToString("\n") else result.content
        if (image != null) {
            // `content` como lista de blocos: o texto primeiro (se houver) e a imagem depois, como no exemplo "tool result with images" da documentação.
            put(
                "content",
                JSONArray().apply {
                    if (text.isNotBlank()) put(textBlock(text))
                    put(imageBlock(image))
                },
            )
        } else if (text.isNotBlank()) {
            put("content", text)
        }
        if (result.isError) put("is_error", true)
    }

    private fun imageBlock(image: ToolImage) = JSONObject()
        .put("type", "image")
        .put("source", JSONObject().put("type", "base64").put("media_type", image.mediaType).put("data", image.bytes.toByteString().base64()))

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
            val kind = when (type) {
                "overloaded_error", "api_error", "timeout_error" -> ReasoningError.NETWORK
                "rate_limit_error" -> ReasoningError.RATE_LIMIT
                "authentication_error", "permission_error" -> ReasoningError.UNAUTHORIZED
                else -> ReasoningError.ENGINE
            }
            val note = when (kind) {
                ReasoningError.NETWORK -> "Anthropic: falha de rede"
                ReasoningError.RATE_LIMIT -> "Anthropic: limite de requisições atingido"
                ReasoningError.UNAUTHORIZED -> "Anthropic: credenciais inválidas"
                else -> "Anthropic: resposta inválida"
            }
            return AgentEvent.Error(kind, note)
        }

        private fun intOf(value: Any?): Int? = (value as? Number)?.toInt()
    }
}
