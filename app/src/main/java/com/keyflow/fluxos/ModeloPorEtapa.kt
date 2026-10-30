package com.keyflow.fluxos

import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings

/** Os provedores de nuvem para onde um fluxo pode mandar dado (selo "Nuvem · <provedor>" — FLX-30). [nome] é o nome próprio. */
enum class ProvedorNuvem(val nome: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    GOOGLE("Google"),
    GROQ("Groq"),
    ELEVENLABS("ElevenLabs"),
    CUSTOM("Custom"),
}

/** Onde a etapa roda. [Indisponivel]: o modelo escolhido não existe (a etapa vai falhar com `INDISPONIVEL`). */
sealed interface Onde {
    data object NoAparelho : Onde

    data class Nuvem(val provedor: ProvedorNuvem) : Onde

    data object Indisponivel : Onde
}

/** O modelo que a etapa vai usar de verdade: a [origem] (nome do enum da aba), o [modelo] daquela origem e [onde] roda. */
data class Efetivo(val origem: String?, val modelo: String?, val onde: Onde)

/** O resultado de [ModeloPorEtapa.aplicar]: as Settings do instante da etapa, ou a origem que não existe naquela aba. */
sealed interface ModeloAplicado {
    data class Ok(val settings: Settings) : ModeloAplicado

    data class Invalido(val origem: String) : ModeloAplicado
}

/** O selo de privacidade do fluxo: 100% no aparelho, ou a lista dos provedores de nuvem (sem repetir, na ordem das etapas). */
data class SeloDoFluxo(val provedores: List<ProvedorNuvem>, val indisponivel: Boolean) {
    val noAparelho: Boolean get() = provedores.isEmpty() && !indisponivel
}

/**
 * O modelo de cada etapa sem copiar segredo (FLX-26, design §8). Puro.
 *
 * [aplicar] devolve uma **cópia em memória** das Settings do instante com **só** a origem e o modelo daquela etapa trocados — as
 * Settings gravadas nunca mudam, e nenhum campo de segredo (valor de cabeçalho, token, chave) é lido nem escrito: a cópia herda do
 * original, que já os tem; a credencial sai do cofre na hora da chamada. Origem Custom troca só a origem (endpoint e cabeçalho são
 * os da aba). O fluxo nunca troca de origem por conta própria: `CREDITS`, um nome que não existe ou que é de outra aba
 * ([ModeloAplicado.Invalido]) faz a porta falhar com `INDISPONIVEL` — sem cair em outra origem.
 */
object ModeloPorEtapa {

