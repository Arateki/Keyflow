package com.keyflow.vision

import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.reasoning.UnavailableReason

sealed interface VisionResolution {
    data class Ready(val engine: VisionModelEngine) : VisionResolution
    data class Unavailable(val reason: UnavailableReason) : VisionResolution
}

/** Só a origem selecionada participa. As capacidades e credenciais são consultas locais, sem iniciar download ou rede. */
class VisionModelEngines(
    private val isInstalled: (String) -> Boolean,
    private val isLocalVision: (String) -> Boolean,
    private val hasCredential: (VisionProvider) -> Boolean,
    private val local: (String) -> VisionModelEngine,
    private val cloud: (VisionProvider, String) -> VisionModelEngine,
    private val custom: (Settings) -> VisionModelEngine,
) {
    fun resolve(settings: Settings): VisionResolution {
        val source = settings.visionSource
        if (source == VisionSource.LOCAL) {
            val id = settings.visionLocalModelId ?: return VisionResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL)
            if (!isLocalVision(id) || !isInstalled(id)) return VisionResolution.Unavailable(UnavailableReason.MODEL_NOT_INSTALLED)
            return VisionResolution.Ready(local(id))
        }
        if (source == VisionSource.CUSTOM) {
            if (settings.visionCustomEndpoint.isBlank()) return VisionResolution.Unavailable(UnavailableReason.NOT_CONNECTED)
            if (settings.visionCustomModel.isBlank()) return VisionResolution.Unavailable(UnavailableReason.NO_MODEL_CHOSEN)
            return VisionResolution.Ready(custom(settings))
        }
        val provider = when (source) {
            VisionSource.OPENAI -> VisionProvider.OPENAI
            VisionSource.ANTHROPIC -> VisionProvider.ANTHROPIC
            VisionSource.GOOGLE -> VisionProvider.GOOGLE
            else -> return VisionResolution.Unavailable(UnavailableReason.NOT_CONNECTED)
        }
        if (!hasCredential(provider)) return VisionResolution.Unavailable(UnavailableReason.NOT_CONNECTED)
        val stored = when (provider) {
            VisionProvider.OPENAI -> settings.visionOpenAiModel
            VisionProvider.ANTHROPIC -> settings.visionAnthropicModel
            VisionProvider.GOOGLE -> settings.visionGoogleModel
        }
        return VisionResolution.Ready(cloud(provider, VisionModelCatalog.resolve(provider, stored)))
    }
}
