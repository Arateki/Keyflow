package com.keyflow.reasoning.google

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

/** A conversão do histórico neutro para `contents` e a leitura dos chunks do Gemini, sem rede. */
class GoogleAgentWireTest {

    private fun contents(vararg messages: AgentMessage, flatten: Boolean = false): List<JSONObject> =
        GoogleAgentWire.toContents(messages.toList(), flatten).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun JSONObject.partsList(): List<JSONObject> = getJSONArray("parts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun read(vararg chunks: String, ids: () -> String = run { var n = 0; { "id-${++n}" } }): List<AgentEvent> {
        val reader = GoogleAgentWire.StreamReader(ids)
        return chunks.flatMap { reader.handle(it) }
    }

    // --- o histórico ---

    @Test
    fun `a plain conversation alternates user and model`() {
        val out = contents(AgentMessage.User("oi"), AgentMessage.Assistant("olá"), AgentMessage.User("tudo bem?"))
        assertThat(out.map { it.getString("role") }).containsExactly("user", "model", "user").inOrder()
        assertThat(out[1].partsList().single().getString("text")).isEqualTo("olá")
    }

    @Test
    fun `several results of one turn go together in one user content, before any text`() {
        val out = contents(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("c1", "skin_list", "{}"), AgentToolCall("c2", "app_status", """{"detail":true}"""))),
            AgentMessage.ToolResult("c1", "r1"),
            AgentMessage.ToolResult("c2", "r2"),
            AgentMessage.User("e então?"),
        )
        assertThat(out.map { it.getString("role") }).containsExactly("user", "model", "user").inOrder()
        val calls = out[1].partsList()
        assertThat(calls.map { it.optJSONObject("functionCall")?.getString("name") ?: "texto" }).containsExactly("texto", "skin_list", "app_status").inOrder()
        assertThat(calls[2].getJSONObject("functionCall").getJSONObject("args").getBoolean("detail")).isTrue()
        val results = out[2].partsList()
        assertThat(results.map { it.has("functionResponse") }).containsExactly(true, true, false).inOrder()
        assertThat(results[0].getJSONObject("functionResponse").getString("name")).isEqualTo("skin_list")
        assertThat(results[1].getJSONObject("functionResponse").getString("name")).isEqualTo("app_status")
        assertThat(results[2].getString("text")).isEqualTo("e então?")
    }

    @Test
    fun `arguments that are not an object become an empty object`() {
        val out = contents(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("c", "x", "[1,2"))), AgentMessage.ToolResult("c", "ok"))
        assertThat(out[1].partsList().single().getJSONObject("functionCall").getJSONObject("args").length()).isEqualTo(0)
    }

    @Test
    fun `a call with no result gets an error result before the request`() {
        val out = contents(AgentMessage.User("a"), AgentMessage.Assistant("", listOf(AgentToolCall("c", "x", "{}"))), AgentMessage.User("outra coisa"))
        assertThat(out.map { it.getString("role") }).containsExactly("user", "model", "user").inOrder()
        val results = out[2].partsList()
        assertThat(results[0].getJSONObject("functionResponse").getJSONObject("response").has("error")).isTrue()
        assertThat(results[1].getString("text")).isEqualTo("outra coisa")
    }

    @Test
    fun `empty messages are dropped and an image is not sent`() {
        val out = contents(
            AgentMessage.User("  "),
            AgentMessage.User("a"),
            AgentMessage.Assistant("", listOf(AgentToolCall("c", "skin_preview", "{}"))),
            AgentMessage.ToolResult("c", "relatório", image = ToolImage("image/png", byteArrayOf(1, 2))),
        )
        assertThat(out.map { it.getString("role") }).containsExactly("user", "model", "user").inOrder()
        assertThat(out.last().toString()).doesNotContain("inlineData")
    }

    @Test
    fun `signed parts of the current tool turn are replayed only while the calls still match`() {
        val payload = """[{"text":"vou","thoughtSignature":"SIG"},{"functionCall":{"name":"skin_list","args":{},"id":"api-1"},"thoughtSignature":"SIG2"}]"""
        val state = ProviderState("google", payload)
        fun parts(call: AgentToolCall, st: ProviderState?) = contents(AgentMessage.User("a"), AgentMessage.Assistant("vou", listOf(call), st), AgentMessage.ToolResult(call.id, "ok"))[1].partsList()
        assertThat(parts(AgentToolCall("api-1", "skin_list", "{}"), state)[0].optString("thoughtSignature")).isEqualTo("SIG")
        // outro nome de ferramenta, outro id da API, estado de outro provedor ou quebrado: reconstruído, sem assinatura
        assertThat(parts(AgentToolCall("api-1", "outra", "{}"), state).toString()).doesNotContain("SIG")
        assertThat(parts(AgentToolCall("api-9", "skin_list", "{}"), state).toString()).doesNotContain("SIG")
        assertThat(parts(AgentToolCall("api-1", "skin_list", "{}"), ProviderState("anthropic", payload)).toString()).doesNotContain("SIG")
        assertThat(parts(AgentToolCall("api-1", "skin_list", "{}"), ProviderState("google", "não é json")).toString()).doesNotContain("SIG")
    }

    @Test
    fun `an older turn is rebuilt without signatures even when it has a state`() {
        val state = ProviderState("google", """[{"functionCall":{"name":"skin_list","args":{}},"thoughtSignature":"SIG"}]""")
        val out = contents(
            AgentMessage.User("a"),
            AgentMessage.Assistant("", listOf(AgentToolCall("${GoogleAgentWire.SYNTHETIC_ID_PREFIX}1", "skin_list", "{}")), state),
            AgentMessage.ToolResult("${GoogleAgentWire.SYNTHETIC_ID_PREFIX}1", "ok"),
            AgentMessage.Assistant("feito"),
            AgentMessage.User("e agora?"),
        )
        assertThat(out.toString()).doesNotContain("SIG")
    }

    @Test
    fun `flattened - calls and results become text`() {
        val out = contents(
            AgentMessage.User("a"),
            AgentMessage.Assistant("vou", listOf(AgentToolCall("c", "skin_list", "{}"))),
            AgentMessage.ToolResult("c", "ok"),
            flatten = true,
        )
        assertThat(out.map { it.getString("role") }).containsExactly("user", "model", "user").inOrder()
        assertThat(out[1].partsList().single().getString("text")).contains("[Ferramenta chamada: skin_list]")
        assertThat(out[2].partsList().single().getString("text")).contains("[Resultado da ferramenta skin_list]")
    }

    // --- o corpo ---

    @Test
    fun `the body has no tools key when there are none, and no output cap unless asked`() {
        val body = JSONObject(GoogleAgentWire.body("S", listOf(AgentMessage.User("a")), emptyList()))
        assertThat(body.has("tools")).isFalse()
        assertThat(body.has("generationConfig")).isFalse()
        assertThat(JSONObject(GoogleAgentWire.body("S", listOf(AgentMessage.User("a")), emptyList(), maxOutputTokens = 100)).getJSONObject("generationConfig").getInt("maxOutputTokens")).isEqualTo(100)
    }

    // --- os chunks ---

    @Test
    fun `consecutive plain text chunks merge in the stored parts, a signed one stays apart`() {
        val reader = GoogleAgentWire.StreamReader { "i" }
        reader.handle("""{"candidates":[{"content":{"parts":[{"text":"a"}]}}]}""")
        reader.handle("""{"candidates":[{"content":{"parts":[{"text":"b"}]}}]}""")
        reader.handle("""{"candidates":[{"content":{"parts":[{"text":"c","thoughtSignature":"S"}]}}]}""")
        val out = reader.handle("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"x","args":{}}}]},"finishReason":"STOP"}]}""")
        val state = (out.last() as AgentEvent.Done).providerState!!
        val parts = JSONArray(state.payload)
        assertThat(parts.length()).isEqualTo(3)
        assertThat(parts.getJSONObject(0).getString("text")).isEqualTo("ab")
        assertThat(parts.getJSONObject(1).getString("thoughtSignature")).isEqualTo("S")
    }

    @Test
    fun `no signature means no provider state, since rebuilding gives the same turn`() {
        val out = read("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"x","args":{}}}]},"finishReason":"STOP"}]}""")
        assertThat((out.last() as AgentEvent.Done).providerState).isNull()
    }

    @Test
    fun `finish reasons map to the stop reasons of the app`() {
        fun stop(reason: String, withCall: Boolean = false): AgentEvent {
            val parts = if (withCall) """[{"functionCall":{"name":"x","args":{}}}]""" else "[]"
            return read("""{"candidates":[{"content":{"parts":$parts},"finishReason":"$reason"}]}""").last()
        }
        assertThat((stop("STOP") as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.END_TURN)
        assertThat((stop("STOP", withCall = true) as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.TOOL_USE)
        assertThat((stop("MAX_TOKENS") as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.TRUNCATED)
        assertThat((stop("CONTINUATION") as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.TRUNCATED)
        for (refusal in listOf("SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII")) assertThat((stop(refusal) as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.REFUSAL)
        for (invalid in listOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "MISSING_THOUGHT_SIGNATURE", "MALFORMED_RESPONSE")) assertThat((stop(invalid) as AgentEvent.Error).kind).isEqualTo(ReasoningError.ENGINE)
        assertThat((stop("OTHER") as AgentEvent.Done).stopReason).isEqualTo(AgentStopReason.OTHER)
    }

    @Test
    fun `an unspecified finish reason is not the end`() {
        val reader = GoogleAgentWire.StreamReader { "i" }
        assertThat(reader.handle("""{"candidates":[{"content":{"parts":[{"text":"a"}]},"finishReason":"FINISH_REASON_UNSPECIFIED"}]}""")).containsExactly(AgentEvent.TextDelta("a"))
        assertThat(reader.finished).isFalse()
    }

    @Test
    fun `the done marker and unknown chunks are ignored`() {
        assertThat(read("[DONE]", """{"modelVersion":"x"}""", """{"candidates":[]}""")).isEmpty()
    }

    @Test
    fun `error codes map to the error kinds and an odd status is not echoed`() {
        fun error(code: Int, status: String) = read(JSONObject().put("error", JSONObject().put("code", code).put("status", status)).toString()).single() as AgentEvent.Error
        assertThat(error(429, "RESOURCE_EXHAUSTED").kind).isEqualTo(ReasoningError.RATE_LIMIT)
        assertThat(error(403, "PERMISSION_DENIED").kind).isEqualTo(ReasoningError.UNAUTHORIZED)
        assertThat(error(503, "UNAVAILABLE").kind).isEqualTo(ReasoningError.NETWORK)
        assertThat(error(400, "INVALID_ARGUMENT").kind).isEqualTo(ReasoningError.ENGINE)
        assertThat(error(400, "<script>").message).doesNotContain("script")
    }

    @Test
    fun `the synthetic id prefix is recognised and a real id is not`() {
        assertThat(GoogleAgentWire.isSynthetic(GoogleAgentWire.syntheticId())).isTrue()
        assertThat(GoogleAgentWire.isSynthetic("call_abc")).isFalse()
        assertThat(GoogleAgentWire.syntheticId()).isNotEqualTo(GoogleAgentWire.syntheticId())
    }
}
