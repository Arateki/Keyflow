package com.keyflow.reasoning.google

import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentHistory
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.ProviderState
import com.keyflow.agent.TokenUsage
import com.keyflow.agent.ToolDefinition
import com.keyflow.reasoning.ReasoningError
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * O formato da API `generateContent` do Gemini para o agente: o corpo da requisição (com `tools.functionDeclarations`, partes
 * `functionCall` e `functionResponse`) e a leitura dos eventos SSE de `streamGenerateContent`. Puro (JVM): quem fala com a rede é o
 * [GoogleAgentEngine].
 *
 * **Escolha (registrada no STATE.md, AGT-T7)**: a `generateContent` e não a Interactions API. A Google diz em
 * `ai.google.dev/gemini-api/docs/interactions` que a Interactions API (Beta, "sujeita a mudanças que quebram") é o padrão para projetos
 * novos, mas que a `generateContent` "remains fully supported" e, na página de function calling da Interactions, "for stable
 * production deployments, we recommend you continue to use the `generateContent` API". O app já fala `streamGenerateContent` (mesma URL, mesmas
 * credenciais, mesma retentativa do motor de texto).
 *
 * Conferido na documentação oficial em 2026-10-24 (nada presumido; ver `STATE.md`):
 *  - o documento de descoberta `generativelanguage.googleapis.com/$discovery/rest?version=v1beta` (revisão 20261023) — os campos
 *    `Tool.functionDeclarations`, `FunctionDeclaration.{name,description,parametersJsonSchema}`, `Part.{functionCall,functionResponse,
 *    thought,thoughtSignature}`, `FunctionCall.{name,args,id}`, `FunctionResponse.{name,response,id}` ("if the function call failed to
 *    execute, the response can have an \"error\" key"), `Candidate.finishReason` (todos os valores, inclusive `MALFORMED_FUNCTION_CALL`
 *    e `MISSING_THOUGHT_SIGNATURE`), `UsageMetadata`, `PromptFeedback.blockReason`;
 *  - `…/docs/migrate-to-interactions` — o exemplo da `generateContent`: a chamada chega em `candidates[0].content.parts[].functionCall`,
 *    o resultado volta num `Content` de papel `user` com `functionResponse`, e o `Content` do modelo é reenviado como veio; no streaming
 *    "function calls arrived complete in a single chunk";
 *  - `…/docs/structured-output` ("JSON schema support") — o subconjunto de JSON Schema aceito (`type`, `properties`, `required`,
 *    `additionalProperties` booleano ou esquema, `enum`, `minimum`/`maximum`, `items`, `minItems`/`maxItems`; `minLength`/`maxLength`
 *    são ignorados pelo servidor, sem erro);
 *  - `…/docs/thinking` — os modelos Gemini 3 usam assinaturas de pensamento; `thoughtSignature` é metadado que pode viver em qualquer
 *    parte (inclusive numa `functionCall`) e precisa voltar na volta seguinte, intacto: por isso o `Content` do modelo da volta de
 *    ferramenta em andamento é guardado como veio ([ProviderState]) e reenviado.
 */
internal object GoogleAgentWire {
    /** O nome do provedor no [ProviderState]. */
    const val PROVIDER = "google"

    /**
     * O id de uma chamada que a API NÃO numerou (`functionCall.id` é opcional): o app inventa um, único no processo. O prefixo diz, na
     * volta, que este id não deve ser reenviado à API como `id` (ela não o conhece).
     */
    const val SYNTHETIC_ID_PREFIX = "gemini-call-"

    private val syntheticCounter = AtomicLong()

    fun syntheticId(): String = SYNTHETIC_ID_PREFIX + syntheticCounter.incrementAndGet()

    fun isSynthetic(id: String): Boolean = id.startsWith(SYNTHETIC_ID_PREFIX)

    /** O maior JSON de argumentos de UMA chamada que se aceita juntar em memória (a cinta; o teto de tokens da resposta já limita). */
    const val MAX_TOOL_INPUT_CHARS = 4 * 1024 * 1024

