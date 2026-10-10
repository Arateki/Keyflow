package com.keyflow.agenthost

import com.google.common.truth.Truth.assertThat
import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.AgentMessage
import com.keyflow.agent.ToolDefinition
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** AGT-05 / A1: com quem a próxima mensagem conversa — ferramentas só na Anthropic por chave de API; no resto, o chat de texto de hoje, com a nota. */
class AgentBackendsTest {

    private class FakeText(val requests: MutableList<ReasoningRequest> = CopyOnWriteArrayList(), val events: List<ReasoningEvent> = listOf(ReasoningEvent.Delta("oi"), ReasoningEvent.Done)) : ReasoningEngine {
        override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = flow {
            requests += request
            events.forEach { emit(it) }
        }
    }

    private class FakeAgent(val model: String) : AgentEngine {
        override fun chat(messages: List<AgentMessage>, tools: List<ToolDefinition>, system: String, maxTokens: Int): Flow<AgentEvent> = emptyFlow()
    }

    private val ready = ReasoningResolution.Ready(FakeText())
    private val models = CopyOnWriteArrayList<String>()

    private fun backends(
        hasTools: Boolean = true,
        apiKey: Boolean = true,
        resolution: ReasoningResolution = ready,
        changes: Flow<Unit> = emptyFlow(),
    ) = AgentBackends(
        hasTools = { hasTools },
        anthropicApiKeyConnected = { apiKey },
        anthropicCredentialChanges = changes,
        agentEngine = { model -> models += model; FakeAgent(model) },
        resolveText = { resolution },
    )

    private fun anthropic(model: String = "claude-sonnet-5-5") = Settings(reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = model)

    @Test
    fun `anthropic with an api key and a model - the agent with tools`() {
        val b = backends()
        assertThat(b.mode(anthropic())).isEqualTo(AgentMode.TOOLS)
        val backend = b.resolve(anthropic("claude-opus-5-5"))
        assertThat(backend).isInstanceOf(AgentBackend.WithTools::class.java)
        assertThat(models).containsExactly("claude-opus-5-5")
    }

    @Test
    fun `anthropic by login, not an api key - text only, with the note`() {
        val b = backends(apiKey = false)
        assertThat(b.mode(anthropic())).isEqualTo(AgentMode.TEXT_ONLY)
        assertThat(b.resolve(anthropic())).isInstanceOf(AgentBackend.TextOnly::class.java)
        assertThat(models).isEmpty()
    }

    @Test
    fun `every other origin is text only with the note, even with an anthropic key stored`() {
        val b = backends(apiKey = true)
        for (source in listOf(ReasoningSource.LOCAL, ReasoningSource.OPENAI, ReasoningSource.GOOGLE, ReasoningSource.CREDITS, ReasoningSource.CUSTOM)) {
            val settings = Settings(reasoningSource = source, reasoningAnthropicModel = "claude-sonnet-5-5")
            assertThat(b.mode(settings)).isEqualTo(AgentMode.TEXT_ONLY)
            assertThat(b.resolve(settings)).isInstanceOf(AgentBackend.TextOnly::class.java)
        }
        assertThat(models).isEmpty()
    }

    @Test
    fun `no model chosen on anthropic - text only`() {
        assertThat(backends().mode(anthropic(model = ""))).isEqualTo(AgentMode.TEXT_ONLY)
        assertThat(backends().mode(anthropic(model = "   "))).isEqualTo(AgentMode.TEXT_ONLY)
    }

    @Test
    fun `with no tool registered the chat is the plain one of today, even on anthropic by key - and no note`() {
        val b = backends(hasTools = false)
        assertThat(b.mode(anthropic())).isEqualTo(AgentMode.PLAIN)
        assertThat(b.resolve(anthropic())).isInstanceOf(AgentBackend.TextOnly::class.java)
        assertThat(models).isEmpty()
    }

    @Test
    fun `without a ready text engine the reason reaches the chat instead of failing silently`() {
        for (reason in UnavailableReason.entries) {
            val backend = backends(apiKey = false, resolution = ReasoningResolution.Unavailable(reason)).resolve(Settings(reasoningSource = ReasoningSource.OPENAI))
            assertThat(backend).isInstanceOf(AgentBackend.Unavailable::class.java)
            assertThat((backend as AgentBackend.Unavailable).message).isEqualTo(AgentBackends.unavailableMessage(reason))
            assertThat(backend.message).isNotEmpty()
        }
        assertThat(AgentBackends.unavailableMessage(UnavailableReason.NOT_CONNECTED)).isEqualTo("Credenciais do modelo não configuradas. Configure a chave na aba Provedores ou Raciocínio.")
    }

    @Test
    fun `the plain resolver never offers tools`() {
        val b = AgentBackends.plain { ready }
        assertThat(b.mode(anthropic())).isEqualTo(AgentMode.PLAIN)
        assertThat(b.resolve(anthropic())).isInstanceOf(AgentBackend.TextOnly::class.java)
    }

    @Test
    fun `the text only backend sends the old system prompt and the prompt alone, and maps the events`() = runBlocking {
        val engine = FakeText(events = listOf(ReasoningEvent.Delta("a"), ReasoningEvent.Delta("b"), ReasoningEvent.Done))
        val backend = AgentBackends.textOnly(engine)
        val got = withTimeout(5_000) { backend.generate("pergunta").toList() }
        assertThat(engine.requests.single()).isEqualTo(ReasoningRequest(system = "Você é o KeyFlow, um assistente de IA integrado ao sistema operacional Android.", user = "pergunta"))
        assertThat(got).containsExactly(AgentEvent.TextDelta("a"), AgentEvent.TextDelta("b"), AgentEvent.Done()).inOrder()
    }

    @Test
    fun `the text only backend maps an error with its kind and message`() = runBlocking<Unit> {
        val backend = AgentBackends.textOnly(FakeText(events = listOf(ReasoningEvent.Error(ReasoningError.RATE_LIMIT, "devagar"))))
        assertThat(withTimeout(5_000) { backend.generate("x").toList() }).containsExactly(AgentEvent.Error(ReasoningError.RATE_LIMIT, "devagar"))
    }

    @Test
    fun `the mode flow starts with the current mode and follows the credential`() = runBlocking {
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var apiKey = false
        val b = AgentBackends(
            hasTools = { true },
            anthropicApiKeyConnected = { apiKey },
            anthropicCredentialChanges = changes,
            agentEngine = { FakeAgent(it) },
            resolveText = { ready },
        )
        val seen = CopyOnWriteArrayList<AgentMode>()
        val job = launch { b.modeFlow(anthropic()).take(2).collect { seen += it } }
        kotlinx.coroutines.yield()
        apiKey = true
        changes.emit(Unit)
        withTimeout(5_000) { job.join() }
        assertThat(seen).containsExactly(AgentMode.TEXT_ONLY, AgentMode.TOOLS).inOrder()
    }
}