    fun aplicar(settings: Settings, etapa: Etapa): ModeloAplicado {
        val ref = etapa.modelo ?: return ModeloAplicado.Ok(settings)
        val modelo = ref.modelo?.takeIf { it.isNotBlank() }
        val invalido = ModeloAplicado.Invalido(ref.origem)
        return when (etapa) {
            is Etapa.Ditar -> {
                val origem = origemDe<TranscriptionSource>(ref.origem, TranscriptionSource.CREDITS) ?: return invalido
                val s = settings.copy(source = origem)
                ModeloAplicado.Ok(
                    when (origem) {
                        TranscriptionSource.LOCAL -> if (modelo != null) s.copy(localModelId = modelo) else s
                        TranscriptionSource.OPENAI -> if (modelo != null) s.copy(openaiModel = modelo) else s
                        TranscriptionSource.GROQ -> if (modelo != null) s.copy(groqModel = modelo) else s
                        TranscriptionSource.CUSTOM, TranscriptionSource.CREDITS -> s
                    },
                )
            }
            is Etapa.Visao -> {
                val origem = origemDe<VisionSource>(ref.origem, VisionSource.CREDITS) ?: return invalido
                val s = settings.copy(visionSource = origem)
                ModeloAplicado.Ok(
                    when (origem) {
                        VisionSource.LOCAL -> if (modelo != null) s.copy(visionLocalModelId = modelo) else s
                        VisionSource.OPENAI -> if (modelo != null) s.copy(visionOpenAiModel = modelo) else s
                        VisionSource.ANTHROPIC -> if (modelo != null) s.copy(visionAnthropicModel = modelo) else s
                        VisionSource.GOOGLE -> if (modelo != null) s.copy(visionGoogleModel = modelo) else s
                        VisionSource.CUSTOM, VisionSource.CREDITS -> s
                    },
                )
            }
            is Etapa.Perguntar, is Etapa.Agente -> {
                val origem = origemDe<ReasoningSource>(ref.origem, ReasoningSource.CREDITS) ?: return invalido
                val s = settings.copy(reasoningSource = origem)
                ModeloAplicado.Ok(
                    when (origem) {
                        ReasoningSource.LOCAL -> if (modelo != null) s.copy(reasoningLocalModelId = modelo) else s
                        ReasoningSource.OPENAI -> if (modelo != null) s.copy(reasoningOpenAiModel = modelo) else s
                        ReasoningSource.ANTHROPIC -> if (modelo != null) s.copy(reasoningAnthropicModel = modelo) else s
                        ReasoningSource.GOOGLE -> if (modelo != null) s.copy(reasoningGoogleModel = modelo) else s
                        ReasoningSource.CUSTOM, ReasoningSource.CREDITS -> s
                    },
                )
            }
            is Etapa.Falar -> {
                val origem = origemDe<FalaSource>(ref.origem, FalaSource.CREDITS) ?: return invalido
                val s = settings.copy(falaSource = origem)
                ModeloAplicado.Ok(
                    when (origem) {
                        FalaSource.LOCAL -> if (modelo != null) s.copy(falaLocalModelId = modelo) else s
                        FalaSource.OPENAI -> if (modelo != null) s.copy(falaCloudVoice = modelo) else s
                        FalaSource.ELEVENLABS -> if (modelo != null) s.copy(falaElevenLabsVoice = modelo) else s
                        FalaSource.GOOGLE -> if (modelo != null) s.copy(falaGoogleVoice = modelo) else s
                        FalaSource.CUSTOM -> if (modelo != null) s.copy(falaCustomVoice = modelo) else s
                        FalaSource.SYSTEM, FalaSource.CREDITS -> s
                    },
                )
            }
            Etapa.Capturar, Etapa.Inserir, is Etapa.Desconhecida -> ModeloAplicado.Ok(settings)
        }
    }

