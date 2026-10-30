package com.keyflow.fluxos

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.SettingsSecrets
import org.junit.Test

/** O modelo de cada etapa sem copiar segredo, e o selo de privacidade (FLX-26, FLX-30, FLX-46). */
class ModeloPorEtapaTest {

    private val segredos = Settings(
        customHeaderValue = "SEGREDO-1",
        reasoningCustomHeaderValue = "SEGREDO-2",
        visionCustomHeaderValue = "SEGREDO-3",
        falaCustomHeaderValue = "SEGREDO-4",
        computerSshPassword = "SEGREDO-5",
        computerSshKey = "SEGREDO-6",
    )

    /** As Settings da aba: tudo local e com um modelo escolhido em cada origem. */
    private val base = segredos.copy(
        source = TranscriptionSource.LOCAL, localModelId = "parakeet-v3", openaiModel = "whisper-1", groqModel = "whisper-large-v3",
        reasoningSource = ReasoningSource.LOCAL, reasoningLocalModelId = "gemma-4-e2b",
        reasoningOpenAiModel = "gpt-4o", reasoningAnthropicModel = "claude-x", reasoningGoogleModel = "gemini-x",
        visionSource = VisionSource.LOCAL, visionLocalModelId = "paddleocr",
        visionOpenAiModel = "gpt-4o-vision", visionAnthropicModel = "claude-vision", visionGoogleModel = "gemini-vision",
        falaSource = FalaSource.LOCAL, falaLocalModelId = "vits-pt", falaCloudVoice = "alloy",
        falaElevenLabsVoice = "voz-el", falaGoogleVoice = "pt-BR-Neural2-A", falaCustomVoice = "voz-custom",
    )

    private fun ok(etapa: Etapa, settings: Settings = base): Settings {
        val r = ModeloPorEtapa.aplicar(settings, etapa)
        assertThat(r).isInstanceOf(ModeloAplicado.Ok::class.java)
        return (r as ModeloAplicado.Ok).settings
    }

    // ── aplicar ─────────────────────────────────────────────────────────────

    @Test
    fun `sem ModeloRef devolve o mesmo Settings`() {
        for (etapa in listOf(Etapa.Ditar(), Etapa.Visao(), Etapa.Perguntar(), Etapa.Falar(), Etapa.Agente(), Etapa.Capturar, Etapa.Inserir)) {
            assertThat(ok(etapa)).isSameInstanceAs(base)
        }
    }

    @Test
    fun `etapas que nao usam modelo devolvem o mesmo Settings mesmo assim`() {
        assertThat(ok(Etapa.Capturar)).isSameInstanceAs(base)
        assertThat(ok(Etapa.Inserir)).isSameInstanceAs(base)
        assertThat(ok(Etapa.Desconhecida("x"))).isSameInstanceAs(base)
    }

    @Test
    fun `Ditar troca so a origem e o modelo daquela origem`() {
        assertThat(ok(Etapa.Ditar(ModeloRef("LOCAL", "outro-local")), base.copy(source = TranscriptionSource.OPENAI)))
            .isEqualTo(base.copy(source = TranscriptionSource.LOCAL, localModelId = "outro-local"))
        assertThat(ok(Etapa.Ditar(ModeloRef("OPENAI", "whisper-x")))).isEqualTo(base.copy(source = TranscriptionSource.OPENAI, openaiModel = "whisper-x"))
        assertThat(ok(Etapa.Ditar(ModeloRef("GROQ", "whisper-g")))).isEqualTo(base.copy(source = TranscriptionSource.GROQ, groqModel = "whisper-g"))
    }

    @Test
    fun `modelo nulo no ModeloRef troca so a origem e fica o modelo que a aba tem para ela`() {
        assertThat(ok(Etapa.Ditar(ModeloRef("OPENAI")))).isEqualTo(base.copy(source = TranscriptionSource.OPENAI))
        assertThat(ok(Etapa.Perguntar(modelo = ModeloRef("ANTHROPIC")))).isEqualTo(base.copy(reasoningSource = ReasoningSource.ANTHROPIC))
    }

