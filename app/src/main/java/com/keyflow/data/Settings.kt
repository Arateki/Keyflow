package com.keyflow.data

import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.OverlaySide
import com.keyflow.core.ReasoningSource
import com.keyflow.core.RecordingMode
import com.keyflow.core.ShortcutOverflow
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.core.FalaSource

/**
 * Estado completo de configuração persistido (T5 / VOZ-01..06, OVL-05, FND-04, REC modos).
 *
 * Distinto de [Config], que é a configuração **efetiva** consumida pelo `TranscriptionClient`.
 * Os campos `custom*` compartilham as chaves legadas do DataStore (endpoint/header/model/language),
 * de modo que `SettingsRepository.flow()`/`save()` continuam válidos durante a migração (T10/T22).
 *
 * Os segredos (tokens, chaves de API, senha e chave privada SSH, valores de cabeçalho de autenticação —
 * ver [SettingsSecrets.BY_PROPERTY]) ficam **criptografados**, fora do DataStore e do backup, e são preservados
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
    // Local (native-voice-models): modelo de voz do aparelho selecionado no gerenciador (id do
    // catálogo) e idioma forçado — null = nenhum modelo / detecção automática (MGR-03/05).
    val localModelId: String? = null,
    val localLanguage: String? = null,
    // Raciocínio (reasoning-models): a origem, o modelo de texto local escolhido (id do catálogo `TEXT`; null =
    // nenhum) e os modelos em nuvem (OpenAI, Anthropic, Google, Custom). As credenciais dos provedores com login ficam no cofre
    // de `auth/` (AUTH-02); só o valor do cabeçalho do Custom é segredo das Settings (cofre cifrado, ver [SettingsSecrets]).
    val reasoningSource: ReasoningSource = ReasoningSource.LOCAL,
    val reasoningLocalModelId: String? = null,
    val reasoningOpenAiModel: String = "",
    val reasoningAnthropicModel: String = "claude-3-5-sonnet-latest",
    val reasoningGoogleModel: String = "gemini-2.0-flash",
    val reasoningCustomEndpoint: String = "",
    val reasoningCustomHeaderName: String = "Authorization",
    val reasoningCustomHeaderValue: String = "",
    val reasoningCustomModel: String = "",
    // Visão (vision-capture): a origem, o modelo local escolhido, destino da tradução e configurações em nuvem/custom.
    val visionSource: VisionSource = VisionSource.LOCAL,
    val visionLocalModelId: String? = null,
    val visionTranslateTarget: String = "pt",
    val visionOpenAiModel: String = "gpt-4o",
    val visionGoogleApiKey: String = "",
    val visionGoogleModel: String = "gemini-2.0-flash",
    val visionAnthropicApiKey: String = "",
    val visionAnthropicModel: String = "claude-3-5-sonnet-latest",
    val visionCustomEndpoint: String = "",
    val visionCustomHeaderName: String = "Authorization",
    val visionCustomHeaderValue: String = "",
    val visionCustomModel: String = "",
    // Voz / Fala (speech-synthesis): a origem, modelo local, velocidade, e provedores na nuvem (OpenAI, ElevenLabs, Google, Custom).
    val falaSource: FalaSource = FalaSource.LOCAL,
    val falaLocalModelId: String? = null,
    val falaSpeed: Float = 1.0f,
    val falaCloudVoice: String = "alloy",
    val falaElevenLabsApiKey: String = "",
    val falaElevenLabsVoice: String = "Rachel",
    val falaElevenLabsModel: String = "eleven_multilingual_v2",
    val falaGoogleApiKey: String = "",
    val falaGoogleVoice: String = "pt-BR-Neural2-A",
    val falaCustomEndpoint: String = "",
    val falaCustomHeaderName: String = "Authorization",
    val falaCustomHeaderValue: String = "",
    val falaCustomModel: String = "",
    // Computador (acesso SSH remoto e shell local)
    val computerMode: String = "ssh",
    val computerSshHost: String = "",
    val computerSshPort: Int = 22,
    val computerSshUser: String = "",
    val computerSshAuthType: String = "password",
    val computerSshPassword: String = "",
    val computerSshKey: String = "",
    // Provisionamento e espelhamento dinâmico de modelos via infra própria / API em Rust
    val customModelMirrorUrl: String = "",
    // Provedores em nuvem (perfis e chaves centralizadas)
    val providerAnthropicApiKey: String = "",
    val providerAnthropicModel: String = "claude-3-5-sonnet-latest",
    val providerAnthropicAuthMode: String = "subscription",
    val providerOpenAiApiKey: String = "",
    val providerOpenAiModel: String = "gpt-4o",
    val providerOpenAiAuthMode: String = "subscription",
    val providerGoogleApiKey: String = "",
    val providerGoogleModel: String = "gemini-2.0-flash",
    val providerGoogleAuthMode: String = "subscription",
    val providerElevenLabsAuthMode: String = "subscription",
    val providerGroqApiKey: String = "",
    val providerGroqModel: String = "llama-3.3-70b-versatile",
    val providerGrokApiKey: String = "",
    val providerGrokModel: String = "grok-2",
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
    // Atalhos de toque da orbe. 1 toque só existe no PUSH_TO_TALK (na janela de release, mão livre);
    // 2/3 toques agem em idle/pause. Fora do PTT o de 1 toque aparece na config mas fica desabilitado.
    val shortcut1Tap: OrbShortcutAction = OrbShortcutAction.NONE,
    val shortcut2Tap: OrbShortcutAction = OrbShortcutAction.TOGGLE_KEYBOARD,
    val shortcut3Tap: OrbShortcutAction = OrbShortcutAction.SWITCH_IME,
    // Toques além do máximo (>3): cair no atalho anterior ou cancelar.
    val shortcutOverflow: ShortcutOverflow = ShortcutOverflow.NEAREST_DOWN,
    // Vibração ao interagir com a orbe: 0 = sem vibração; 1..100 = intensidade (mapeada p/ amplitude
    // onde o aparelho suporta hasAmplitudeControl; senão vira liga/desliga).
    val vibrationIntensity: Int = 60,
    // Tema + onboarding
    val themeDark: Boolean = false,
    val onboardingDone: Boolean = false,
    // Tempo limite de resposta da transcrição (ms) — compartilhado entre OpenAI/Groq/Custom, não é por
    // preset (aba Voz → seção Geral). Bug real reportado pelo Yan: sem isto, uma rota de rede ruim
    // trava "transcrevendo" pra sempre (OkHttp/JVM não tem timeout de DNS por padrão).
    // 0 = sem limite ("∞", pedido do Yan, 2026-09-28).
    val transcriptionTimeoutMs: Int = Config.DEFAULT_TIMEOUT_MS.toInt(),
    // Duração máxima de uma gravação (s), aba Voz → seção Geral (pedido do Yan, 2026-07-02). MOCKADO:
    // o valor é persistido, mas nada ainda o aplica durante a gravação — o comportamento real (pausar
    // ao atingir o limite + toast pra enviar/descartar) fica pendente, ver STATE.md.
    // 0 = sem limite ("∞", pedido do Yan, 2026-09-28) — o coordinator já tratava 0 como desligado.
    val maxRecordingSec: Int = 300,
    // Tamanho do teclado KeyFlow (%), aba Teclado (pedido do Yan, 2026-07-02) — reusa o `scale: Float`
    // que já existe em KeyFlowKeyboardView/KeyboardGridView/CandidatesView (era só usado pela demo da
    // aba Modo). 100 = tamanho padrão; a View multiplica cada dp por este fator/100.
    val keyboardScalePercent: Int = 100,
    // Idioma do teclado ("pt"/"en"/"es"), gerenciado 100% pelo app — NÃO pelos subtypes do sistema
    // Android (pedido do Yan, 2026-07-02: declarar >1 subtype fazia o seletor "Trocar teclado" do
    // sistema listar "KeyFlow" repetido, um por idioma, e a troca pelo globo dependia de habilitar
    // manualmente cada um na tela de Idiomas do Android — experiência ruim, diferente do Gboard, que
    // também gerencia idioma só dentro do próprio app). O globo cicla por aqui: pt→en→es→pt.
    val keyboardLanguage: String = "pt",
    // Fileira dedicada de números acima do "qwertyuiop" (estilo Gboard), aba Teclado (pedido do Yan,
    // 2026-07-03). Desligada (padrão): números só via long-press na 1ª fileira (comportamento atual).
    // Ligada: fileira nova SÓ na camada de letras (a de símbolos já tem números na 1ª fileira) — e o
    // long-press de números na 1ª fileira de letras é desativado (redundante com a fileira nova).
    val keyboardNumberRowEnabled: Boolean = false,
    // Com um teclado físico conectado, o `onEvaluateInputViewShown()` padrão do `InputMethodService`
    // esconde o teclado virtual — e some junto o mic, que é o produto. Ligado por padrão: hoje o
    // KeyFlow simplesmente desaparece nessa situação, e o Gboard também mostra. Desmarcar devolve o
    // comportamento do sistema.
    val keyboardShowWithHardKeyboard: Boolean = true,
    // Correção automática (Q9, pedido do Yan, 2026-09-28): trocar a palavra no espaço/pontuação quando o
    // corretor tem certeza. Desligada, as sugestões continuam na faixa — só a troca sozinha para.
    val keyboardAutoCorrect: Boolean = true,
    // Cores do teclado (id de `KeyboardSkin`): "system" (padrão desde 2026-09-28, como o Samsung) ou
    // "atelie" (o papel quente de antes). Só o teclado; o app de config segue no Ateliê.
    val keyboardSkin: String = "system",
    // Skin da orbe do overlay ("atelie" ou "aero"), escolhida na aba Sobrepor e independente da do teclado
    // (Yan, 2026-09-29: o Aero no teclado não deve mexer na orbe).
    val orbSkin: String = "atelie",
    // Idioma da interface (código de `AppLanguage`: "pt"/"en"/"es"). Vazio = ainda não escolhido: vale o
    // português, e a SetupActivity grava o idioma do aparelho na primeira abertura (Yan, 2026-09-28).
    val appLanguage: String = "",
    // Transcrição parcial ao pausar (native-voice-models, G12 / PAR-01): cada pausa transcreve o trecho
    // gravado até ali para um buffer da sessão; o 2º toggle só vale com o 1º ligado. Agnósticos à origem.
    val partialOnPause: Boolean = false,
    val partialViewEdit: Boolean = false,
) {
    /**
     * Resolve a [Config] efetiva para uma origem (VOZ-02/03/06). CREDITS é stub: retorna
     * [EffectiveConfig.Unavailable] para o consumidor avisar "em breve" e abortar (VOZ-05).
     *
     * LOCAL não tem [Config] HTTP: com modelo selecionado vira [EffectiveConfig.Local]; sem modelo cai
     * em [EffectiveConfig.Unavailable], o mesmo caminho que já termina em `CONFIG_MISSING` (NAT-03).
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
                timeoutMs = transcriptionTimeoutMs.toLong(),
            )
        )
        TranscriptionSource.GROQ -> EffectiveConfig.Ready(
            Config(
                endpointUrl = GROQ_ENDPOINT,
                authHeaderName = "Authorization",
                authHeaderValue = bearer(groqToken),
                model = groqModel,
                language = groqLanguage,
                timeoutMs = transcriptionTimeoutMs.toLong(),
            )
        )
        TranscriptionSource.LOCAL ->
            localModelId?.let { EffectiveConfig.Local(modelId = it, language = localLanguage) }
                ?: EffectiveConfig.Unavailable
        TranscriptionSource.CUSTOM -> EffectiveConfig.Ready(
            Config(
                endpointUrl = customEndpoint,
                authHeaderName = customHeaderName,
                authHeaderValue = customHeaderValue,
                model = customModel,
                language = customLanguage,
                timeoutMs = transcriptionTimeoutMs.toLong(),
            )
        )
    }

    /** Ação mapeada para uma rajada de [count] toques (1/2/3); demais contagens → [OrbShortcutAction.NONE]. */
    fun shortcutForTaps(count: Int): OrbShortcutAction = when (count) {
        1 -> shortcut1Tap
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
        if (count < 1) return OrbShortcutAction.NONE
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
    /** Modelo do aparelho: sem rede, sem [Config] HTTP — quem transcreve é o engine local (NAT-01). */
    data class Local(val modelId: String, val language: String?) : EffectiveConfig
    /** Origem sem como transcrever: Créditos ("em breve") ou Local sem modelo selecionado (NAT-03). */
    data object Unavailable : EffectiveConfig
}
