package com.keyflow.reasoning.openai

import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentHistory
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.ProviderState
import com.keyflow.agent.TokenUsage
import com.keyflow.agent.ToolDefinition
import com.keyflow.reasoning.ReasoningError
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * O formato da **Responses API** da OpenAI para o agente: o corpo da requisição (com `tools`, itens `function_call` e
 * `function_call_output`) e a leitura dos eventos SSE. Puro (JVM): quem fala com a rede é o [OpenAiAgentEngine].
 *
 * **SPEC_DEVIATION (local)**: o design (D8) previa `tool_calls`/`role=tool` (Chat Completions). Conferido em 2026-10-24 em
 * `developers.openai.com/api/docs/guides/function-calling`: "GPT-6 Astra and GPT-6.1 Sol require the Responses API for tool calling"
 * (os exemplos de Chat Completions usam o GPT-5.6 Terra com raciocínio desligado), e o motor de texto do app ([OpenAiReasoningEngine])
 * já usa `/responses` — a mesma base de URL e as mesmas credenciais. Chat Completions deixaria de fora justamente os modelos novos.
 *
 * Conferido na documentação oficial (nada presumido; ver `STATE.md`, AGT-T7):
 *  - `…/guides/function-calling` — a definição `{"type":"function","name","description","parameters","strict"}`; o item de saída
 *    `{"type":"function_call","id":"fc_…","call_id":"call_…","name","arguments":"<JSON em texto>"}`; o resultado
 *    `{"type":"function_call_output","call_id","output":"<texto>"}`; o streaming com `response.output_item.added`,
 *    `response.function_call_arguments.delta` (`item_id`, `delta`), `response.function_call_arguments.done` e `response.output_item.done`;
 *    "for reasoning models like GPT-5 or o4-mini, any reasoning items returned in model responses with tool calls must also be passed
 *    back with tool call outputs"; sem `strict` a Responses tenta normalizar o esquema e cai para o modo não estrito — por isso o app
 *    manda `strict:false` (os esquemas das ferramentas têm propriedades opcionais);
 *  - `…/guides/reasoning` — em modo sem estado (`store:false`) os itens `reasoning` da resposta trazem `encrypted_content` por padrão
 *    e todo item de saída é reenviado, intacto, na volta seguinte;
 *  - `openai-siwc-notes.md` (do próprio repositório) — `store:false` e `stream:true`, `instructions` no lugar do item `system`, e os
 *    eventos `response.completed` (só ele é sucesso), `response.failed`, `response.incomplete`.
 */
internal object OpenAiAgentWire {
    /** O nome do provedor no [ProviderState]. */
    const val PROVIDER = "openai"

    /** O maior JSON de argumentos de UMA chamada que se aceita juntar em memória (a cinta; o teto de tokens da resposta já limita). */
    const val MAX_TOOL_INPUT_CHARS = 4 * 1024 * 1024

    /**
     * [maxOutputTokens] `null` (o padrão do motor) = não manda o teto: o limite de saída muda de modelo para modelo e pedir MAIS que o do modelo pode ser recusado com HTTP 400 (cautela do app, não conferida numa página oficial); sem o campo vale o limite do próprio modelo (e o
     * `openai-siwc-notes.md` manda omitir `max_output_tokens` no fluxo do plano). Os outros freios do laço (12 voltas, 8 chamadas por volta) seguem valendo.
     */
    fun body(model: String, system: String, messages: List<AgentMessage>, tools: List<ToolDefinition>, maxOutputTokens: Int? = null): String = JSONObject().apply {
        put("model", model)
        put("instructions", system)
        put("input", toInput(messages, flattenTools = tools.isEmpty()))
        put("store", false)
        put("stream", true)
        if (maxOutputTokens != null) put("max_output_tokens", maxOutputTokens)
        if (tools.isNotEmpty()) {
            put(
                "tools",
                JSONArray().apply {
                    tools.forEach { tool ->
                        put(
                            JSONObject()
                                .put("type", "function")
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", tool.inputSchema)
                                .put("strict", false),
                        )
                    }
                },
            )
        }
    }.toString()