    @Test
    fun `Custom troca so a origem, mesmo com modelo no ModeloRef`() {
        assertThat(ok(Etapa.Ditar(ModeloRef("CUSTOM", "ignorado")))).isEqualTo(base.copy(source = TranscriptionSource.CUSTOM))
        assertThat(ok(Etapa.Visao(modelo = ModeloRef("CUSTOM", "ignorado")))).isEqualTo(base.copy(visionSource = VisionSource.CUSTOM))
        assertThat(ok(Etapa.Perguntar(modelo = ModeloRef("CUSTOM", "ignorado")))).isEqualTo(base.copy(reasoningSource = ReasoningSource.CUSTOM))
        assertThat(ok(Etapa.Agente(ModeloRef("CUSTOM", "ignorado")))).isEqualTo(base.copy(reasoningSource = ReasoningSource.CUSTOM))
    }

    @Test
    fun `Visao troca a origem e o modelo de visao`() {
        assertThat(ok(Etapa.Visao(modelo = ModeloRef("LOCAL", "gemma-vision")))).isEqualTo(base.copy(visionLocalModelId = "gemma-vision"))
        assertThat(ok(Etapa.Visao(modelo = ModeloRef("OPENAI", "gpt-5")))).isEqualTo(base.copy(visionSource = VisionSource.OPENAI, visionOpenAiModel = "gpt-5"))
        assertThat(ok(Etapa.Visao(modelo = ModeloRef("ANTHROPIC", "c")))).isEqualTo(base.copy(visionSource = VisionSource.ANTHROPIC, visionAnthropicModel = "c"))
        assertThat(ok(Etapa.Visao(modelo = ModeloRef("GOOGLE", "g")))).isEqualTo(base.copy(visionSource = VisionSource.GOOGLE, visionGoogleModel = "g"))
    }

    @Test
    fun `Perguntar e Agente trocam a origem e o modelo de raciocinio`() {
        for (etapa in listOf<(ModeloRef) -> Etapa>({ Etapa.Perguntar(modelo = it) }, { Etapa.Agente(it) })) {
            assertThat(ok(etapa(ModeloRef("LOCAL", "qwen")))).isEqualTo(base.copy(reasoningLocalModelId = "qwen"))
            assertThat(ok(etapa(ModeloRef("OPENAI", "gpt-5")))).isEqualTo(base.copy(reasoningSource = ReasoningSource.OPENAI, reasoningOpenAiModel = "gpt-5"))
            assertThat(ok(etapa(ModeloRef("ANTHROPIC", "c")))).isEqualTo(base.copy(reasoningSource = ReasoningSource.ANTHROPIC, reasoningAnthropicModel = "c"))
            assertThat(ok(etapa(ModeloRef("GOOGLE", "g")))).isEqualTo(base.copy(reasoningSource = ReasoningSource.GOOGLE, reasoningGoogleModel = "g"))
        }
    }

    @Test
    fun `Falar troca a origem e a voz ou o modelo local`() {
        assertThat(ok(Etapa.Falar(ModeloRef("LOCAL", "supertonic")))).isEqualTo(base.copy(falaLocalModelId = "supertonic"))
        assertThat(ok(Etapa.Falar(ModeloRef("SYSTEM", "ignorado")))).isEqualTo(base.copy(falaSource = FalaSource.SYSTEM))
        assertThat(ok(Etapa.Falar(ModeloRef("OPENAI", "nova")))).isEqualTo(base.copy(falaSource = FalaSource.OPENAI, falaCloudVoice = "nova"))
        assertThat(ok(Etapa.Falar(ModeloRef("ELEVENLABS", "v2")))).isEqualTo(base.copy(falaSource = FalaSource.ELEVENLABS, falaElevenLabsVoice = "v2"))
        assertThat(ok(Etapa.Falar(ModeloRef("GOOGLE", "pt-BR-Wavenet-B")))).isEqualTo(base.copy(falaSource = FalaSource.GOOGLE, falaGoogleVoice = "pt-BR-Wavenet-B"))
        assertThat(ok(Etapa.Falar(ModeloRef("CUSTOM", "minha")))).isEqualTo(base.copy(falaSource = FalaSource.CUSTOM, falaCustomVoice = "minha"))
    }

    @Test
    fun `CREDITS, nome inexistente e origem de outra aba sao invalidos`() {
        val invalidos = listOf(
            Etapa.Ditar(ModeloRef("CREDITS")), Etapa.Visao(modelo = ModeloRef("CREDITS")), Etapa.Perguntar(modelo = ModeloRef("CREDITS")),
            Etapa.Falar(ModeloRef("CREDITS")), Etapa.Agente(ModeloRef("CREDITS")),
            Etapa.Ditar(ModeloRef("MARTE")), Etapa.Perguntar(modelo = ModeloRef("")), Etapa.Falar(ModeloRef("openai")), // minúscula não é o nome do enum
            Etapa.Ditar(ModeloRef("ANTHROPIC")), // Anthropic não transcreve
            Etapa.Falar(ModeloRef("GROQ")), Etapa.Visao(modelo = ModeloRef("GROQ")), Etapa.Perguntar(modelo = ModeloRef("SYSTEM")),
        )
        for (etapa in invalidos) {
            assertThat(ModeloPorEtapa.aplicar(base, etapa)).isEqualTo(ModeloAplicado.Invalido(etapa.modelo!!.origem))
        }
    }

