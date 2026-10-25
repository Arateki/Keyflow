package com.keyflow.reasoning.openai

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.AgentStopReason
import com.keyflow.agent.AgentToolCall
import com.keyflow.agent.ProviderState
import com.keyflow.agent.ToolImage
import com.keyflow.reasoning.ReasoningError
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** A conversão do histórico neutro para os itens de `input` da Responses API, e a leitura dos eventos, sem rede. */
class OpenAiAgentWireTest {

    private fun items(vararg messages: AgentMessage, flatten: Boolean = false): List<JSONObject> =
        OpenAiAgentWire.toInput(messages.toList(), flatten).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun read(vararg events: Pair<String, String>): List<AgentEvent> {
        val reader = OpenAiAgentWire.StreamReader()
        return events.flatMap { (name, data) -> reader.handle(name, data) }
    }

    // --- o histórico ---

    @Test
    fun `a plain conversation is easy input messages`() {
        val out = items(AgentMessage.User("oi"), AgentMessage.Assistant("olá"), AgentMessage.User("tudo bem?"))
        assertThat(out.map { it.getString("role") + ":" + it.getString("content") }).containsExactly("user:oi", "assistant:olá", "user:tudo bem?").inOrder()
    }

    @Test
    fun `a call and its result are loose items with the same call_id, and the arguments are text`() {
        val out = items(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("call_1", "settings_set", """{"key":"themeDark","value":"true"}"""))),
            AgentMessage.ToolResult("call_1", "feito"),
        )
        assertThat(out.map { it.optString("type").ifEmpty { it.optString("role") } }).containsExactly("user", "assistant", "function_call", "function_call_output").inOrder()
        val call = out[2]
        assertThat(call.getString("call_id")).isEqualTo("call_1")
        assertThat(call.getString("name")).isEqualTo("settings_set")
        assertThat(JSONObject(call.getString("arguments")).getString("key")).isEqualTo("themeDark")
        assertThat(call.has("id")).isFalse()
        assertThat(out[3].getString("output")).isEqualTo("feito")
    }

    @Test
    fun `a tool error says so in the output, since the item has no error flag`() {
        val out = items(
            AgentMessage.User("a"),
            AgentMessage.Assistant("", listOf(AgentToolCall("c", "x", "{}"))),
            AgentMessage.ToolResult("c", "deu ruim", isError = true),
        )
        assertThat(out.last().getString("output")).isEqualTo("[erro da ferramenta] deu ruim")
    }

    @Test
    fun `arguments that are not an object become an empty object`() {
        val out = items(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("c", "x", "[1,2"))), AgentMessage.ToolResult("c", "ok"))
        assertThat(out[1].getString("arguments")).isEqualTo("{}")
    }

    @Test
    fun `a call with no result gets an error result before the request, so the api does not refuse the conversation`() {
        val out = items(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("c", "x", "{}"))), AgentMessage.User("outra coisa"))
        assertThat(out.map { it.optString("type").ifEmpty { it.optString("role") } }).containsExactly("user", "function_call", "function_call_output", "user").inOrder()
    }

    @Test
    fun `empty messages are dropped and an image is not sent`() {
        val out = items(
            AgentMessage.User("  "),
            AgentMessage.User("a"),
            AgentMessage.Assistant("", listOf(AgentToolCall("c", "skin_preview", "{}"))),
            AgentMessage.ToolResult("c", "relatório", image = ToolImage("image/png", byteArrayOf(1, 2))),
        )
        assertThat(out.map { it.optString("type").ifEmpty { it.optString("role") } }).containsExactly("user", "function_call", "function_call_output").inOrder()
        assertThat(out.last().get("output")).isEqualTo("relatório")
    }

    @Test
    fun `reasoning of the current tool turn is replayed only while the ids still match the calls`() {
        val state = ProviderState("openai", """[{"type":"reasoning","id":"rs_1","encrypted_content":"x"},{"type":"function_call","id":"fc_1","call_id":"call_1","name":"skin_list","arguments":"{}"}]""")
        val matching = items(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("call_1", "skin_list", "{}")), state), AgentMessage.ToolResult("call_1", "ok"))
        assertThat(matching[1].getString("type")).isEqualTo("reasoning")
        val other = items(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("call_X", "skin_list", "{}")), state), AgentMessage.ToolResult("call_X", "ok"))
        assertThat(other.map { it.optString("type") }).doesNotContain("reasoning")
        val foreign = items(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("call_1", "skin_list", "{}")), ProviderState("anthropic", state.payload)), AgentMessage.ToolResult("call_1", "ok"))
        assertThat(foreign.map { it.optString("type") }).doesNotContain("reasoning")
        val broken = items(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("call_1", "skin_list", "{}")), ProviderState("openai", "não é json")), AgentMessage.ToolResult("call_1", "ok"))
        assertThat(broken.map { it.optString("type") }).doesNotContain("reasoning")
    }

    @Test
    fun `flattened - calls and results become text`() {
        val out = items(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("c", "skin_list", "{}"))),
            AgentMessage.ToolResult("c", "ok"),
            flatten = true,
        )
        assertThat(out.map { it.getString("role") }).containsExactly("user", "assistant", "user").inOrder()
        assertThat(out[1].getString("content")).contains("[Ferramenta chamada: skin_list]")
        assertThat(out[2].getString("content")).contains("[Resultado da ferramenta skin_list]")
    }

    // --- o corpo ---

    @Test
    fun `the body has no tools key when there are none, and no output cap unless asked`() {
        val noTools = JSONObject(OpenAiAgentWire.body("m", "S", listOf(AgentMessage.User("a")), emptyList()))
        assertThat(noTools.has("tools")).isFalse()
        assertThat(noTools.has("max_output_tokens")).isFalse()
        assertThat(JSONObject(OpenAiAgentWire.body("m", "S", listOf(AgentMessage.User("a")), emptyList(), maxOutputTokens = 100)).getInt("max_output_tokens")).isEqualTo(100)
    }

    // --- os eventos ---

    @Test
    fun `text deltas pass through and an empty delta is ignored`() {
        val out = read(
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":"a"}""",
            "response.output_text.delta" to """{"type":"response.output_text.delta","delta":""}""",
        )
        assertThat(out).containsExactly(AgentEvent.TextDelta("a"))
    }

    @Test
    fun `the event name is used when the data has no type`() {
        assertThat(read("response.output_text.delta" to """{"delta":"x"}""")).containsExactly(AgentEvent.TextDelta("x"))
    }

    @Test
    fun `unknown events are ignored`() {
        assertThat(read("response.future_thing" to """{"type":"response.future_thing","x":1}""", "response.content_part.added" to """{"type":"response.content_part.added"}""")).isEmpty()
    }

    @Test
    fun `huge arguments stop the response instead of filling memory`() {
        val reader = OpenAiAgentWire.StreamReader()
        reader.handle("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc","call_id":"c","name":"x","arguments":""}}""")
        val chunk = "x".repeat(1024 * 1024)
        var last: List<AgentEvent> = emptyList()
        repeat(5) { last = reader.handle("response.function_call_arguments.delta", """{"type":"response.function_call_arguments.delta","item_id":"fc","delta":"$chunk"}""") }
        assertThat((last.single() as AgentEvent.Error).kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(reader.finished).isTrue()
    }

    @Test
    fun `completed with no calls ends the turn, and reasoning alone is not worth replaying`() {
        val out = read("response.completed" to """{"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs","encrypted_content":"x"},{"type":"message","content":[]}],"usage":{"input_tokens":1,"output_tokens":2}}}""")
        val done = out.single() as AgentEvent.Done
        assertThat(done.stopReason).isEqualTo(AgentStopReason.END_TURN)
        assertThat(done.providerState).isNull()
    }

    @Test
    fun `an incomplete response with an unknown reason is other`() {
        val done = read("response.incomplete" to """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"algo_novo"}}}""").single() as AgentEvent.Done
        assertThat(done.stopReason).isEqualTo(AgentStopReason.OTHER)
    }

    @Test
    fun `failed codes map to the error kinds and an odd code is not echoed`() {
        fun kind(code: String) = (read("response.failed" to JSONObject().put("type", "response.failed").put("response", JSONObject().put("error", JSONObject().put("code", code))).toString()).single() as AgentEvent.Error)
        assertThat(kind("rate_limit_exceeded").kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(kind("insufficient_quota").kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(kind("invalid_api_key").kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(kind("server_error").kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(kind("context_length_exceeded").kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(kind("<script>alert(1)</script>").message).doesNotContain("script")
    }

    @Test
    fun `the whole reasoning plus call output is kept as the provider state`() {
        val done = read(
            "response.completed" to """{"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs","encrypted_content":"x"},{"type":"function_call","id":"fc","call_id":"c1","name":"a","arguments":"{}"}]}}""",
        ).last() as AgentEvent.Done
        val state = done.providerState!!
        assertThat(state.provider).isEqualTo("openai")
        assertThat(JSONArray(state.payload).length()).isEqualTo(2)
    }
}
