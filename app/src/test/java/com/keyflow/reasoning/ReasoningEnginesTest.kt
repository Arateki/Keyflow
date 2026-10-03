package com.keyflow.reasoning

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test

/** RRUN-01/RTS-02: qual motor responde, e — quando nenhum — por que não dá para testar. */
class ReasoningEnginesTest {

    private class FakeEngine(val label: String) : ReasoningEngine {
        override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = flowOf(ReasoningEvent.Done)
    }

    private fun engines(
        installed: Set<String> = emptySet(),
        connected: Boolean = false,
        anthropicConnected: Boolean = false,
        googleConnected: Boolean = false,
    ) = ReasoningEngines(
        isModelInstalled = { it in installed },
        hasOpenAiCredential = { connected },
        hasAnthropicCredential = { anthropicConnected },
        hasGoogleCredential = { googleConnected },
        local = { FakeEngine("local:$it") },
        openAi = { FakeEngine("openai:$it") },
        anthropic = { FakeEngine("anthropic:$it") },
        google = { FakeEngine("google:$it") },
    )

    private fun ready(resolution: ReasoningResolution): FakeEngine =
        ((resolution as ReasoningResolution.Ready).engine as FakeEngine)

    @Test
    fun `local without a chosen model is unavailable`() {
        val resolution = engines().resolve(Settings(reasoningSource = ReasoningSource.LOCAL))
        assertThat(resolution).isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL))
    }

    @Test
    fun `local with a model that is not installed is unavailable`() {
        val settings = Settings(reasoningSource = ReasoningSource.LOCAL, reasoningLocalModelId = "qwen3-0.6b-litertlm")
        assertThat(engines(installed = emptySet()).resolve(settings))
            .isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.MODEL_NOT_INSTALLED))
    }

    @Test
    fun `local with an installed model is ready and uses the chosen model id`() {
        val settings = Settings(reasoningSource = ReasoningSource.LOCAL, reasoningLocalModelId = "qwen3-0.6b-litertlm")
        val engine = ready(engines(installed = setOf("qwen3-0.6b-litertlm")).resolve(settings))
        assertThat(engine.label).isEqualTo("local:qwen3-0.6b-litertlm")
    }

    @Test
    fun `openai without a credential is unavailable`() {
        val settings = Settings(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = "algum-modelo")
        assertThat(engines(connected = false).resolve(settings))
            .isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED))
    }

    @Test
    fun `openai connected but with no model chosen is unavailable`() {
        val settings = Settings(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = "")
        assertThat(engines(connected = true).resolve(settings))
            .isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.NO_MODEL_CHOSEN))
    }

    @Test
    fun `openai ready passes the chosen model`() {
        val settings = Settings(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = "algum-modelo")
        assertThat(ready(engines(connected = true).resolve(settings)).label).isEqualTo("openai:algum-modelo")
    }

    @Test
    fun `anthropic without a credential is unavailable`() {
        val settings = Settings(reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = "claude-3-5-sonnet")
        assertThat(engines(anthropicConnected = false).resolve(settings))
            .isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED))
    }

    @Test
    fun `anthropic ready passes the chosen model`() {
        val settings = Settings(reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = "claude-3-5-sonnet")
        assertThat(ready(engines(anthropicConnected = true).resolve(settings)).label).isEqualTo("anthropic:claude-3-5-sonnet")
    }

    @Test
    fun `google without a credential is unavailable`() {
        val settings = Settings(reasoningSource = ReasoningSource.GOOGLE, reasoningGoogleModel = "gemini-2.0-flash")
        assertThat(engines(googleConnected = false).resolve(settings))
            .isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED))
    }

    @Test
    fun `google connected but with no model chosen is unavailable`() {
        val settings = Settings(reasoningSource = ReasoningSource.GOOGLE, reasoningGoogleModel = "")
        assertThat(engines(googleConnected = true).resolve(settings))
            .isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.NO_MODEL_CHOSEN))
    }

    @Test
    fun `google ready passes the chosen model`() {
        val settings = Settings(reasoningSource = ReasoningSource.GOOGLE, reasoningGoogleModel = "gemini-2.0-flash")
        assertThat(ready(engines(googleConnected = true).resolve(settings)).label).isEqualTo("google:gemini-2.0-flash")
    }

    /** Uma origem nunca "vaza" para a outra: credencial da OpenAI não faz o local ficar pronto e vice-versa. */
    @Test
    fun `the chosen source decides, not what happens to be available`() {
        val local = Settings(reasoningSource = ReasoningSource.LOCAL, reasoningLocalModelId = null)
        assertThat(engines(connected = true).resolve(local))
            .isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL))
        val openai = Settings(reasoningSource = ReasoningSource.OPENAI, reasoningLocalModelId = "qwen3-0.6b-litertlm")
        assertThat(engines(installed = setOf("qwen3-0.6b-litertlm")).resolve(openai))
            .isEqualTo(ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED))
    }

    @Test
    fun `settings defaults to the local source with nothing chosen`() {
        val settings = Settings()
        assertThat(settings.reasoningSource).isEqualTo(ReasoningSource.LOCAL)
        assertThat(settings.reasoningLocalModelId).isNull()
        assertThat(settings.reasoningOpenAiModel).isEmpty()
        assertThat(settings.reasoningGoogleModel).isEqualTo("gemini-2.0-flash")
    }
}