    /**
     * [maxOutputTokens] `null` (o padrão do motor) = não manda o teto: cada modelo tem o seu limite de saída e pedir MAIS que o limite do modelo pode ser recusado com HTTP 400 (cautela do app, não conferida numa página oficial); sem o campo vale o limite do próprio modelo. O teto de 16 384 do
     * agente (A5) só vale na Anthropic; aqui os outros freios do laço (12 voltas, 8 chamadas por volta) seguem valendo.
     */
    fun body(system: String, messages: List<AgentMessage>, tools: List<ToolDefinition>, maxOutputTokens: Int? = null): String = JSONObject().apply {
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        put("contents", toContents(messages, flattenTools = tools.isEmpty()))
        if (maxOutputTokens != null) put("generationConfig", JSONObject().put("maxOutputTokens", maxOutputTokens))
        if (tools.isNotEmpty()) {
            put(
                "tools",
                JSONArray().put(
                    JSONObject().put(
                        "functionDeclarations",
                        JSONArray().apply {
                            tools.forEach { tool ->
                                put(
                                    JSONObject()
                                        .put("name", tool.name)
                                        .put("description", tool.description)
                                        .put("parametersJsonSchema", schemaOf(tool.inputSchema)),
                                )
                            }
                        },
                    ),
                ),
            )
        }
    }.toString()

