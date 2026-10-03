package com.keyflow.reasoning.anthropic

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentToolCall
import com.keyflow.agent.ProviderState
import com.keyflow.agent.TokenUsage
import com.keyflow.agent.ToolDefinition
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.anthropic.AnthropicSseFixtures.sse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** O formato da Anthropic para o agente, sem rede: a leitura do SSE e a conversão do histórico (AGT-02, D8). */
class AnthropicAgentWireTest {

    /** Lê um corpo SSE inteiro com o leitor de verdade (blocos separados por linha em branco). */
    private fun read(body: String): List<AgentEvent> {
        val reader = AnthropicAgentWire.StreamReader()
        val out = ArrayList<AgentEvent>()
        for (block in body.trim().split("\n\n")) {
            var name: String? = null
            val data = StringBuilder()
            for (line in block.lines()) {
                when {
                    line.startsWith("event:") -> name = line.substring(6).trim()
                    line.startsWith("data:") -> data.append(line.substring(5).trimStart())
                }
            }
            out += reader.handle(name, data.toString())
        }
        return out
    }

    private fun content(message: JSONObject): List<JSONObject> = message.getJSONArray("content").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun messages(history: List<AgentMessage>, flatten: Boolean = false): List<JSONObject> =
        AnthropicAgentWire.toApiMessages(history, flatten).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    // --- SSE: texto ---

    @Test
    fun `text only - the deltas and a Done with the usage`() {
        val events = read(AnthropicSseFixtures.TEXT_ONLY)
        assertThat(events.filterIsInstance<AgentEvent.TextDelta>().map { it.text }).containsExactly("Hello", "!").inOrder()
        assertThat(events.last()).isEqualTo(AgentEvent.Done(AgentStopReason.END_TURN, TokenUsage(25, 15), null))
        assertThat(events.filterIsInstance<AgentEvent.ToolCall>()).isEmpty()
    }

    // --- SSE: ferramentas ---