    /**
     * O histórico neutro como itens de `input`. Regras (da documentação acima):
     *  - uma chamada do modelo é um item `function_call` (`call_id`, `name`, `arguments`) e cada resultado, um `function_call_output` com o
     *    MESMO `call_id` — itens soltos, na ordem;
     *  - os itens de saída da volta de ferramenta em andamento (as mensagens depois da última do usuário) voltam INTACTOS quando há
     *    raciocínio guardado ([ProviderState]); voltas antigas são reconstruídas do texto e das chamadas, sem o raciocínio (e sem `id` de
     *    item: com `store:false` o `fc_…` não existe no servidor);
     *  - mensagem vazia não existe; a imagem de um resultado não vai (só texto: a validação com o modelo é da AGT-T10);
     *  - sem nenhuma ferramenta ativa as chamadas do histórico viram TEXTO, em vez de itens de ferramenta sem `tools` na requisição.
     * Conserta antes ([AgentHistory.repair]): chamada sem resultado ganha um resultado de erro.
     */
    fun toInput(messages: List<AgentMessage>, flattenTools: Boolean): JSONArray {
        val repaired = AgentHistory.repair(messages).messages
        val lastUser = repaired.indexOfLast { it is AgentMessage.User }
        val names = HashMap<String, String>()
        val items = JSONArray()
        repaired.forEachIndexed { index, message ->
            when (message) {
                is AgentMessage.User -> if (message.text.isNotBlank()) items.put(textMessage("user", message.text))
                is AgentMessage.Assistant -> {
                    message.toolCalls.forEach { names[it.id] = it.name }
                    val replay = if (index > lastUser && !flattenTools) replayed(message) else null
                    if (replay != null) {
                        for (i in 0 until replay.length()) items.put(replay.get(i))
                    } else if (flattenTools) {
                        val text = buildString {
                            append(message.text)
                            message.toolCalls.forEach { append("\n[Ferramenta chamada: ${it.name}]") }
                        }.trim()
                        if (text.isNotEmpty()) items.put(textMessage("assistant", text))
                    } else {
                        if (message.text.isNotBlank()) items.put(textMessage("assistant", message.text))
                        message.toolCalls.forEach { call ->
                            items.put(
                                JSONObject().put("type", "function_call").put("call_id", call.id).put("name", call.name)
                                    .put("arguments", argumentsOf(call.argsJson)),
                            )
                        }
                    }
                }
                is AgentMessage.ToolResult -> {
                    if (flattenTools) {
                        items.put(textMessage("user", "[Resultado da ferramenta ${names[message.callId] ?: "?"}${if (message.isError) " (erro)" else ""}]\n${message.content}"))
                    } else {
                        items.put(JSONObject().put("type", "function_call_output").put("call_id", message.callId).put("output", resultText(message)))
                    }
                }
            }
        }
        return items
    }

