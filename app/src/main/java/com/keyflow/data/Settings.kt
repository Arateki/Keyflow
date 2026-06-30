package com.keyflow.data

import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.core.ShortcutOverflow
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
    // Modo de gravação da orbe/overlay (C16; default TOGGLE). O teclado não tem modo:
    // é toque (começar/enviar) + seletor de ações ao segurar o mic.
    val overlayRecordingMode: RecordingMode = RecordingMode.TOGGLE,
    // Colar automaticamente (só vale com o teclado KeyFlow ativo; no overlay com teclado nativo o
    // texto vai para a área de transferência). Dois cenários independentes:
    //  - inField: gravou com um campo já focado → cola direto quando a transcrição volta.
    //  - onFocus: gravou fora de um campo → cola ao tocar no próximo campo.
    val autoPasteInField: Boolean = true,
    val autoPasteOnFocus: Boolean = true,
    // Overlay
    val overlaySide: OverlaySide = OverlaySide.RIGHT,
    // Tamanho da orbe em px (slider contínuo na aba Sobrepor; aplicado ao vivo pelo OverlayService).
    // Padrão no meio da faixa do slider (110..280).
    val orbSizePx: Int = 195,
    // Intervalo máximo (ms) entre toques para acionar os atalhos da orbe — também define o delay de
    // confirmação do comando de gravação (rajada de toques). Slider na aba Sobrepor → Atalhos.
    val tapBurstWindowMs: Int = 250,
    // Atalhos de toque da orbe (acionados em idle/pause). 1 toque é reservado ao gesto do modo.
    val shortcut2Tap: OrbShortcutAction = OrbShortcutAction.TOGGLE_KEYBOARD,
    val shortcut3Tap: OrbShortcutAction = OrbShortcutAction.SWITCH_IME,
    // Toques além do máximo (>3): cair no atalho anterior ou cancelar.
    val shortcutOverflow: ShortcutOverflow = ShortcutOverflow.NEAREST_DOWN,
    // Retorno tátil: vibração leve ao interagir com a orbe.
    val hapticFeedback: Boolean = true,
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

    /** Ação mapeada para uma rajada de [count] toques (2 ou 3); demais contagens → [OrbShortcutAction.NONE]. */
    fun shortcutForTaps(count: Int): OrbShortcutAction = when (count) {
        2 -> shortcut2Tap
        3 -> shortcut3Tap
        else -> OrbShortcutAction.NONE
    }

    /**
     * Ação efetiva de uma rajada de [count] toques, já aplicando a política de overflow para >3 toques:
     * cancela ([ShortcutOverflow.CANCEL]) ou cai no atalho ativo de menos toques mais próximo
     * ([ShortcutOverflow.NEAREST_DOWN]).
     */
    fun resolveShortcut(count: Int): OrbShortcutAction {
        if (count < 2) return OrbShortcutAction.NONE
        if (count <= 3) return shortcutForTaps(count)
        return when (shortcutOverflow) {
            ShortcutOverflow.CANCEL -> OrbShortcutAction.NONE
            ShortcutOverflow.NEAREST_DOWN ->
                (3 downTo 2).map { shortcutForTaps(it) }.firstOrNull { it != OrbShortcutAction.NONE }
                    ?: OrbShortcutAction.NONE
        }
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