    /**
     * O modelo que a [etapa] usa de verdade com estas Settings: o do `ModeloRef`, ou o que a aba tem agora, e onde isso roda.
     * Capturar e Inserir rodam no aparelho e não têm origem.
     */
    fun efetivo(settings: Settings, etapa: Etapa): Efetivo {
        if (!etapa.usaModelo) return Efetivo(null, null, Onde.NoAparelho)
        val aplicado = aplicar(settings, etapa)
        if (aplicado is ModeloAplicado.Invalido) return Efetivo(aplicado.origem, etapa.modelo?.modelo, Onde.Indisponivel)
        val s = (aplicado as ModeloAplicado.Ok).settings
        return when (etapa) {
            is Etapa.Ditar -> when (s.source) {
                TranscriptionSource.LOCAL -> Efetivo("LOCAL", s.localModelId, Onde.NoAparelho)
                TranscriptionSource.OPENAI -> nuvem("OPENAI", s.openaiModel, ProvedorNuvem.OPENAI)
                TranscriptionSource.GROQ -> nuvem("GROQ", s.groqModel, ProvedorNuvem.GROQ)
                TranscriptionSource.CUSTOM -> nuvem("CUSTOM", s.customModel, ProvedorNuvem.CUSTOM)
                TranscriptionSource.CREDITS -> Efetivo("CREDITS", null, Onde.Indisponivel)
            }
            is Etapa.Visao -> when (s.visionSource) {
                VisionSource.LOCAL -> Efetivo("LOCAL", s.visionLocalModelId, Onde.NoAparelho)
                VisionSource.OPENAI -> nuvem("OPENAI", s.visionOpenAiModel, ProvedorNuvem.OPENAI)
                VisionSource.ANTHROPIC -> nuvem("ANTHROPIC", s.visionAnthropicModel, ProvedorNuvem.ANTHROPIC)
                VisionSource.GOOGLE -> nuvem("GOOGLE", s.visionGoogleModel, ProvedorNuvem.GOOGLE)
                VisionSource.CUSTOM -> nuvem("CUSTOM", s.visionCustomModel, ProvedorNuvem.CUSTOM)
                VisionSource.CREDITS -> Efetivo("CREDITS", null, Onde.Indisponivel)
            }
            is Etapa.Perguntar, is Etapa.Agente -> when (s.reasoningSource) {
                ReasoningSource.LOCAL -> Efetivo("LOCAL", s.reasoningLocalModelId, Onde.NoAparelho)
                ReasoningSource.OPENAI -> nuvem("OPENAI", s.reasoningOpenAiModel, ProvedorNuvem.OPENAI)
                ReasoningSource.ANTHROPIC -> nuvem("ANTHROPIC", s.reasoningAnthropicModel, ProvedorNuvem.ANTHROPIC)
                ReasoningSource.GOOGLE -> nuvem("GOOGLE", s.reasoningGoogleModel, ProvedorNuvem.GOOGLE)
                ReasoningSource.CUSTOM -> nuvem("CUSTOM", s.reasoningCustomModel, ProvedorNuvem.CUSTOM)
                ReasoningSource.CREDITS -> Efetivo("CREDITS", null, Onde.Indisponivel)
            }
            is Etapa.Falar -> when (s.falaSource) {
                FalaSource.LOCAL -> Efetivo("LOCAL", s.falaLocalModelId, Onde.NoAparelho)
                FalaSource.SYSTEM -> Efetivo("SYSTEM", null, Onde.NoAparelho)
                FalaSource.OPENAI -> nuvem("OPENAI", s.falaCloudVoice, ProvedorNuvem.OPENAI)
                FalaSource.ELEVENLABS -> nuvem("ELEVENLABS", s.falaElevenLabsVoice, ProvedorNuvem.ELEVENLABS)
                FalaSource.GOOGLE -> nuvem("GOOGLE", s.falaGoogleVoice, ProvedorNuvem.GOOGLE)
                FalaSource.CUSTOM -> nuvem("CUSTOM", s.falaCustomVoice, ProvedorNuvem.CUSTOM)
                FalaSource.CREDITS -> Efetivo("CREDITS", null, Onde.Indisponivel)
            }
            Etapa.Capturar, Etapa.Inserir, is Etapa.Desconhecida -> Efetivo(null, null, Onde.NoAparelho)
        }
    }

    /** O selo do fluxo, com o modelo efetivo de cada etapa (o da etapa ou o da aba agora). */
    fun seloDoFluxo(settings: Settings, fluxo: Fluxo): SeloDoFluxo {
        val ondes = fluxo.etapas.map { efetivo(settings, it).onde }
        return SeloDoFluxo(
            provedores = ondes.filterIsInstance<Onde.Nuvem>().map { it.provedor }.distinct(),
            indisponivel = ondes.any { it == Onde.Indisponivel },
        )
    }

    private fun nuvem(origem: String, modelo: String, provedor: ProvedorNuvem) =
        Efetivo(origem, modelo.takeIf { it.isNotBlank() }, Onde.Nuvem(provedor))

    /** O valor do enum de nome [nome] que não seja o legado [creditos]; nulo se não existe (inclusive minúscula). */
    private inline fun <reified E : Enum<E>> origemDe(nome: String, creditos: E): E? =
        enumValues<E>().firstOrNull { it.name == nome && it != creditos }
}