    private fun textMessage(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    /** O `output` é texto; o erro de uma ferramenta não tem campo próprio, então o texto diz. */
    private fun resultText(result: AgentMessage.ToolResult): String = if (result.isError) "[erro da ferramenta] ${result.content}" else result.content

    /** `arguments` TEM de ser um objeto JSON em texto: o que não é (o modelo errou) vira `{}` — a ferramenta já devolveu o erro dele. */
    private fun argumentsOf(argsJson: String): String = try {
        JSONObject(argsJson.trim().ifEmpty { "{}" }).toString()
    } catch (e: JSONException) {
        "{}"
    }

    /** Os itens de saída guardados da volta, se ainda batem com as chamadas dela; senão `null` e a mensagem é reconstruída. */
    private fun replayed(message: AgentMessage.Assistant): JSONArray? {
        val state = message.providerState?.takeIf { it.provider == PROVIDER } ?: return null
        val array = try {
            JSONArray(state.payload)
        } catch (e: JSONException) {
            return null
        }
        val ids = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.filter { it.optString("type") == "function_call" }.map { it.optString("call_id") }
        return if (ids == message.toolCalls.map { it.id }) array else null
    }

    /**
     * Lê os eventos SSE de UMA resposta e devolve os [AgentEvent] correspondentes. Junta os pedaços dos argumentos de cada `function_call`
     * por `item_id` e só emite a [AgentEvent.ToolCall] quando o item fecha (`response.output_item.done`), com o JSON inteiro. O fim é o
     * `response.completed` (ou `incomplete`/`failed`): um fluxo que acaba sem eles é erro, nunca sucesso.
     */
    class StreamReader {
        private class Call(var callId: String, var name: String) {
            val arguments = StringBuilder()
        }

        private val calls = LinkedHashMap<String, Call>()
        private val emittedIds = LinkedHashSet<String>()

        /** Chegou um evento terminal: a resposta acabou (bem ou mal). */
        var finished: Boolean = false
            private set

        fun handle(eventName: String?, data: String): List<AgentEvent> {
            val json = try {
                JSONObject(data)
            } catch (e: JSONException) {
                return emptyList()
            }
            return when ((json.opt("type") as? String)?.ifEmpty { null } ?: eventName.orEmpty()) {
                "response.output_text.delta" -> (json.opt("delta") as? String)?.takeIf { it.isNotEmpty() }?.let { listOf(AgentEvent.TextDelta(it)) } ?: emptyList()
                "response.output_item.added" -> itemAdded(json.optJSONObject("item"))
                "response.function_call_arguments.delta" -> argumentsDelta(json)
                "response.function_call_arguments.done" -> {
                    val call = calls[json.optString("item_id")]
                    val full = json.opt("arguments") as? String
                    if (call != null && full != null) {
                        call.arguments.setLength(0)
                        call.arguments.append(full)
                    }
                    emptyList()
                }
                "response.output_item.done" -> itemDone(json.optJSONObject("item"))
                "response.completed" -> completed(json.optJSONObject("response"))
                "response.incomplete" -> incomplete(json.optJSONObject("response"))
                "response.failed" -> {
                    finished = true
                    listOf(failure(json.optJSONObject("response")?.optJSONObject("error")))
                }
                "error" -> {
                    finished = true
                    listOf(failure(json.optJSONObject("error") ?: json))
                }
                // `response.created`, `content_part.*`, `reasoning_summary_*`… e tipos novos: ignorados.
                else -> emptyList()
            }
        }

        private fun itemAdded(item: JSONObject?): List<AgentEvent> {
            if (item == null || item.optString("type") != "function_call") return emptyList()
            val id = item.optString("id")
            if (id.isEmpty()) return emptyList()
            val call = Call(item.optString("call_id"), item.optString("name"))
            (item.opt("arguments") as? String)?.let { call.arguments.append(it) }
            calls[id] = call
            return emptyList()
        }

        private fun argumentsDelta(json: JSONObject): List<AgentEvent> {
            val call = calls[json.optString("item_id")] ?: return emptyList()
            call.arguments.append(json.opt("delta") as? String ?: "")
            if (call.arguments.length > MAX_TOOL_INPUT_CHARS) {
                finished = true
                return listOf(AgentEvent.Error(ReasoningError.ENGINE, "Os argumentos de uma ferramenta passaram de ${MAX_TOOL_INPUT_CHARS / 1024} KB; a resposta foi interrompida."))
            }
            return emptyList()
        }

        /** O item fechou: os argumentos finais vêm no próprio item (o que vale); o acumulado é o plano B. */
        private fun itemDone(item: JSONObject?): List<AgentEvent> {
            if (item == null || item.optString("type") != "function_call") return emptyList()
            val known = calls[item.optString("id")]
            val callId = item.optString("call_id").ifEmpty { known?.callId.orEmpty() }
            val name = item.optString("name").ifEmpty { known?.name.orEmpty() }
            val args = (item.opt("arguments") as? String) ?: known?.arguments?.toString().orEmpty()
            return emitCall(callId, name, args)
        }

        private fun emitCall(callId: String, name: String, args: String): List<AgentEvent> {
            if (callId.isEmpty() || name.isEmpty() || !emittedIds.add(callId)) return emptyList()
            return listOf(AgentEvent.ToolCall(callId, name, args.ifBlank { "{}" }))
        }

        private fun completed(response: JSONObject?): List<AgentEvent> {
            finished = true
            val output = response?.optJSONArray("output") ?: JSONArray()
            val events = ArrayList<AgentEvent>()
            // O que ainda não saiu por `output_item.done` (um fluxo sem esses eventos): a lista final da resposta é a verdade.
            var hasCalls = false
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                if (item.optString("type") != "function_call") continue
                hasCalls = true
                events += emitCall(item.optString("call_id"), item.optString("name"), item.opt("arguments") as? String ?: "")
            }
            val reason = if (hasCalls || emittedIds.isNotEmpty()) AgentStopReason.TOOL_USE else AgentStopReason.END_TURN
            events += AgentEvent.Done(reason, usage(response), providerState(output))
            return events
        }

        private fun incomplete(response: JSONObject?): List<AgentEvent> {
            finished = true
            val reason = when (response?.optJSONObject("incomplete_details")?.optString("reason").orEmpty()) {
                "max_output_tokens" -> AgentStopReason.TRUNCATED
                "content_filter" -> AgentStopReason.REFUSAL
                else -> AgentStopReason.OTHER
            }
            // Chamadas de uma resposta cortada NÃO são confiáveis: o laço não as executa em TRUNCATED/REFUSAL.
            return listOf(AgentEvent.Done(reason, usage(response), providerState = null))
        }

        private fun usage(response: JSONObject?): TokenUsage? {
            val usage = response?.optJSONObject("usage") ?: return null
            val input = (usage.opt("input_tokens") as? Number)?.toInt()
            val output = (usage.opt("output_tokens") as? Number)?.toInt()
            return if (input != null || output != null) TokenUsage(input ?: 0, output ?: 0) else null
        }

        /**
         * Os itens de saída da resposta como a API os mandou, guardados para voltarem intactos na volta de ferramenta seguinte — só quando
         * há raciocínio (`reasoning`) E chamada de ferramenta; sem isso, reconstruir do texto e das chamadas dá o mesmo.
         */
        private fun providerState(output: JSONArray): ProviderState? {
            val items = (0 until output.length()).mapNotNull { output.optJSONObject(it) }
            if (items.none { it.optString("type") == "reasoning" } || items.none { it.optString("type") == "function_call" }) return null
            return ProviderState(PROVIDER, output.toString())
        }

        /** O erro de uma resposta que falhou no meio: só o CÓDIGO decide (o texto da mensagem não é repetido ao usuário). */
        private fun failure(error: JSONObject?): AgentEvent.Error {
            val code = error?.optString("code").orEmpty()
            val kind = when {
                code == "rate_limit_exceeded" || code == "insufficient_quota" || code == "subscription_sharing_usage_limit_exceeded" -> ReasoningError.RATE_LIMIT
                code == "invalid_api_key" || code == "subscription_sharing_invalid_user" -> ReasoningError.UNAUTHORIZED
                code == "server_error" || code == "subscription_sharing_usage_unavailable" || code == "subscription_sharing_user_unavailable" -> ReasoningError.NETWORK
                code == "subscription_sharing_user_not_eligible" -> ReasoningError.NOT_ELIGIBLE
                else -> ReasoningError.ENGINE
            }
            val safe = code.takeIf { it.length <= 64 && it.matches(SAFE_CODE) }
            return AgentEvent.Error(kind, "a resposta da OpenAI falhou" + (safe?.let { " ($it)" } ?: ""))
        }
    }

    private val SAFE_CODE = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")
}