    @Test
    fun `o Settings original fica intacto`() {
        val antes = base.copy()
        ok(Etapa.Ditar(ModeloRef("OPENAI", "whisper-x")))
        ok(Etapa.Falar(ModeloRef("ELEVENLABS", "v2")))
        assertThat(base).isEqualTo(antes)
    }

    @Test
    fun `nenhum campo de segredo e lido ou escrito`() {
        val etapas = listOf(
            Etapa.Ditar(ModeloRef("CUSTOM")), Etapa.Visao(modelo = ModeloRef("CUSTOM")), Etapa.Perguntar(modelo = ModeloRef("CUSTOM")),
            Etapa.Falar(ModeloRef("CUSTOM")), Etapa.Agente(ModeloRef("OPENAI", "x")), Etapa.Falar(ModeloRef("ELEVENLABS", "y")),
        )
        for (etapa in etapas) {
            val depois = ok(etapa)
            for (campo in SettingsSecrets.BY_PROPERTY.keys) {
                val leitor = Settings::class.java.getDeclaredMethod("get" + campo.replaceFirstChar { it.uppercase() })
                assertThat(leitor.invoke(depois)).isEqualTo(leitor.invoke(base))
            }
            assertThat(depois.customEndpoint).isEqualTo(base.customEndpoint)
            assertThat(depois.reasoningCustomEndpoint).isEqualTo(base.reasoningCustomEndpoint)
        }
    }

    // ── efetivo ─────────────────────────────────────────────────────────────

    @Test
    fun `sem ModeloRef o efetivo e o que a aba tem agora`() {
        val nuvem = base.copy(
            source = TranscriptionSource.GROQ, reasoningSource = ReasoningSource.OPENAI, visionSource = VisionSource.ANTHROPIC,
            falaSource = FalaSource.ELEVENLABS,
        )
        assertThat(ModeloPorEtapa.efetivo(nuvem, Etapa.Ditar())).isEqualTo(Efetivo("GROQ", "whisper-large-v3", Onde.Nuvem(ProvedorNuvem.GROQ)))
        assertThat(ModeloPorEtapa.efetivo(nuvem, Etapa.Perguntar())).isEqualTo(Efetivo("OPENAI", "gpt-4o", Onde.Nuvem(ProvedorNuvem.OPENAI)))
        assertThat(ModeloPorEtapa.efetivo(nuvem, Etapa.Agente())).isEqualTo(Efetivo("OPENAI", "gpt-4o", Onde.Nuvem(ProvedorNuvem.OPENAI)))
        assertThat(ModeloPorEtapa.efetivo(nuvem, Etapa.Visao())).isEqualTo(Efetivo("ANTHROPIC", "claude-vision", Onde.Nuvem(ProvedorNuvem.ANTHROPIC)))
        assertThat(ModeloPorEtapa.efetivo(nuvem, Etapa.Falar())).isEqualTo(Efetivo("ELEVENLABS", "voz-el", Onde.Nuvem(ProvedorNuvem.ELEVENLABS)))
    }

