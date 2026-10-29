package com.keyflow.reasoning

import com.keyflow.core.ReasoningSource
import com.keyflow.data.Settings

/** Por que não há motor pronto na origem escolhida — a tela traduz em "o que falta fazer" (RTS-02). */
enum class UnavailableReason { NO_LOCAL_MODEL, MODEL_NOT_INSTALLED, NOT_CONNECTED, NO_MODEL_CHOSEN }

/** O resultado de [ReasoningEngines.resolve]: um motor pronto, ou o motivo de não haver. */
sealed interface ReasoningResolution {
    data class Ready(val engine: ReasoningEngine) : ReasoningResolution
    data class Unavailable(val reason: UnavailableReason) : ReasoningResolution
}

/**
 * Escolhe o motor da origem ativa. Só a origem escolhida decide: credencial da OpenAI não deixa o Local "pronto" e
 * um modelo instalado não deixa a OpenAI "pronta". Tudo que é do ambiente entra por lambda — o resolvedor não conhece
 * `ModelStore`, cofre nem rede, e por isso se testa na JVM.
 */
class ReasoningEngines(
    private val isModelInstalled: (modelId: String) -> Boolean,
    private val hasOpenAiCredential: () -> Boolean,
    private val hasAnthropicCredential: () -> Boolean = { false },
    private val hasGoogleCredential: () -> Boolean = { false },
    private val local: (modelId: String) -> ReasoningEngine,
    private val openAi: (model: String) -> ReasoningEngine,
    private val anthropic: (model: String) -> ReasoningEngine = { openAi(it) },
    private val google: (model: String) -> ReasoningEngine = { openAi(it) },
    private val custom: (settings: Settings) -> ReasoningEngine = { openAi(it.reasoningCustomModel) },
) {
    fun resolve(settings: Settings): ReasoningResolution = when (settings.reasoningSource) {
        ReasoningSource.CREDITS -> ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED)
        ReasoningSource.LOCAL -> {
            val modelId = settings.reasoningLocalModelId
            when {
                modelId == null -> ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL)
                !isModelInstalled(modelId) -> ReasoningResolution.Unavailable(UnavailableReason.MODEL_NOT_INSTALLED)
                else -> ReasoningResolution.Ready(local(modelId))
            }
        }
        ReasoningSource.OPENAI -> when {
            !hasOpenAiCredential() -> ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED)
            settings.reasoningOpenAiModel.isBlank() -> ReasoningResolution.Unavailable(UnavailableReason.NO_MODEL_CHOSEN)
            else -> ReasoningResolution.Ready(openAi(settings.reasoningOpenAiModel))
        }
        ReasoningSource.ANTHROPIC -> when {
            !hasAnthropicCredential() -> ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED)
            settings.reasoningAnthropicModel.isBlank() -> ReasoningResolution.Unavailable(UnavailableReason.NO_MODEL_CHOSEN)
            else -> ReasoningResolution.Ready(anthropic(settings.reasoningAnthropicModel))
        }
        ReasoningSource.GOOGLE -> when {
            !hasGoogleCredential() -> ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED)
            settings.reasoningGoogleModel.isBlank() -> ReasoningResolution.Unavailable(UnavailableReason.NO_MODEL_CHOSEN)
            else -> ReasoningResolution.Ready(google(settings.reasoningGoogleModel))
        }
        ReasoningSource.CUSTOM -> when {
            settings.reasoningCustomEndpoint.isBlank() -> ReasoningResolution.Unavailable(UnavailableReason.NOT_CONNECTED)
            settings.reasoningCustomModel.isBlank() -> ReasoningResolution.Unavailable(UnavailableReason.NO_MODEL_CHOSEN)
            else -> ReasoningResolution.Ready(custom(settings))
        }
    }
}
