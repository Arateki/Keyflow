package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.bancada.corpus.CorpusLoader
import com.keyflow.bancada.corpus.DiretorioFonte
import com.keyflow.bancada.executor.ModeloEscolhido
import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

/** BNC-01/03/05: a faixa de sub-abas e os motivos de uma estar desabilitada, na JVM. */
class BancadaSubAbasTest {

    private val corpus = CorpusLoader.carregar(DiretorioFonte(File("src/main/assets/bancada/v1")))
    private val pronto = ReasoningResolution.Ready(
        object : ReasoningEngine {
            override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> = emptyFlow()
        },
    )
    private val local = ModeloEscolhido(origem = "local", modelo = "qwen3-0.6b-litertlm", nome = "Qwen3 0.6B", backend = "CPU")

    @Test
    fun `the strip has Quick first, then the five tests of the corpus in order`() {
        val abas = bancadaSubAbas(corpus, pronto, local)
        assertThat(abas.map { it.id }).containsExactly("rapido", "pontuacao", "reescrita", "json", "acoes", "contexto").inOrder()
        assertThat(abas.all { it.habilitada }).isTrue()
        assertThat(abas.first().rotulo).isEqualTo("Rápido")
    }

    @Test
    fun `without a ready engine every test tab carries the same reason as today and Quick stays enabled`() {
        for (razao in UnavailableReason.entries) {
            val abas = bancadaSubAbas(corpus, ReasoningResolution.Unavailable(razao), null)
            assertThat(abas.first().habilitada).isTrue()
            val esperado = ReasoningTestMessages.unavailable(razao)
            assertThat(abas.drop(1).map { it.motivo }.toSet()).containsExactly(esperado)
        }
    }

    @Test
    fun `a window smaller than every case disables the test with the reason, a partial fit does not`() {
        val functionGemma = local.copy(modelo = "functiongemma-270m-mobile-actions-litertlm", contextTokens = 1_024)
        val abas = bancadaSubAbas(corpus, pronto, functionGemma).associateBy { it.id }
        assertThat(abas.getValue("acoes").habilitada).isFalse()
        assertThat(abas.getValue("acoes").motivo).contains("1024")
        assertThat(abas.getValue("pontuacao").habilitada).isTrue()
        assertThat(abas.getValue("contexto").habilitada).isTrue() // os documentos curtos cabem; os longos viram "não se aplica" caso a caso
        assertThat(bancadaSubAbas(corpus, pronto, local.copy(contextTokens = 4_096)).all { it.habilitada }).isTrue()
    }

    @Test
    fun `no corpus leaves only Quick`() {
        assertThat(bancadaSubAbas(null, pronto, local).map { it.id }).containsExactly("rapido")
    }

    // ── o modelo escolhido ───────────────────────────────────────────────────────────────

    @Test
    fun `the local model is the installed and selected text model with its backend, hash and window`() {
        val spec = ModelCatalog.QWEN2_5_1_5B
        val models = LocalModelsUi.compose(catalog = ModelCatalog.TEXT_ALL, installed = setOf(spec.id), active = null, failures = emptyMap(), selectedId = spec.id, deviceRamMb = 8_000)
        val modelo = modeloEscolhido(Settings(reasoningSource = ReasoningSource.LOCAL, reasoningLocalModelId = spec.id), models, porAssinatura = false)!!
        assertThat(modelo.id).isEqualTo(spec.id)
        assertThat(modelo.online).isFalse()
        assertThat(modelo.backend).isEqualTo("CPU")
        assertThat(modelo.sha256).isEqualTo(spec.file(FileRole.MODEL)!!.sha256)
        assertThat(modelo.contextTokens).isEqualTo(4_096)
    }

    @Test
    fun `no installed selection means no local model`() {
        val models = LocalModelsUi.compose(catalog = ModelCatalog.TEXT_ALL, installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 8_000)
        assertThat(modeloEscolhido(Settings(reasoningSource = ReasoningSource.LOCAL), models, false)).isNull()
        assertThat(modeloEscolhido(Settings(reasoningSource = ReasoningSource.CREDITS), models, false)).isNull()
    }

    @Test
    fun `a cloud model is the one written in the provider settings, under the provider`() {
        val vazio = LocalModelsUi(emptyList())
        val openAi = modeloEscolhido(Settings(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = " gpt-4.1 "), vazio, porAssinatura = true)!!
        assertThat(openAi.id).isEqualTo("openai/gpt-4.1")
        assertThat(openAi.online).isTrue()
        assertThat(openAi.porAssinatura).isTrue()
        assertThat(openAi.backend).isEqualTo("nuvem")
        assertThat(modeloEscolhido(Settings(reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = "claude-x"), vazio, false)!!.id).isEqualTo("anthropic/claude-x")
        assertThat(modeloEscolhido(Settings(reasoningSource = ReasoningSource.GOOGLE, reasoningGoogleModel = "gemini-x"), vazio, false)!!.id).isEqualTo("google/gemini-x")
        assertThat(modeloEscolhido(Settings(reasoningSource = ReasoningSource.CUSTOM, reasoningCustomModel = "meu"), vazio, false)!!.id).isEqualTo("custom/meu")
        assertThat(modeloEscolhido(Settings(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = "  "), vazio, false)).isNull()
    }
}