    @Test
    fun `one tool use - the fragments of the arguments are joined into the whole json`() {
        val events = read(AnthropicSseFixtures.ONE_TOOL_USE)
        assertThat(events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }).isEqualTo("Okay, let's check the weather for San Francisco, CA:")
        val call = events.filterIsInstance<AgentEvent.ToolCall>().single()
        assertThat(call.id).isEqualTo("toolu_01T1x1fJ34qAmk2tNTrN7Up6")
        assertThat(call.name).isEqualTo("get_weather")
        assertThat(JSONObject(call.argsJson).getString("location")).isEqualTo("San Francisco, CA")
        assertThat(events.last()).isEqualTo(AgentEvent.Done(AgentStopReason.TOOL_USE, TokenUsage(472, 89), null))
    }

    @Test
    fun `the call is emitted only when its block closes`() {
        val reader = AnthropicAgentWire.StreamReader()
        reader.handle("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"x","input":{}}}""")
        val mid = reader.handle("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\":1"}}""")
        assertThat(mid).isEmpty()
        val end = reader.handle("content_block_stop", """{"type":"content_block_stop","index":0}""")
        assertThat(end).containsExactly(AgentEvent.ToolCall("t1", "x", "{\"a\":1"))
    }

    @Test
    fun `several tool uses in one turn come out in order with their own arguments`() {
        val calls = read(AnthropicSseFixtures.TWO_TOOL_USES).filterIsInstance<AgentEvent.ToolCall>()
        assertThat(calls.map { it.id }).containsExactly("toolu_01AAA", "toolu_01BBB").inOrder()
        assertThat(calls.map { it.name }).containsExactly("skin_list", "app_status").inOrder()
        assertThat(JSONObject(calls[0].argsJson).length()).isEqualTo(0)
        assertThat(JSONObject(calls[1].argsJson).getBoolean("detail")).isTrue()
    }

    @Test
    fun `a tool with no arguments gets an empty object, not an empty string`() {
        val call = read(AnthropicSseFixtures.TOOL_USE_NO_ARGS).filterIsInstance<AgentEvent.ToolCall>().single()
        assertThat(call.argsJson).isEqualTo("{}")
    }

    @Test
    fun `a json string split in the middle of an escape is joined correctly`() {
        val body = sse(
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"x","input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"svg\": \"<svg width=\\"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"24\\\" height=\\\"24\\\"/>\\n\", \"n\": 1"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"}"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        )
        val args = JSONObject(read(body).filterIsInstance<AgentEvent.ToolCall>().single().argsJson)
        assertThat(args.getString("svg")).isEqualTo("<svg width=\"24\" height=\"24\"/>\n")
        assertThat(args.getInt("n")).isEqualTo(1)
    }

    @Test
    fun `a tool use cut by the token limit never becomes a call and the turn says truncated`() {
        val events = read(AnthropicSseFixtures.CUT_BY_MAX_TOKENS_IN_TOOL_USE)
        assertThat(events.filterIsInstance<AgentEvent.ToolCall>()).isEmpty() // o bloco nunca fechou
        assertThat((events.last() as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.TRUNCATED)
    }

    @Test
    fun `a huge argument stream is cut off`() {
        val reader = AnthropicAgentWire.StreamReader()
        reader.handle("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"x","input":{}}}""")
        val chunk = "a".repeat(1024 * 1024)
        var last: List<AgentEvent> = emptyList()
        repeat(5) { last = reader.handle("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"$chunk"}}""") }
        assertThat((last.single() as AgentEvent.Error).kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(reader.finished).isTrue()
    }

    // --- SSE: raciocínio assinado ---

    @Test
    fun `thinking and its signature are kept for the next turn of a tool loop`() {
        val done = read(AnthropicSseFixtures.THINKING_THEN_TOOL_USE).last() as AgentEvent.Done
        val state = done.providerState!!
        assertThat(state.provider).isEqualTo("anthropic")
        val blocks = JSONArray(state.payload).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertThat(blocks.map { it.getString("type") }).containsExactly("thinking", "text", "tool_use").inOrder()
        assertThat(blocks[0].getString("signature")).isEqualTo(AnthropicSseFixtures.SIGNATURE)
        assertThat(blocks[0].getString("thinking")).contains("Euclidean algorithm")
        assertThat(blocks[0].getString("thinking")).contains("462 = 3 × 147 + 21")
        assertThat(blocks[1].getString("text")).isEqualTo("Vou consultar.")
        assertThat(blocks[2].getString("id")).isEqualTo("toolu_01Think")
        assertThat(blocks[2].getJSONObject("input").length()).isEqualTo(0)
    }

    @Test
    fun `an omitted thinking block is kept with an empty text and its signature`() {
        val done = read(AnthropicSseFixtures.OMITTED_THINKING_THEN_TEXT).last() as AgentEvent.Done
        val blocks = JSONArray(done.providerState!!.payload)
        assertThat(blocks.getJSONObject(0).getString("thinking")).isEmpty()
        assertThat(blocks.getJSONObject(0).getString("signature")).isEqualTo(AnthropicSseFixtures.SIGNATURE)
        assertThat(blocks.getJSONObject(1).getString("text")).isEqualTo("Feito.")
    }

    @Test
    fun `a redacted thinking block is kept as it came`() {
        val done = read(AnthropicSseFixtures.REDACTED_THEN_TOOL_USE).last() as AgentEvent.Done
        val blocks = JSONArray(done.providerState!!.payload)
        assertThat(blocks.getJSONObject(0).getString("type")).isEqualTo("redacted_thinking")
        assertThat(blocks.getJSONObject(0).getString("data")).startsWith("EmwKAhgBEgy3va3pzix")
        assertThat(blocks.getJSONObject(1).getString("type")).isEqualTo("tool_use")
    }

    @Test
    fun `without thinking there is nothing to keep`() {
        assertThat((read(AnthropicSseFixtures.ONE_TOOL_USE).last() as AgentEvent.Done).providerState).isNull()
    }

    // --- SSE: parada e erro ---

    @Test
    fun `the stop reasons map to the app vocabulary`() {
        fun stop(reason: String?): AgentStopReason {
            val delta = if (reason == null) """{"type":"message_delta","delta":{"stop_sequence":null}}""" else """{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null}}"""
            return (read(sse("message_delta" to delta, "message_stop" to """{"type":"message_stop"}""")).last() as AgentEvent.Done).stopReason
        }
        assertThat(stop("end_turn")).isEqualTo(AgentStopReason.END_TURN)
        assertThat(stop("tool_use")).isEqualTo(AgentStopReason.TOOL_USE)
        assertThat(stop("max_tokens")).isEqualTo(AgentStopReason.TRUNCATED)
        assertThat(stop("model_context_window_exceeded")).isEqualTo(AgentStopReason.TRUNCATED)
        assertThat(stop("refusal")).isEqualTo(AgentStopReason.REFUSAL)
        assertThat(stop("stop_sequence")).isEqualTo(AgentStopReason.OTHER)
        assertThat(stop("pause_turn")).isEqualTo(AgentStopReason.OTHER)
        assertThat(stop("something_new")).isEqualTo(AgentStopReason.OTHER)
        assertThat(stop(null)).isEqualTo(AgentStopReason.END_TURN)
    }

    @Test
    fun `an error event in the middle of the stream is an error of the right kind`() {
        val overloaded = read(AnthropicSseFixtures.OVERLOADED_EVENT).single() as AgentEvent.Error
        assertThat(overloaded.kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(overloaded.message).contains("overloaded_error")
        assertThat(overloaded.message).contains("Overloaded")
        fun kindOf(type: String) = (read(sse("error" to """{"type":"error","error":{"type":"$type","message":"m"}}""")).single() as AgentEvent.Error).kind
        assertThat(kindOf("rate_limit_error")).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(kindOf("authentication_error")).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(kindOf("permission_error")).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(kindOf("api_error")).isEqualTo(ReasoningError.NETWORK)
        assertThat(kindOf("invalid_request_error")).isEqualTo(ReasoningError.ENGINE)
    }

    @Test
    fun `unknown event types, pings and garbage are ignored without failing`() {
        val body = sse(
            "ping" to """{"type": "ping"}""",
            "brand_new_event" to """{"type":"brand_new_event","x":1}""",
            "content_block_delta" to """{"type":"content_block_delta","index":9,"delta":{"type":"text_delta","text":"sem bloco"}}""",
            "content_block_delta" to """not json at all""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        val events = read(body)
        assertThat(events).hasSize(1)
        assertThat(events.single()).isInstanceOf(AgentEvent.Done::class.java)
    }

    // --- histórico → API ---

    private val call1 = AgentToolCall("toolu_1", "skin_list", "{}")
    private val call2 = AgentToolCall("toolu_2", "app_status", """{"detail":true}""")

    @Test
    fun `plain turns become user and assistant messages with text blocks`() {
        val m = messages(listOf(AgentMessage.User("oi"), AgentMessage.Assistant("olá"), AgentMessage.User("tudo bem?")))
        assertThat(m.map { it.getString("role") }).containsExactly("user", "assistant", "user").inOrder()
        assertThat(content(m[0]).single().toString()).isEqualTo(JSONObject().put("type", "text").put("text", "oi").toString())
        assertThat(content(m[1]).single().getString("text")).isEqualTo("olá")
    }

    @Test
    fun `a tool turn becomes tool_use blocks and one user message with every tool_result`() {
        val m = messages(
            listOf(
                AgentMessage.User("faça"),
                AgentMessage.Assistant("vou ver", listOf(call1, call2)),
                AgentMessage.ToolResult("toolu_1", "3 skins"),
                AgentMessage.ToolResult("toolu_2", "falhou", isError = true),
            ),
        )
        assertThat(m).hasSize(3)
        val assistant = content(m[1])
        assertThat(assistant.map { it.getString("type") }).containsExactly("text", "tool_use", "tool_use").inOrder()
        assertThat(assistant[1].getString("id")).isEqualTo("toolu_1")
        assertThat(assistant[1].getString("name")).isEqualTo("skin_list")
        assertThat(assistant[1].getJSONObject("input").length()).isEqualTo(0)
        assertThat(assistant[2].getJSONObject("input").getBoolean("detail")).isTrue()

        val results = content(m[2])
        assertThat(m[2].getString("role")).isEqualTo("user")
        assertThat(results.map { it.getString("type") }).containsExactly("tool_result", "tool_result").inOrder()
        assertThat(results[0].getString("tool_use_id")).isEqualTo("toolu_1")
        assertThat(results[0].getString("content")).isEqualTo("3 skins")
        assertThat(results[0].has("is_error")).isFalse()
        assertThat(results[1].getBoolean("is_error")).isTrue()
    }

    @Test
    fun `the tool results come before any text of the next user message`() {
        val m = messages(
            listOf(
                AgentMessage.User("a"),
                AgentMessage.Assistant("", listOf(call1)),
                AgentMessage.ToolResult("toolu_1", "r"),
                AgentMessage.User("e agora?"),
            ),
        )
        assertThat(m).hasSize(3)
        assertThat(content(m[2]).map { it.getString("type") }).containsExactly("tool_result", "text").inOrder()
    }

    @Test
    fun `an out-of-order history is repaired instead of becoming a 400`() {
        // Um histórico fora de ordem (um defeito de quem guarda) não pode virar um 400.
        val m = messages(listOf(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(call1)), AgentMessage.User("texto"), AgentMessage.ToolResult("toolu_1", "r")))
        // O resultado órfão sai (a chamada ganha um resultado sintético logo depois do modelo), e o texto vem depois dele.
        assertThat(content(m.last()).map { it.getString("type") }).containsExactly("tool_result", "text").inOrder()
    }

    @Test
    fun `an empty tool result omits the content field`() {
        val m = messages(listOf(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(call1)), AgentMessage.ToolResult("toolu_1", "  ")))
        assertThat(content(m[2]).single().has("content")).isFalse()
    }

    @Test
    fun `consecutive user messages are merged and blanks are dropped`() {
        val m = messages(listOf(AgentMessage.User("um"), AgentMessage.User("   "), AgentMessage.User("dois"), AgentMessage.Assistant(""), AgentMessage.Assistant("resposta")))
        assertThat(m.map { it.getString("role") }).containsExactly("user", "assistant").inOrder()
        assertThat(content(m[0]).map { it.getString("text") }).containsExactly("um", "dois").inOrder()
        assertThat(content(m[1]).single().getString("text")).isEqualTo("resposta")
    }

    @Test
    fun `a call left without a result is closed with an error result`() {
        val m = messages(listOf(AgentMessage.User("a"), AgentMessage.Assistant("vou", listOf(call1)), AgentMessage.User("cancelei")))
        assertThat(content(m[2]).map { it.getString("type") }).containsExactly("tool_result", "text").inOrder()
        assertThat(content(m[2])[0].getBoolean("is_error")).isTrue()
    }

    @Test
    fun `an invalid arguments string becomes an empty object so the block stays valid`() {
        val m = messages(listOf(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("t", "x", "isso não é json"))), AgentMessage.ToolResult("t", "erro")))
        assertThat(content(m[1]).single().getJSONObject("input").length()).isEqualTo(0)
    }

    private val thinkingState = ProviderState(
        "anthropic",
        """[{"type":"thinking","thinking":"","signature":"SIG-A"},{"type":"tool_use","id":"toolu_1","name":"skin_list","input":{}}]""",
    )

    @Test
    fun `the signed thinking comes back untouched in the tool turn in progress`() {
        val m = messages(
            listOf(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(call1), thinkingState), AgentMessage.ToolResult("toolu_1", "r")),
        )
        val blocks = content(m[1])
        assertThat(blocks.map { it.getString("type") }).containsExactly("thinking", "tool_use").inOrder()
        assertThat(blocks[0].getString("signature")).isEqualTo("SIG-A")
    }

    @Test
    fun `thinking of an older turn is left out, which the documentation allows`() {
        val m = messages(
            listOf(
                AgentMessage.User("a"), AgentMessage.Assistant("", listOf(call1), thinkingState), AgentMessage.ToolResult("toolu_1", "r"), AgentMessage.Assistant("fim"),
                AgentMessage.User("b"),
            ),
        )
        assertThat(content(m[1]).map { it.getString("type") }).containsExactly("tool_use") // reconstruída, sem o bloco thinking
    }

    @Test
    fun `thinking from another provider or with ids that do not match is ignored`() {
        val other = thinkingState.copy(provider = "openai")
        val mismatched = thinkingState.copy(payload = """[{"type":"thinking","thinking":"","signature":"S"},{"type":"tool_use","id":"outro","name":"x","input":{}}]""")
        val broken = thinkingState.copy(payload = "not json")
        for (state in listOf(other, mismatched, broken)) {
            val m = messages(listOf(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(call1), state), AgentMessage.ToolResult("toolu_1", "r")))
            assertThat(content(m[1]).map { it.getString("type") }).containsExactly("tool_use")
        }
    }

    @Test
    fun `without active tools the tool blocks become plain text, because the API refuses them otherwise`() {
        val m = messages(
            listOf(AgentMessage.User("a"), AgentMessage.Assistant("vou ver", listOf(call1)), AgentMessage.ToolResult("toolu_1", "3 skins"), AgentMessage.Assistant("são 3")),
            flatten = true,
        )
        val text = m.joinToString("\n") { it.toString() }
        assertThat(text).doesNotContain("tool_use")
        assertThat(text).doesNotContain("tool_result")
        assertThat(content(m[1]).single().getString("text")).contains("vou ver")
        assertThat(content(m[1]).single().getString("text")).contains("skin_list")
        assertThat(content(m[2]).single().getString("text")).contains("Resultado da ferramenta skin_list")
        assertThat(content(m[2]).single().getString("text")).contains("3 skins")
    }

    // --- o corpo ---

    private val tool = ToolDefinition("skin_list", "Lista as skins.", JSONObject("""{"type":"object","properties":{}}"""))

    private fun body(
        tools: List<ToolDefinition> = listOf(tool),
        isOAuth: Boolean = false,
        eager: Boolean = false,
        messages: List<AgentMessage> = listOf(AgentMessage.User("oi")),
    ) = JSONObject(AnthropicAgentWire.body("claude-opus-5-5", "SISTEMA", messages, tools, 4096, isOAuth, eager))

    @Test
    fun `the body has the model, the cap, streaming, the system prompt and the tools`() {
        val b = body()
        assertThat(b.getString("model")).isEqualTo("claude-opus-5-5")
        assertThat(b.getInt("max_tokens")).isEqualTo(4096)
        assertThat(b.getBoolean("stream")).isTrue()
        assertThat(b.getString("system")).isEqualTo("SISTEMA")
        val t = b.getJSONArray("tools").getJSONObject(0)
        assertThat(t.getString("name")).isEqualTo("skin_list")
        assertThat(t.getString("description")).isEqualTo("Lista as skins.")
        assertThat(t.getJSONObject("input_schema").getString("type")).isEqualTo("object")
        assertThat(t.has("eager_input_streaming")).isFalse()
        assertThat(b.has("tool_choice")).isFalse()
        assertThat(b.has("thinking")).isFalse()
    }

    @Test
    fun `eager input streaming is sent only when asked`() {
        assertThat(body(eager = true).getJSONArray("tools").getJSONObject(0).getBoolean("eager_input_streaming")).isTrue()
    }

    @Test
    fun `no tools means no tools field`() {
        assertThat(body(tools = emptyList()).has("tools")).isFalse()
    }

    @Test
    fun `the login credential gets the Claude Code identity first in the system prompt, like the text engine`() {
        assertThat(body(isOAuth = true).getString("system")).isEqualTo("You are Claude Code, Anthropic's official CLI for Claude.\n\nSISTEMA")
    }
}