    /** Uma cópia do esquema sem `default` (só documentação, e fora da lista que a Google aceita). */
    private fun schemaOf(schema: JSONObject): JSONObject {
        val copy = JSONObject()
        val keys = schema.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "default") continue
            copy.put(key, cleaned(schema.get(key)))
        }
        return copy
    }

    private fun cleaned(value: Any): Any = when (value) {
        is JSONObject -> schemaOf(value)
        is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(cleaned(value.get(i))) }
        else -> value
    }

    /**
     * O histórico neutro como `contents`. Regras (da documentação acima):
     *  - uma chamada do modelo é uma parte `functionCall` (`name`, `args`, e `id` só se a API o numerou) num `Content` de papel `model`;
     *    todos os resultados da volta vão juntos num `Content` de papel `user`, cada um um `functionResponse` (`name`, `response`, `id`) —
     *    por isso mensagens seguidas do mesmo papel são fundidas e os `functionResponse` vêm antes de qualquer texto;
     *  - o `response` é um objeto: `{"result": texto}` e, se a ferramenta falhou, `{"error": texto}`;
     *  - as partes do modelo da volta de ferramenta em andamento (as mensagens depois da última do usuário) voltam INTACTAS, com as
     *    `thoughtSignature`, quando guardadas ([ProviderState]); voltas antigas são reconstruídas do texto e das chamadas;
     *  - mensagem vazia não existe; a imagem de um resultado não vai (só texto: a validação com o modelo é da AGT-T10);
     *  - sem nenhuma ferramenta ativa as chamadas do histórico viram TEXTO, em vez de partes `functionCall` sem `tools` na requisição.
     * Conserta antes ([AgentHistory.repair]): chamada sem resultado ganha um resultado de erro.
     */
    fun toContents(messages: List<AgentMessage>, flattenTools: Boolean): JSONArray {
        val repaired = AgentHistory.repair(messages).messages
        val names = HashMap<String, String>()
        val turns = ArrayList<Pair<String, MutableList<JSONObject>>>()
        fun append(role: String, part: JSONObject) {
            if (turns.isNotEmpty() && turns.last().first == role) turns.last().second += part else turns += role to mutableListOf(part)
        }
        repaired.forEach { message ->
            when (message) {
                is AgentMessage.User -> if (message.text.isNotBlank()) append("user", textPart(message.text))
                is AgentMessage.Assistant -> {
                    message.toolCalls.forEach { names[it.id] = it.name }
                    val replay = if (!flattenTools) replayed(message) else null
                    when {
                        replay != null -> replay.forEach { append("model", it) }
                        flattenTools -> {
                            val text = buildString {
                                append(message.text)
                                message.toolCalls.forEach { append("\n[Ferramenta chamada: ${it.name}]") }
                            }.trim()
                            if (text.isNotEmpty()) append("model", textPart(text))
                        }
                        else -> {
                            if (message.text.isNotBlank()) append("model", textPart(message.text))
                            message.toolCalls.forEach { call ->
                                val functionCall = JSONObject().put("name", call.name).put("args", argsOf(call.argsJson))
                                if (!isSynthetic(call.id)) functionCall.put("id", call.id)
                                append("model", JSONObject().put("functionCall", functionCall))
                            }
                        }
                    }
                }
                is AgentMessage.ToolResult -> append(
                    "user",
                    if (flattenTools) {
                        textPart("[Resultado da ferramenta ${names[message.callId] ?: "?"}${if (message.isError) " (erro)" else ""}]\n${message.content}")
                    } else {
                        val response = JSONObject().put(if (message.isError) "error" else "result", message.content)
                        val functionResponse = JSONObject().put("name", names[message.callId] ?: "desconhecida").put("response", response)
                        if (!isSynthetic(message.callId)) functionResponse.put("id", message.callId)
                        JSONObject().put("functionResponse", functionResponse)
                    },
                )
            }
        }
        return JSONArray().apply {
            turns.forEach { (role, parts) ->
                // Num Content do usuário, os `functionResponse` vêm ANTES de qualquer texto. sortedBy é estável.
                val ordered = if (role == "user") parts.sortedBy { if (it.has("functionResponse")) 0 else 1 } else parts
                put(JSONObject().put("role", role).put("parts", JSONArray(ordered)))
            }
        }
    }

    private fun textPart(text: String) = JSONObject().put("text", text)

    /** `args` TEM de ser um objeto: JSON que não é (o modelo errou) vira `{}` — a ferramenta já devolveu o erro dele. */
    private fun argsOf(argsJson: String): JSONObject = try {
        JSONObject(argsJson.trim().ifEmpty { "{}" })
    } catch (e: JSONException) {
        JSONObject()
    }

    /** As partes guardadas da volta, se ainda batem com as chamadas dela; senão `null` e a mensagem é reconstruída. */
    private fun replayed(message: AgentMessage.Assistant): List<JSONObject>? {
        val state = message.providerState?.takeIf { it.provider == PROVIDER } ?: return null
        val array = try {
            JSONArray(state.payload)
        } catch (e: JSONException) {
            return null
        }
        val parts = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        val ids = parts.mapNotNull { it.optJSONObject("functionCall") }.map { it.optString("id") }
        // O id guardado na parte é o da API (ou vazio, se ela não numerou): confere com as chamadas pela ordem e pelo nome, e pelo id quando há.
        val calls = message.toolCalls
        val names = parts.mapNotNull { it.optJSONObject("functionCall") }.map { it.optString("name") }
        if (names != calls.map { it.name }) return null
        if (ids.zip(calls).any { (apiId, call) -> apiId.isNotEmpty() && apiId != call.id }) return null
        return parts
    }

    /**
     * Lê os chunks SSE de UMA resposta e devolve os [AgentEvent] correspondentes. A `functionCall` chega completa num chunk só (a
     * documentação) e sai na hora; o fim é o chunk com `finishReason` (ou um `promptFeedback.blockReason`): um fluxo que acaba sem ele é
     * erro, nunca sucesso. Guarda as partes da resposta, na ordem, para devolvê-las intactas (com as `thoughtSignature`).
     */
    class StreamReader(private val newId: () -> String = { syntheticId() }) {
        private val parts = ArrayList<JSONObject>()
        private var hasCalls = false
        private var hasSignature = false
        private var promptTokens: Int? = null
        private var outputTokens: Int? = null

        /** Chegou o chunk final (ou um erro): a resposta acabou (bem ou mal). */
        var finished: Boolean = false
            private set

        fun handle(data: String): List<AgentEvent> {
            if (data.trim() == "[DONE]") return emptyList()
            val json = try {
                JSONObject(data)
            } catch (e: JSONException) {
                return emptyList()
            }
            json.optJSONObject("error")?.let {
                finished = true
                return listOf(streamError(it))
            }
            json.optJSONObject("usageMetadata")?.let { usage ->
                (usage.opt("promptTokenCount") as? Number)?.let { promptTokens = it.toInt() }
                // Os tokens de raciocínio contam como saída (a doc: o total inclui `thoughtsTokenCount`).
                val out = (usage.opt("candidatesTokenCount") as? Number)?.toInt()
                val thoughts = (usage.opt("thoughtsTokenCount") as? Number)?.toInt()
                if (out != null || thoughts != null) outputTokens = (out ?: 0) + (thoughts ?: 0)
            }
            val events = ArrayList<AgentEvent>()
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            if (candidate == null) {
                val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                if (blocked.isNotEmpty() && blocked != "BLOCK_REASON_UNSPECIFIED") {
                    finished = true
                    events += AgentEvent.Done(AgentStopReason.REFUSAL, usage(), providerState = null)
                }
                return events
            }
            candidate.optJSONObject("content")?.optJSONArray("parts")?.let { array ->
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { events += part(it) }
            }
            val finish = candidate.optString("finishReason")
            if (finish.isNotEmpty() && finish != "FINISH_REASON_UNSPECIFIED") {
                finished = true
                events += finishEvent(finish)
            }
            return events
        }

        private fun part(part: JSONObject): List<AgentEvent> {
            val signature = (part.opt("thoughtSignature") as? String)?.takeIf { it.isNotEmpty() }
            val call = part.optJSONObject("functionCall")
            if (call != null) {
                if (signature != null) hasSignature = true
                hasCalls = true
                val args = call.optJSONObject("args")
                val id = call.optString("id").ifEmpty { newId() }
                // A parte guardada leva o id da API (vazio se ela não numerou) — o do app não vai à rede.
                parts += JSONObject(part.toString())
                return listOf(AgentEvent.ToolCall(id, call.optString("name"), (args ?: JSONObject()).toString()))
            }
            // Resumo de pensamento (só vem se pedido): nem é texto da resposta nem se reenvia sem assinatura.
            if (part.optBoolean("thought", false) && signature == null) return emptyList()
            val text = part.opt("text") as? String
            if (text != null) {
                if (signature != null) {
                    hasSignature = true
                    parts += JSONObject(part.toString())
                } else if (text.isNotEmpty()) {
                    val last = parts.lastOrNull()
                    if (last != null && last.keys().asSequence().toSet() == setOf("text")) last.put("text", last.getString("text") + text) else parts += JSONObject().put("text", text)
                }
                return if (text.isNotEmpty() && !part.optBoolean("thought", false)) listOf(AgentEvent.TextDelta(text)) else emptyList()
            }
            // Qualquer outra parte só se guarda quando carrega uma assinatura (precisa voltar); o resto não é do agente.
            if (signature != null) {
                hasSignature = true
                parts += JSONObject(part.toString())
            }
            return emptyList()
        }

        private fun usage(): TokenUsage? = if (promptTokens != null || outputTokens != null) TokenUsage(promptTokens ?: 0, outputTokens ?: 0) else null

        private fun finishEvent(finish: String): AgentEvent = when (finish) {
            "STOP" -> done(if (hasCalls) AgentStopReason.TOOL_USE else AgentStopReason.END_TURN)
            // CONTINUATION: "a resposta bateu no limite por requisição, mas a geração não terminou" — cortada.
            "MAX_TOKENS", "CONTINUATION" -> done(AgentStopReason.TRUNCATED)
            "SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "ESCALATION", "PUP_LIMITED_DISABLED" -> done(AgentStopReason.REFUSAL)
            // A volta é inválida: nada dela entra no histórico e nenhuma ferramenta dela roda.
            "MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "MISSING_THOUGHT_SIGNATURE", "MALFORMED_RESPONSE" ->
                AgentEvent.Error(ReasoningError.ENGINE, "Google Gemini: resposta inválida ($finish)")
            else -> done(if (hasCalls) AgentStopReason.TOOL_USE else AgentStopReason.OTHER)
        }

        private fun done(reason: AgentStopReason) = AgentEvent.Done(reason, usage(), providerState())

        /** As partes da resposta como a API as mandou, guardadas só quando há assinatura de pensamento; sem ela, reconstruir dá o mesmo. */
        private fun providerState(): ProviderState? {
            if (!hasSignature || parts.isEmpty()) return null
            return ProviderState(PROVIDER, JSONArray(parts).toString())
        }

        /** Um erro no meio do fluxo (um objeto `error` num chunk): só o CÓDIGO decide; a mensagem do servidor não é repetida. */
        private fun streamError(error: JSONObject): AgentEvent.Error {
            val status = error.optString("status")
            val code = (error.opt("code") as? Number)?.toInt()
            val kind = when {
                code == 429 || status == "RESOURCE_EXHAUSTED" -> ReasoningError.RATE_LIMIT
                code == 401 || code == 403 || status == "UNAUTHENTICATED" || status == "PERMISSION_DENIED" -> ReasoningError.UNAUTHORIZED
                (code != null && code >= 500) || status == "UNAVAILABLE" || status == "INTERNAL" || status == "DEADLINE_EXCEEDED" -> ReasoningError.NETWORK
                else -> ReasoningError.ENGINE
            }
            val safe = status.takeIf { it.length <= 64 && it.matches(SAFE_CODE) }
            return AgentEvent.Error(kind, "Google Gemini: a resposta falhou" + (safe?.let { " ($it)" } ?: ""))
        }
    }

    private val SAFE_CODE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
}