    @Test
    fun `origem local ou do sistema roda no aparelho`() {
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Ditar())).isEqualTo(Efetivo("LOCAL", "parakeet-v3", Onde.NoAparelho))
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Perguntar())).isEqualTo(Efetivo("LOCAL", "gemma-4-e2b", Onde.NoAparelho))
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Visao())).isEqualTo(Efetivo("LOCAL", "paddleocr", Onde.NoAparelho))
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Falar())).isEqualTo(Efetivo("LOCAL", "vits-pt", Onde.NoAparelho))
        assertThat(ModeloPorEtapa.efetivo(base.copy(falaSource = FalaSource.SYSTEM), Etapa.Falar()).onde).isEqualTo(Onde.NoAparelho)
    }

    @Test
    fun `Capturar e Inserir rodam no aparelho e nao tem origem`() {
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Capturar)).isEqualTo(Efetivo(null, null, Onde.NoAparelho))
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Inserir)).isEqualTo(Efetivo(null, null, Onde.NoAparelho))
    }

    @Test
    fun `o efetivo com ModeloRef e o da etapa, nao o da aba`() {
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Perguntar(modelo = ModeloRef("OPENAI", "gpt-5"))))
            .isEqualTo(Efetivo("OPENAI", "gpt-5", Onde.Nuvem(ProvedorNuvem.OPENAI)))
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Perguntar(modelo = ModeloRef("GOOGLE"))))
            .isEqualTo(Efetivo("GOOGLE", "gemini-x", Onde.Nuvem(ProvedorNuvem.GOOGLE)))
        assertThat(ModeloPorEtapa.efetivo(base.copy(reasoningSource = ReasoningSource.OPENAI), Etapa.Perguntar(modelo = ModeloRef("LOCAL", "qwen"))))
            .isEqualTo(Efetivo("LOCAL", "qwen", Onde.NoAparelho))
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Falar(ModeloRef("CUSTOM"))).onde).isEqualTo(Onde.Nuvem(ProvedorNuvem.CUSTOM))
    }

    @Test
    fun `todo provedor de nuvem tem o nome certo no selo`() {
        assertThat(ProvedorNuvem.entries.map { it.nome })
            .containsExactly("OpenAI", "Anthropic", "Google", "Groq", "ElevenLabs", "Custom").inOrder()
    }

    @Test
    fun `ModeloRef invalido e origem CREDITS da aba ficam indisponiveis`() {
        assertThat(ModeloPorEtapa.efetivo(base, Etapa.Ditar(ModeloRef("MARTE", "m"))))
            .isEqualTo(Efetivo("MARTE", "m", Onde.Indisponivel))
        assertThat(ModeloPorEtapa.efetivo(base.copy(reasoningSource = ReasoningSource.CREDITS), Etapa.Perguntar()).onde).isEqualTo(Onde.Indisponivel)
        assertThat(ModeloPorEtapa.efetivo(base.copy(source = TranscriptionSource.CREDITS), Etapa.Ditar()).onde).isEqualTo(Onde.Indisponivel)
    }

    // ── selo do fluxo ───────────────────────────────────────────────────────

    private fun fluxo(vararg etapas: Etapa) = Fluxo("f", "F", etapas = etapas.toList())

    @Test
    fun `tudo local e 100 por cento no aparelho`() {
        val selo = ModeloPorEtapa.seloDoFluxo(base, fluxo(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Falar()))
        assertThat(selo.noAparelho).isTrue()
        assertThat(selo.provedores).isEmpty()
        assertThat(ModeloPorEtapa.seloDoFluxo(base, fluxo(Etapa.Capturar, Etapa.Visao(), Etapa.Inserir)).noAparelho).isTrue()
    }

    @Test
    fun `o selo lista os provedores de nuvem sem repetir, na ordem das etapas`() {
        val f = fluxo(
            Etapa.Ditar(ModeloRef("OPENAI")), Etapa.Perguntar(modelo = ModeloRef("OPENAI", "gpt-5")),
            Etapa.Agente(ModeloRef("ANTHROPIC")), Etapa.Falar(ModeloRef("ELEVENLABS")), Etapa.Inserir,
        )
        val selo = ModeloPorEtapa.seloDoFluxo(base, f)
        assertThat(selo.noAparelho).isFalse()
        assertThat(selo.provedores).containsExactly(ProvedorNuvem.OPENAI, ProvedorNuvem.ANTHROPIC, ProvedorNuvem.ELEVENLABS).inOrder()
    }

    @Test
    fun `o selo acompanha as Settings da aba quando a etapa nao escolheu modelo`() {
        val f = fluxo(Etapa.Ditar(), Etapa.Perguntar(), Etapa.Falar())
        val selo = ModeloPorEtapa.seloDoFluxo(base.copy(reasoningSource = ReasoningSource.GOOGLE, falaSource = FalaSource.CUSTOM), f)
        assertThat(selo.provedores).containsExactly(ProvedorNuvem.GOOGLE, ProvedorNuvem.CUSTOM).inOrder()
    }

    @Test
    fun `um modelo indisponivel tira o selo de 100 por cento no aparelho`() {
        val selo = ModeloPorEtapa.seloDoFluxo(base, fluxo(Etapa.Ditar(ModeloRef("CREDITS")), Etapa.Falar()))
        assertThat(selo.indisponivel).isTrue()
        assertThat(selo.noAparelho).isFalse()
    }
}
