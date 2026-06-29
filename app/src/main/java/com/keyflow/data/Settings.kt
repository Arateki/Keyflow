package com.keyflow.data

import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.core.TranscriptionSource

/**
 * Estado completo de configuração persistido (T5 / VOZ-01..06, OVL-05, FND-04, REC modos).
 *
 * Distinto de [Config], que é a configuração **efetiva** consumida pelo `TranscriptionClient`.
 * Os campos `custom*` compartilham as chaves legadas do DataStore (endpoint/header/model/language),
 * de modo que `SettingsRepository.flow()`/`save()` continuam válidos durante a migração (T10/T22).
 *
 * Tokens (`openaiToken`, `groqToken`, `customHeaderValue`) ficam **criptografados** e são preservados
 * ao trocar de preset — trocar a `source` nunca apaga a credencial de outro provedor.
 */
data class Settings(
    val source: TranscriptionSource = TranscriptionSource.CUSTOM,
    // Custom (paridade com a config atual)
    val customEndpoint: String = "",
    val customHeaderName: String = "Authorization",
    val customHeaderValue: String = "",
    val customModel: String = "whisper-1",
    val customLanguage: String? = null,
    // OpenAI (BYO-key)
    val openaiToken: String = "",
    val openaiModel: String = OPENAI_MODEL,
    val openaiLanguage: String? = null,
    // Groq (BYO-key)
    val groqToken: String = "",
    val groqModel: String = GROQ_MODEL,
    val groqLanguage: String? = null,
    // Modos habilitados (gating das abas; cada modo libera sua aba — CFG-02)
    val overlayModeEnabled: Boolean = false,
    val keyboardModeEnabled: Boolean = false,
    // Modos de gravação por gatilho (C16; default TOGGLE)
    val overlayRecordingMode: RecordingMode = RecordingMode.TOGGLE,
    val keyboardRecordingMode: RecordingMode = RecordingMode.TOGGLE,
    // Colar automaticamente (só vale com o teclado KeyFlow ativo; no overlay com teclado nativo o
    // texto vai para a área de transferência). Dois cenários independentes:
    //  - inField: gravou com um campo já focado → cola direto quando a transcrição volta.
    //  - onFocus: gravou fora de um campo → cola ao tocar no próximo campo.
    val autoPasteInField: Boolean = true,
    val autoPasteOnFocus: Boolean = true,
    // Overlay
    val overlaySide: OverlaySide = OverlaySide.RIGHT,
    // Tamanho da orbe em px (slider contínuo na aba Sobrepor; aplicado ao vivo pelo OverlayService).
    val orbSizePx: Int = 156,
    // Tema + onboarding
    val themeDark: Boolean = false,
    val onboardingDone: Boolean = false,
) {
    /**
     * Resolve a [Config] efetiva para uma origem (VOZ-02/03/06). CREDITS é stub: retorna
     * [EffectiveConfig.Unavailable] para o consumidor avisar "em breve" e abortar (VOZ-05).
     */
    fun effectiveConfigFor(source: TranscriptionSource = this.source): EffectiveConfig = when (source) {
        TranscriptionSource.CREDITS -> EffectiveConfig.Unavailable
        TranscriptionSource.OPENAI -> EffectiveConfig.Ready(
            Config(
                endpointUrl = OPENAI_ENDPOINT,
                authHeaderName = "Authorization",
                authHeaderValue = bearer(openaiToken),
                model = openaiModel,
                language = openaiLanguage,
            )
        )
        TranscriptionSource.GROQ -> EffectiveConfig.Ready(
            Config(
                endpointUrl = GROQ_ENDPOINT,
                authHeaderName = "Authorization",
                authHeaderValue = bearer(groqToken),
                model = groqModel,
                language = groqLanguage,
            )
        )
        TranscriptionSource.CUSTOM -> EffectiveConfig.Ready(
            Config(
                endpointUrl = customEndpoint,
                authHeaderName = customHeaderName,
                authHeaderValue = customHeaderValue,
                model = customModel,
                language = customLanguage,
            )
        )
    }

    private fun bearer(token: String): String = if (token.isBlank()) "" else "Bearer $token"

    companion object {
        // Endpoints/modelos verificados nas docs (OpenAI/GroqDocs, 2026), não fabricados.
        const val OPENAI_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val OPENAI_MODEL = "whisper-1"
        const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val GROQ_MODEL = "whisper-large-v3"
    }
}

/** Resultado de [Settings.effectiveConfigFor]. */
sealed interface EffectiveConfig {
    data class Ready(val config: Config) : EffectiveConfig
    /** Origem indisponível nesta fase (Créditos — "em breve"). */
    data object Unavailable : EffectiveConfig
}
