package com.keyflow.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.security.crypto.MasterKey
import com.keyflow.core.FalaSource
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.OverlaySide
import com.keyflow.core.ReasoningSource
import com.keyflow.core.RecordingMode
import com.keyflow.core.ShortcutOverflow
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutCodec
import com.keyflow.core.shortcuts.ShortcutGrid
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelKind
import com.keyflow.data.models.ModelProvisioning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.io.File

class SettingsRepository(
    context: Context,
    dataStoreFile: File = context.preferencesDataStoreFile(DATASTORE_NAME),
    private val securePrefsName: String = SECURE_PREFS_NAME,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = dataStoreFor(dataStoreFile, scope)
    private val securePrefs: SharedPreferences by lazy {
        createSecurePrefs(appContext, securePrefsName)
    }

    /**
     * Config efetivo consumido pelo transcritor. Resolve pela **origem selecionada** ([Settings.source])
     * via [Settings.effectiveConfigFor] — antes ignorava o source e usava só os campos legados do CUSTOM,
     * o que dava `CONFIG_MISSING` ao escolher OpenAI/Groq. CREDITS (em breve) → Config incompleto.
     */
    /**
     * Leitura leve só do tema, lendo **apenas o DataStore** — não inicializa o EncryptedSharedPreferences
     * (cripto cara). Usada no caminho crítico do IME (`onCreateInputView`) para não travar a thread
     * principal ao criar o teclado, o que atrasava a exibição na troca de IME.
     */
    suspend fun themeDarkOnce(): Boolean = dataStore.data.first()[THEME_DARK] ?: false

    /** Mesma leitura leve de [themeDarkOnce], para o tamanho do teclado (também lido em `onCreateInputView`). */
    suspend fun keyboardScalePercentOnce(): Int = dataStore.data.first()[KEYBOARD_SCALE_PERCENT] ?: 100

    /**
     * Observação ao vivo de tema/tamanho/idioma (pedido do Yan, 2026-07-03: aplicar sem precisar reabrir
     * o teclado) — mesmo padrão de [barsConfigFlow]/[shortcutGridsFlow] (BAR-07).
     */
    fun themeDarkFlow(): Flow<Boolean> = dataStore.data.map { it[THEME_DARK] ?: false }
    fun keyboardScalePercentFlow(): Flow<Int> = dataStore.data.map { it[KEYBOARD_SCALE_PERCENT] ?: 100 }
    fun keyboardLanguageFlow(): Flow<String> = dataStore.data.map { it[KEYBOARD_LANGUAGE] ?: "pt" }
    fun keyboardNumberRowEnabledFlow(): Flow<Boolean> =
        dataStore.data.map { it[KEYBOARD_NUMBER_ROW_ENABLED] ?: false }

    /** Mesma leitura leve de [themeDarkOnce], para a fileira de números (também lida em `onCreateInputView`). */
    suspend fun keyboardNumberRowEnabledOnce(): Boolean =
        dataStore.data.first()[KEYBOARD_NUMBER_ROW_ENABLED] ?: false

    fun keyboardShowWithHardKeyboardFlow(): Flow<Boolean> =
        dataStore.data.map { it[KEYBOARD_SHOW_WITH_HARD_KEYBOARD] ?: true }

    fun keyboardAutoCorrectFlow(): Flow<Boolean> = dataStore.data.map { it[KEYBOARD_AUTO_CORRECT] ?: true }

    fun keyboardSkinFlow(): Flow<String> = dataStore.data.map { it[KEYBOARD_SKIN] ?: "system" }

    /** Idioma da interface, leve (só DataStore): o [com.keyflow.App] aplica no processo inteiro. */
    fun appLanguageFlow(): Flow<String> = dataStore.data.map { it[APP_LANGUAGE] ?: "" }

    /** Mesma leitura leve de [themeDarkOnce], para as cores do teclado em `onCreateInputView`. */
    suspend fun keyboardSkinOnce(): String = dataStore.data.first()[KEYBOARD_SKIN] ?: "system"

    /**
     * Idioma do teclado ("pt"/"en"/"es"), gerenciado pelo app (não pelos subtypes do sistema — ver
     * [Settings.keyboardLanguage]). Leitura leve mesmo padrão de [themeDarkOnce]; escrita separada
     * (não passa pelo round-trip completo de [saveSettings]) porque o globo troca isto sozinho, sem
     * tocar no resto da config.
     */
    suspend fun keyboardLanguageOnce(): String = dataStore.data.first()[KEYBOARD_LANGUAGE] ?: "pt"

    suspend fun saveKeyboardLanguageOnce(language: String) {
        dataStore.edit { it[KEYBOARD_LANGUAGE] = language }
    }

    suspend fun saveOnboardingDone(done: Boolean) {
        dataStore.edit { it[ONBOARDING_DONE] = done }
    }

    suspend fun saveOverlayModeEnabled(enabled: Boolean) {
        dataStore.edit { it[OVERLAY_MODE_ENABLED] = enabled }
    }

    suspend fun saveKeyboardModeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEYBOARD_MODE_ENABLED] = enabled }
    }

    /** Leitura leve da intensidade de vibração (háptica da grade efêmera/alavanca — GRD-03/JOY-02). */
    suspend fun vibrationIntensityOnce(): Int = dataStore.data.first().let { prefs ->
        prefs[VIBRATION_INTENSITY] ?: (if (prefs[HAPTIC_FEEDBACK] == false) 0 else 60)
    }

    // ── Barras personalizáveis + teclados de atalhos (Trabalho 3 / SB-T5) ────
    // Strings JSON no DataStore (nada aqui é segredo — fora do EncryptedSharedPreferences); o codec cai
    // no DEFAULT/vazio em config malformada, então o IME nunca quebra por dado ruim.

    /** Leitura leve para o `onCreateInputView` do IME (só DataStore, como [themeDarkOnce]). */
    suspend fun barsConfigOnce(): BarsConfig =
        ShortcutCodec.decodeBars(dataStore.data.first()[BARS_CONFIG_JSON])

    suspend fun shortcutGridsOnce(): List<ShortcutGrid> =
        ShortcutCodec.decodeGrids(dataStore.data.first()[SHORTCUT_GRIDS_JSON])

    /** Observação ao vivo (BAR-07): o service recria a view do teclado quando a config muda. */
    fun barsConfigFlow(): Flow<BarsConfig> =
        dataStore.data.map { ShortcutCodec.decodeBars(it[BARS_CONFIG_JSON]) }

    fun shortcutGridsFlow(): Flow<List<ShortcutGrid>> =
        dataStore.data.map { ShortcutCodec.decodeGrids(it[SHORTCUT_GRIDS_JSON]) }

    /**
     * MGR-03: um modelo acabou de instalar e nenhum está selecionado → seleciona. Escrita atômica só da
     * chave do modelo: não disputa com o auto-save da tela (que regrava as Settings inteiras).
     * @return true se selecionou.
     */
    suspend fun selectLocalModelIfNone(modelId: String): Boolean = selectInstalledModelIfNone(modelId, ModelKind.SPEECH)

    /**
     * O mesmo para os tipos de modelo: a chave é a do tipo — fala escolhe o modelo da Transcrição, texto escolhe o
     * do Raciocínio, visão escolhe o da Visão.
     */
    suspend fun selectInstalledModelIfNone(modelId: String, kind: ModelKind): Boolean {
        val key = when (kind) {
            ModelKind.TEXT -> REASONING_LOCAL_MODEL_ID
            ModelKind.VISION -> VISION_LOCAL_MODEL_ID
            ModelKind.VOICE -> FALA_LOCAL_MODEL_ID
            ModelKind.SPEECH -> LOCAL_MODEL_ID
        }
        var selected = false
        dataStore.edit { prefs ->
            if (prefs[key].isNullOrBlank()) {
                prefs[key] = modelId
                selected = true
            }
        }
        return selected
    }

    /** Gancho do downloader ao instalar: o tipo vem do catálogo; id que o catálogo não conhece não seleciona nada. */
    internal suspend fun selectOnInstall(modelId: String) {
        val spec = ModelCatalog.byId(modelId) ?: return
        selectInstalledModelIfNone(spec.id, spec.kind)
    }

    /**
     * O DataStore inteiro, chave por chave, como está gravado. Existe para o gate de instrumentados
     * guardar a config de quem é dono do aparelho antes de rodar a suíte no estado de fábrica
     * (`FactorySettingsListener`, androidTest) — nenhuma tela do app usa isto.
     *
     * Migra antes os segredos que um build antigo deixou em claro ([migrateLegacySecrets]): o gate copia o que
     * lê para um arquivo em `files/`, que seria mais uma cópia em claro (e no backup) do segredo.
     */
    internal suspend fun rawPreferencesOnce(): Preferences {
        migrateLegacySecrets()
        return dataStore.data.first()
    }

    /** Troca o DataStore inteiro por [prefs]; vazio = estado de fábrica. Par de [rawPreferencesOnce]. */
    internal suspend fun replaceRawPreferences(prefs: Preferences) {
        dataStore.edit { current ->
            current.clear()
            current += prefs
        }
    }

    suspend fun saveBarsConfig(config: BarsConfig) {
        dataStore.edit { it[BARS_CONFIG_JSON] = ShortcutCodec.encodeBars(config) }
    }

    suspend fun saveShortcutGrids(grids: List<ShortcutGrid>) {
        dataStore.edit { it[SHORTCUT_GRIDS_JSON] = ShortcutCodec.encodeGrids(grids) }
    }

    fun flow(): Flow<Config> = settingsFlow().map { settings ->
        when (val eff = settings.effectiveConfigFor()) {
            is EffectiveConfig.Ready -> eff.config
            // Sem endpoint nem token: o RoutingTranscriber manda para o :inference (NM-T9, PRV-01).
            is EffectiveConfig.Local -> Config(
                timeoutMs = Config.localTimeoutMs(settings.transcriptionTimeoutMs.toLong()),
                local = eff,
            )
            EffectiveConfig.Unavailable -> Config()
        }
    }

    /**
     * Estado completo de configuração (T5). Os campos `custom*` compartilham as chaves legadas;
     * os segredos (tokens, chaves de API, senha e chave SSH, valores de cabeçalho — [SettingsSecrets.BY_PROPERTY])
     * vêm do armazenamento criptografado e são preservados entre trocas de origem.
     */
    fun settingsFlow(): Flow<Settings> = dataStore.data.onStart { migrateLegacySecrets() }.map { prefs ->
        val mirrorUrl = prefs[CUSTOM_MODEL_MIRROR_URL]?.ifBlank { null }
        ModelProvisioning.customMirrorUrl = mirrorUrl
        Settings(
            source = prefs[SOURCE].toSource(),
            customEndpoint = prefs[ENDPOINT_URL].orEmpty(),
            customHeaderName = prefs[AUTH_HEADER_NAME] ?: "Authorization",
            customHeaderValue = securePrefs.getString(SettingsSecrets.AUTH_HEADER_VALUE, "").orEmpty(),
            customModel = prefs[MODEL] ?: "whisper-1",
            customLanguage = prefs[LANGUAGE]?.takeIf { it.isNotBlank() },
            openaiToken = securePrefs.getString(SettingsSecrets.OPENAI_TOKEN, "").orEmpty(),
            openaiModel = prefs[OPENAI_MODEL] ?: Settings.OPENAI_MODEL,
            openaiLanguage = prefs[OPENAI_LANGUAGE]?.takeIf { it.isNotBlank() },
            groqToken = securePrefs.getString(SettingsSecrets.GROQ_TOKEN, "").orEmpty(),
            groqModel = prefs[GROQ_MODEL] ?: Settings.GROQ_MODEL,
            groqLanguage = prefs[GROQ_LANGUAGE]?.takeIf { it.isNotBlank() },
            localModelId = prefs[LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            localLanguage = prefs[LOCAL_LANGUAGE]?.takeIf { it.isNotBlank() },
            reasoningSource = prefs[REASONING_SOURCE].toReasoningSource(),
            reasoningLocalModelId = prefs[REASONING_LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            reasoningOpenAiModel = prefs[REASONING_OPENAI_MODEL].orEmpty(),
            reasoningAnthropicModel = prefs[REASONING_ANTHROPIC_MODEL] ?: "claude-3-5-sonnet-latest",
            reasoningGoogleModel = prefs[REASONING_GOOGLE_MODEL] ?: "gemini-2.0-flash",
            reasoningCustomEndpoint = prefs[REASONING_CUSTOM_ENDPOINT].orEmpty(),
            reasoningCustomHeaderName = prefs[REASONING_CUSTOM_HEADER_NAME] ?: "Authorization",
            reasoningCustomHeaderValue = secret(prefs, SettingsSecrets.REASONING_CUSTOM_HEADER_VALUE),
            reasoningCustomModel = prefs[REASONING_CUSTOM_MODEL].orEmpty(),
            visionSource = prefs[VISION_SOURCE].toVisionSource(),
            visionLocalModelId = prefs[VISION_LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            visionTranslateTarget = prefs[VISION_TRANSLATE_TARGET] ?: "pt",
            visionOpenAiModel = prefs[VISION_OPENAI_MODEL] ?: "gpt-4o",
            visionGoogleApiKey = secret(prefs, SettingsSecrets.VISION_GOOGLE_API_KEY),
            visionGoogleModel = prefs[VISION_GOOGLE_MODEL] ?: "gemini-2.0-flash",
            visionAnthropicApiKey = secret(prefs, SettingsSecrets.VISION_ANTHROPIC_API_KEY),
            visionAnthropicModel = prefs[VISION_ANTHROPIC_MODEL] ?: "claude-3-5-sonnet-latest",
            visionCustomEndpoint = prefs[VISION_CUSTOM_ENDPOINT].orEmpty(),
            visionCustomHeaderName = prefs[VISION_CUSTOM_HEADER_NAME] ?: "Authorization",
            visionCustomHeaderValue = secret(prefs, SettingsSecrets.VISION_CUSTOM_HEADER_VALUE),
            visionCustomModel = prefs[VISION_CUSTOM_MODEL].orEmpty(),
            falaSource = prefs[FALA_SOURCE].toFalaSource(),
            falaLocalModelId = prefs[FALA_LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            falaSpeed = prefs[FALA_SPEED] ?: 1.0f,
            falaCloudVoice = prefs[FALA_CLOUD_VOICE] ?: "alloy",
            falaElevenLabsApiKey = secret(prefs, SettingsSecrets.FALA_ELEVENLABS_API_KEY),
            falaElevenLabsVoice = prefs[FALA_ELEVENLABS_VOICE] ?: "Rachel",
            falaElevenLabsModel = prefs[FALA_ELEVENLABS_MODEL] ?: "eleven_multilingual_v2",
            falaGoogleApiKey = secret(prefs, SettingsSecrets.FALA_GOOGLE_API_KEY),
            falaGoogleVoice = prefs[FALA_GOOGLE_VOICE] ?: "pt-BR-Neural2-A",
            falaCustomEndpoint = prefs[FALA_CUSTOM_ENDPOINT].orEmpty(),
            falaCustomHeaderName = prefs[FALA_CUSTOM_HEADER_NAME] ?: "Authorization",
            falaCustomHeaderValue = secret(prefs, SettingsSecrets.FALA_CUSTOM_HEADER_VALUE),
            falaCustomModel = prefs[FALA_CUSTOM_MODEL].orEmpty(),
            computerMode = prefs[COMPUTER_MODE] ?: "ssh",
            computerSshHost = prefs[COMPUTER_SSH_HOST].orEmpty(),
            computerSshPort = prefs[COMPUTER_SSH_PORT] ?: 22,
            computerSshUser = prefs[COMPUTER_SSH_USER].orEmpty(),
            computerSshAuthType = prefs[COMPUTER_SSH_AUTH_TYPE] ?: "password",
            computerSshPassword = secret(prefs, SettingsSecrets.COMPUTER_SSH_PASSWORD),
            computerSshKey = secret(prefs, SettingsSecrets.COMPUTER_SSH_KEY),
            customModelMirrorUrl = prefs[CUSTOM_MODEL_MIRROR_URL].orEmpty(),
            providerAnthropicApiKey = secret(prefs, SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY),
            providerAnthropicModel = prefs[PROVIDER_ANTHROPIC_MODEL] ?: "claude-3-5-sonnet-latest",
            providerAnthropicAuthMode = prefs[PROVIDER_ANTHROPIC_AUTH_MODE] ?: "subscription",
            providerOpenAiApiKey = secret(prefs, SettingsSecrets.PROVIDER_OPENAI_API_KEY),
            providerOpenAiModel = prefs[PROVIDER_OPENAI_MODEL] ?: "gpt-4o",
            providerOpenAiAuthMode = prefs[PROVIDER_OPENAI_AUTH_MODE] ?: "subscription",
            providerGoogleApiKey = secret(prefs, SettingsSecrets.PROVIDER_GOOGLE_API_KEY),
            providerGoogleModel = prefs[PROVIDER_GOOGLE_MODEL] ?: "gemini-2.0-flash",
            providerGoogleAuthMode = prefs[PROVIDER_GOOGLE_AUTH_MODE] ?: "subscription",
            providerElevenLabsAuthMode = prefs[PROVIDER_ELEVENLABS_AUTH_MODE] ?: "subscription",
            providerGroqApiKey = secret(prefs, SettingsSecrets.PROVIDER_GROQ_API_KEY),
            providerGroqModel = prefs[PROVIDER_GROQ_MODEL] ?: "llama-3.3-70b-versatile",
            providerGrokApiKey = secret(prefs, SettingsSecrets.PROVIDER_GROK_API_KEY),
            providerGrokModel = prefs[PROVIDER_GROK_MODEL] ?: "grok-2",
            overlayModeEnabled = prefs[OVERLAY_MODE_ENABLED] ?: false,
            keyboardModeEnabled = prefs[KEYBOARD_MODE_ENABLED] ?: false,
            overlayRecordingMode = prefs[OVERLAY_RECORDING_MODE].toRecordingMode(),
            // Migra o antigo KEYBOARD_AUTO_PASTE único para os dois flags (mesmo valor) quando os novos
            // ainda não existem.
            autoPasteInField = prefs[AUTO_PASTE_IN_FIELD] ?: prefs[KEYBOARD_AUTO_PASTE] ?: true,
            autoPasteOnFocus = prefs[AUTO_PASTE_ON_FOCUS] ?: prefs[KEYBOARD_AUTO_PASTE] ?: true,
            overlaySide = prefs[OVERLAY_SIDE].toOverlaySide(),
            orbSizePx = prefs[ORB_SIZE_PX] ?: 195,
            tapBurstWindowMs = prefs[TAP_BURST_WINDOW_MS] ?: 250,
            shortcut1Tap = prefs[SHORTCUT_1_TAP].toShortcutAction(OrbShortcutAction.NONE),
            shortcut2Tap = prefs[SHORTCUT_2_TAP].toShortcutAction(OrbShortcutAction.TOGGLE_KEYBOARD),
            shortcut3Tap = prefs[SHORTCUT_3_TAP].toShortcutAction(OrbShortcutAction.SWITCH_IME),
            shortcutOverflow = prefs[SHORTCUT_OVERFLOW].toShortcutOverflow(),
            // Migração do antigo liga/desliga: haptic=false → 0; ausente/true → 60.
            vibrationIntensity = prefs[VIBRATION_INTENSITY]
                ?: (if (prefs[HAPTIC_FEEDBACK] == false) 0 else 60),
            themeDark = prefs[THEME_DARK] ?: false,
            onboardingDone = prefs[ONBOARDING_DONE] ?: false,
            transcriptionTimeoutMs = prefs[TRANSCRIPTION_TIMEOUT_MS] ?: Config.DEFAULT_TIMEOUT_MS.toInt(),
            maxRecordingSec = prefs[MAX_RECORDING_SEC] ?: 300,
            keyboardScalePercent = prefs[KEYBOARD_SCALE_PERCENT] ?: 100,
            keyboardLanguage = prefs[KEYBOARD_LANGUAGE] ?: "pt",
            keyboardNumberRowEnabled = prefs[KEYBOARD_NUMBER_ROW_ENABLED] ?: false,
            partialOnPause = prefs[PARTIAL_ON_PAUSE] ?: false,
            partialViewEdit = prefs[PARTIAL_VIEW_EDIT] ?: false,
            keyboardShowWithHardKeyboard = prefs[KEYBOARD_SHOW_WITH_HARD_KEYBOARD] ?: true,
            keyboardAutoCorrect = prefs[KEYBOARD_AUTO_CORRECT] ?: true,
            keyboardSkin = prefs[KEYBOARD_SKIN] ?: "system",
            orbSkin = prefs[ORB_SKIN] ?: "atelie",
            appLanguage = prefs[APP_LANGUAGE] ?: "",
        )
    }

    /**
     * Persiste o estado completo (T5). Segredos criptografados; `null` em texto remove a chave do DataStore.
     * Não apaga credenciais de outros provedores — todos os segredos ([SettingsSecrets.BY_PROPERTY]) são
     * reescritos a partir de [settings]. Só depois de o cofre confirmar o `commit()` o DataStore é tocado, e então
     * qualquer cópia em claro que um build antigo tenha deixado lá é removida (ver [migrateLegacySecrets]).
     */
    suspend fun saveSettings(settings: Settings) {
        val committed = synchronized(SettingsSecrets.LOCK) {
            securePrefs.edit()
                .putString(SettingsSecrets.AUTH_HEADER_VALUE, settings.customHeaderValue)
                .putString(SettingsSecrets.OPENAI_TOKEN, settings.openaiToken)
                .putString(SettingsSecrets.GROQ_TOKEN, settings.groqToken)
                .putString(SettingsSecrets.REASONING_CUSTOM_HEADER_VALUE, settings.reasoningCustomHeaderValue)
                .putString(SettingsSecrets.VISION_GOOGLE_API_KEY, settings.visionGoogleApiKey)
                .putString(SettingsSecrets.VISION_ANTHROPIC_API_KEY, settings.visionAnthropicApiKey)
                .putString(SettingsSecrets.VISION_CUSTOM_HEADER_VALUE, settings.visionCustomHeaderValue)
                .putString(SettingsSecrets.FALA_ELEVENLABS_API_KEY, settings.falaElevenLabsApiKey)
                .putString(SettingsSecrets.FALA_GOOGLE_API_KEY, settings.falaGoogleApiKey)
                .putString(SettingsSecrets.FALA_CUSTOM_HEADER_VALUE, settings.falaCustomHeaderValue)
                .putString(SettingsSecrets.COMPUTER_SSH_PASSWORD, settings.computerSshPassword)
                .putString(SettingsSecrets.COMPUTER_SSH_KEY, settings.computerSshKey)
                .putString(SettingsSecrets.PROVIDER_ANTHROPIC_API_KEY, settings.providerAnthropicApiKey)
                .putString(SettingsSecrets.PROVIDER_OPENAI_API_KEY, settings.providerOpenAiApiKey)
                .putString(SettingsSecrets.PROVIDER_GOOGLE_API_KEY, settings.providerGoogleApiKey)
                .putString(SettingsSecrets.PROVIDER_GROQ_API_KEY, settings.providerGroqApiKey)
                .putString(SettingsSecrets.PROVIDER_GROK_API_KEY, settings.providerGrokApiKey)
                .commit()
        }
        check(committed) { "Unable to persist provider tokens" }

        dataStore.edit { prefs ->
            SettingsSecrets.LEGACY_IN_DATASTORE.forEach { prefs.remove(it) }
            prefs[SOURCE] = settings.source.name
            prefs[ENDPOINT_URL] = settings.customEndpoint
            prefs[AUTH_HEADER_NAME] = settings.customHeaderName
            prefs[MODEL] = settings.customModel
            settings.customLanguage.putOrRemove(prefs, LANGUAGE)
            prefs[OPENAI_MODEL] = settings.openaiModel
            settings.openaiLanguage.putOrRemove(prefs, OPENAI_LANGUAGE)
            prefs[GROQ_MODEL] = settings.groqModel
            settings.groqLanguage.putOrRemove(prefs, GROQ_LANGUAGE)
            settings.localModelId.putOrRemove(prefs, LOCAL_MODEL_ID)
            settings.localLanguage.putOrRemove(prefs, LOCAL_LANGUAGE)
            prefs[REASONING_SOURCE] = settings.reasoningSource.name
            settings.reasoningLocalModelId.putOrRemove(prefs, REASONING_LOCAL_MODEL_ID)
            settings.reasoningOpenAiModel.putOrRemove(prefs, REASONING_OPENAI_MODEL)
            prefs[REASONING_ANTHROPIC_MODEL] = settings.reasoningAnthropicModel
            prefs[REASONING_GOOGLE_MODEL] = settings.reasoningGoogleModel
            prefs[REASONING_CUSTOM_ENDPOINT] = settings.reasoningCustomEndpoint
            prefs[REASONING_CUSTOM_HEADER_NAME] = settings.reasoningCustomHeaderName
            prefs[REASONING_CUSTOM_MODEL] = settings.reasoningCustomModel
            prefs[VISION_SOURCE] = settings.visionSource.name
            settings.visionLocalModelId.putOrRemove(prefs, VISION_LOCAL_MODEL_ID)
            prefs[VISION_TRANSLATE_TARGET] = settings.visionTranslateTarget
            prefs[VISION_OPENAI_MODEL] = settings.visionOpenAiModel
            prefs[VISION_GOOGLE_MODEL] = settings.visionGoogleModel
            prefs[VISION_ANTHROPIC_MODEL] = settings.visionAnthropicModel
            prefs[VISION_CUSTOM_ENDPOINT] = settings.visionCustomEndpoint
            prefs[VISION_CUSTOM_HEADER_NAME] = settings.visionCustomHeaderName
            prefs[VISION_CUSTOM_MODEL] = settings.visionCustomModel
            prefs[FALA_SOURCE] = settings.falaSource.name
            settings.falaLocalModelId.putOrRemove(prefs, FALA_LOCAL_MODEL_ID)
            prefs[FALA_SPEED] = settings.falaSpeed
            prefs[FALA_CLOUD_VOICE] = settings.falaCloudVoice
            prefs[FALA_ELEVENLABS_VOICE] = settings.falaElevenLabsVoice
            prefs[FALA_ELEVENLABS_MODEL] = settings.falaElevenLabsModel
            prefs[FALA_GOOGLE_VOICE] = settings.falaGoogleVoice
            prefs[FALA_CUSTOM_ENDPOINT] = settings.falaCustomEndpoint
            prefs[FALA_CUSTOM_HEADER_NAME] = settings.falaCustomHeaderName
            prefs[FALA_CUSTOM_MODEL] = settings.falaCustomModel
            prefs[COMPUTER_MODE] = settings.computerMode
            prefs[COMPUTER_SSH_HOST] = settings.computerSshHost
            prefs[COMPUTER_SSH_PORT] = settings.computerSshPort
            prefs[COMPUTER_SSH_USER] = settings.computerSshUser
            prefs[COMPUTER_SSH_AUTH_TYPE] = settings.computerSshAuthType
            prefs[CUSTOM_MODEL_MIRROR_URL] = settings.customModelMirrorUrl
            ModelProvisioning.customMirrorUrl = settings.customModelMirrorUrl.ifBlank { null }
            prefs[PROVIDER_ANTHROPIC_MODEL] = settings.providerAnthropicModel
            prefs[PROVIDER_ANTHROPIC_AUTH_MODE] = settings.providerAnthropicAuthMode
            prefs[PROVIDER_OPENAI_MODEL] = settings.providerOpenAiModel
            prefs[PROVIDER_OPENAI_AUTH_MODE] = settings.providerOpenAiAuthMode
            prefs[PROVIDER_GOOGLE_MODEL] = settings.providerGoogleModel
            prefs[PROVIDER_GOOGLE_AUTH_MODE] = settings.providerGoogleAuthMode
            prefs[PROVIDER_ELEVENLABS_AUTH_MODE] = settings.providerElevenLabsAuthMode
            prefs[PROVIDER_GROQ_MODEL] = settings.providerGroqModel
            prefs[PROVIDER_GROK_MODEL] = settings.providerGrokModel
            prefs[OVERLAY_MODE_ENABLED] = settings.overlayModeEnabled
            prefs[KEYBOARD_MODE_ENABLED] = settings.keyboardModeEnabled
            prefs[OVERLAY_RECORDING_MODE] = settings.overlayRecordingMode.name
            prefs[AUTO_PASTE_IN_FIELD] = settings.autoPasteInField
            prefs[AUTO_PASTE_ON_FOCUS] = settings.autoPasteOnFocus
            prefs[OVERLAY_SIDE] = settings.overlaySide.name
            prefs[ORB_SIZE_PX] = settings.orbSizePx
            prefs[TAP_BURST_WINDOW_MS] = settings.tapBurstWindowMs
            prefs[SHORTCUT_1_TAP] = settings.shortcut1Tap.name
            prefs[SHORTCUT_2_TAP] = settings.shortcut2Tap.name
            prefs[SHORTCUT_3_TAP] = settings.shortcut3Tap.name
            prefs[SHORTCUT_OVERFLOW] = settings.shortcutOverflow.name
            prefs[VIBRATION_INTENSITY] = settings.vibrationIntensity
            prefs[THEME_DARK] = settings.themeDark
            prefs[ONBOARDING_DONE] = settings.onboardingDone
            prefs[TRANSCRIPTION_TIMEOUT_MS] = settings.transcriptionTimeoutMs
            prefs[MAX_RECORDING_SEC] = settings.maxRecordingSec
            prefs[KEYBOARD_SCALE_PERCENT] = settings.keyboardScalePercent
            prefs[KEYBOARD_LANGUAGE] = settings.keyboardLanguage
            prefs[KEYBOARD_NUMBER_ROW_ENABLED] = settings.keyboardNumberRowEnabled
            prefs[PARTIAL_ON_PAUSE] = settings.partialOnPause
            prefs[PARTIAL_VIEW_EDIT] = settings.partialViewEdit
            prefs[KEYBOARD_SHOW_WITH_HARD_KEYBOARD] = settings.keyboardShowWithHardKeyboard
            prefs[KEYBOARD_AUTO_CORRECT] = settings.keyboardAutoCorrect
            prefs[KEYBOARD_SKIN] = settings.keyboardSkin
            prefs[ORB_SKIN] = settings.orbSkin
            prefs[APP_LANGUAGE] = settings.appLanguage
        }
    }

    private fun String?.putOrRemove(prefs: MutablePreferences, key: Preferences.Key<String>) {
        if (this.isNullOrBlank()) prefs.remove(key) else prefs[key] = this
    }

    /** O segredo [name]: o do cofre; ver [SettingsSecrets.read] para o caso do que ainda está em claro no DataStore. */
    private fun secret(prefs: Preferences, name: String): String = SettingsSecrets.read(securePrefs, prefs, name)

    /**
     * Segredos que builds de desenvolvimento gravaram em claro no DataStore ([SettingsSecrets.LEGACY_IN_DATASTORE]):
     * se o cofre ainda não tem o valor, copia para ele e **só depois** de o `commit()` confirmar remove a chave do
     * DataStore. Roda ao começar cada coleta de [settingsFlow] e em [rawPreferencesOnce]; o caminho comum, sem nada
     * em claro, só olha o DataStore e nem abre o cofre. Idempotente. Se o cofre falhar, o segredo fica onde está
     * (a próxima leitura tenta de novo) e a leitura não cai.
     */
    private suspend fun migrateLegacySecrets() {
        val current = dataStore.data.first()
        val present = SettingsSecrets.legacyPresentIn(current)
        if (present.isEmpty()) return
        val movable = try {
            SettingsSecrets.moveToVault(current, present, securePrefs)
        } catch (e: Exception) {
            Log.w(TAG, "Cofre indisponível; segredos antigos ficam no DataStore por enquanto", e)
            return
        }
        if (movable.isNotEmpty()) dataStore.edit { prefs -> movable.forEach { prefs.remove(it) } }
    }

    fun overlayPositionFlow(): Flow<OverlayPosition> = dataStore.data.map { prefs ->
        OverlayPosition(
            x = prefs[OVERLAY_X] ?: OverlayPosition().x,
            y = prefs[OVERLAY_Y] ?: OverlayPosition().y,
        )
    }

    suspend fun save(cfg: Config) {
        val committed = securePrefs.edit()
            .putString(SettingsSecrets.AUTH_HEADER_VALUE, cfg.authHeaderValue)
            .commit()
        check(committed) { "Unable to persist auth header value" }

        dataStore.edit { prefs ->
            prefs[ENDPOINT_URL] = cfg.endpointUrl
            prefs[AUTH_HEADER_NAME] = cfg.authHeaderName
            prefs[MODEL] = cfg.model
            prefs[RECORDING_MODE] = cfg.recordingMode.name
            if (cfg.language.isNullOrBlank()) {
                prefs.remove(LANGUAGE)
            } else {
                prefs[LANGUAGE] = cfg.language
            }
        }
    }

    suspend fun saveOverlayPosition(position: OverlayPosition) {
        dataStore.edit { prefs ->
            prefs[OVERLAY_X] = position.x
            prefs[OVERLAY_Y] = position.y
        }
    }

    private fun String?.toRecordingMode(): RecordingMode = this
        ?.let { runCatching { RecordingMode.valueOf(it) }.getOrNull() }
        ?: RecordingMode.TOGGLE

    private fun String?.toSource(): TranscriptionSource = this
        ?.let { runCatching { TranscriptionSource.valueOf(it) }.getOrNull() }
        ?: TranscriptionSource.CUSTOM

    /** Valor ausente ou desconhecido (versão futura, dado corrompido) cai no Local — nunca derruba a leitura. */
    private fun String?.toReasoningSource(): ReasoningSource = this
        ?.let { runCatching { ReasoningSource.valueOf(it) }.getOrNull() }
        ?: ReasoningSource.LOCAL

    /** Valor ausente ou desconhecido cai no Local — nunca derruba a leitura. */
    private fun String?.toVisionSource(): VisionSource = this
        ?.let { runCatching { VisionSource.valueOf(it) }.getOrNull() }
        ?: VisionSource.LOCAL

    /** Valor ausente ou desconhecido cai no Local — nunca derruba a leitura. */
    private fun String?.toFalaSource(): FalaSource = this
        ?.let { runCatching { FalaSource.valueOf(it) }.getOrNull() }
        ?: FalaSource.LOCAL

    private fun String?.toOverlaySide(): OverlaySide = this
        ?.let { runCatching { OverlaySide.valueOf(it) }.getOrNull() }
        ?: OverlaySide.RIGHT

    private fun String?.toShortcutAction(default: OrbShortcutAction): OrbShortcutAction = this
        ?.let { runCatching { OrbShortcutAction.valueOf(it) }.getOrNull() }
        ?: default

    private fun String?.toShortcutOverflow(): ShortcutOverflow = this
        ?.let { runCatching { ShortcutOverflow.valueOf(it) }.getOrNull() }
        ?: ShortcutOverflow.NEAREST_DOWN

    private companion object {
        const val DATASTORE_NAME = "keyflow_settings"
        const val SECURE_PREFS_NAME = "keyflow_secure"
        const val TAG = "SettingsRepository"
        val DATASTORES = mutableMapOf<String, DataStore<Preferences>>()

        val ENDPOINT_URL = stringPreferencesKey("endpoint_url")
        val AUTH_HEADER_NAME = stringPreferencesKey("auth_header_name")
        val MODEL = stringPreferencesKey("model")
        val LANGUAGE = stringPreferencesKey("language")
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")

        // T5 — novas chaves
        val SOURCE = stringPreferencesKey("transcription_source")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val OPENAI_LANGUAGE = stringPreferencesKey("openai_language")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val GROQ_LANGUAGE = stringPreferencesKey("groq_language")
        val LOCAL_MODEL_ID = stringPreferencesKey("local_model_id")
        val LOCAL_LANGUAGE = stringPreferencesKey("local_language")
        val REASONING_SOURCE = stringPreferencesKey("reasoning_source")
        val REASONING_LOCAL_MODEL_ID = stringPreferencesKey("reasoning_local_model_id")
        val REASONING_OPENAI_MODEL = stringPreferencesKey("reasoning_openai_model")
        val REASONING_ANTHROPIC_MODEL = stringPreferencesKey("reasoning_anthropic_model")
        val REASONING_GOOGLE_MODEL = stringPreferencesKey("reasoning_google_model")
        val VISION_SOURCE = stringPreferencesKey("vision_source")
        val VISION_LOCAL_MODEL_ID = stringPreferencesKey("vision_local_model_id")
        val VISION_TRANSLATE_TARGET = stringPreferencesKey("vision_translate_target")
        val VISION_OPENAI_MODEL = stringPreferencesKey("vision_openai_model")
        val VISION_GOOGLE_MODEL = stringPreferencesKey("vision_google_model")
        val VISION_ANTHROPIC_MODEL = stringPreferencesKey("vision_anthropic_model")
        val FALA_SOURCE = stringPreferencesKey("fala_source")
        val FALA_LOCAL_MODEL_ID = stringPreferencesKey("fala_local_model_id")
        val FALA_SPEED = floatPreferencesKey("fala_speed")
        val FALA_CLOUD_VOICE = stringPreferencesKey("fala_cloud_voice")
        val FALA_ELEVENLABS_VOICE = stringPreferencesKey("fala_elevenlabs_voice")
        val FALA_ELEVENLABS_MODEL = stringPreferencesKey("fala_elevenlabs_model")
        val FALA_GOOGLE_VOICE = stringPreferencesKey("fala_google_voice")
        val REASONING_CUSTOM_ENDPOINT = stringPreferencesKey("reasoning_custom_endpoint")
        val REASONING_CUSTOM_HEADER_NAME = stringPreferencesKey("reasoning_custom_header_name")
        val REASONING_CUSTOM_MODEL = stringPreferencesKey("reasoning_custom_model")
        val VISION_CUSTOM_ENDPOINT = stringPreferencesKey("vision_custom_endpoint")
        val VISION_CUSTOM_HEADER_NAME = stringPreferencesKey("vision_custom_header_name")
        val VISION_CUSTOM_MODEL = stringPreferencesKey("vision_custom_model")
        val FALA_CUSTOM_ENDPOINT = stringPreferencesKey("fala_custom_endpoint")
        val FALA_CUSTOM_HEADER_NAME = stringPreferencesKey("fala_custom_header_name")
        val FALA_CUSTOM_MODEL = stringPreferencesKey("fala_custom_model")
        val COMPUTER_MODE = stringPreferencesKey("computer_mode")
        val COMPUTER_SSH_HOST = stringPreferencesKey("computer_ssh_host")
        val COMPUTER_SSH_PORT = intPreferencesKey("computer_ssh_port")
        val COMPUTER_SSH_USER = stringPreferencesKey("computer_ssh_user")
        val COMPUTER_SSH_AUTH_TYPE = stringPreferencesKey("computer_ssh_auth_type")
        val CUSTOM_MODEL_MIRROR_URL = stringPreferencesKey("custom_model_mirror_url")
        val PROVIDER_ANTHROPIC_MODEL = stringPreferencesKey("provider_anthropic_model")
        val PROVIDER_ANTHROPIC_AUTH_MODE = stringPreferencesKey("provider_anthropic_auth_mode")
        val PROVIDER_OPENAI_MODEL = stringPreferencesKey("provider_openai_model")
        val PROVIDER_OPENAI_AUTH_MODE = stringPreferencesKey("provider_openai_auth_mode")
        val PROVIDER_GOOGLE_MODEL = stringPreferencesKey("provider_google_model")
        val PROVIDER_GOOGLE_AUTH_MODE = stringPreferencesKey("provider_google_auth_mode")
        val PROVIDER_ELEVENLABS_AUTH_MODE = stringPreferencesKey("provider_elevenlabs_auth_mode")
        val PROVIDER_GROQ_MODEL = stringPreferencesKey("provider_groq_model")
        val PROVIDER_GROK_MODEL = stringPreferencesKey("provider_grok_model")
        val OVERLAY_MODE_ENABLED = booleanPreferencesKey("overlay_mode_enabled")
        val KEYBOARD_MODE_ENABLED = booleanPreferencesKey("keyboard_mode_enabled")
        val OVERLAY_RECORDING_MODE = stringPreferencesKey("overlay_recording_mode")
        val KEYBOARD_AUTO_PASTE = booleanPreferencesKey("keyboard_auto_paste") // legado (migração)
        val AUTO_PASTE_IN_FIELD = booleanPreferencesKey("auto_paste_in_field")
        val AUTO_PASTE_ON_FOCUS = booleanPreferencesKey("auto_paste_on_focus")
        val OVERLAY_SIDE = stringPreferencesKey("overlay_side")
        val ORB_SIZE_PX = intPreferencesKey("orb_size_px")
        val TAP_BURST_WINDOW_MS = intPreferencesKey("tap_burst_window_ms")
        val SHORTCUT_1_TAP = stringPreferencesKey("shortcut_1_tap")
        val SHORTCUT_2_TAP = stringPreferencesKey("shortcut_2_tap")
        val SHORTCUT_3_TAP = stringPreferencesKey("shortcut_3_tap")
        val SHORTCUT_OVERFLOW = stringPreferencesKey("shortcut_overflow")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback") // legado (migração p/ vibration_intensity)
        val VIBRATION_INTENSITY = intPreferencesKey("vibration_intensity")
        val THEME_DARK = booleanPreferencesKey("theme_dark")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val TRANSCRIPTION_TIMEOUT_MS = intPreferencesKey("transcription_timeout_ms")
        val MAX_RECORDING_SEC = intPreferencesKey("max_recording_sec")
        val KEYBOARD_SCALE_PERCENT = intPreferencesKey("keyboard_scale_percent")
        val KEYBOARD_LANGUAGE = stringPreferencesKey("keyboard_language")
        val KEYBOARD_NUMBER_ROW_ENABLED = booleanPreferencesKey("keyboard_number_row_enabled")
        val PARTIAL_ON_PAUSE = booleanPreferencesKey("partial_on_pause")
        val PARTIAL_VIEW_EDIT = booleanPreferencesKey("partial_view_edit")
        val KEYBOARD_SHOW_WITH_HARD_KEYBOARD = booleanPreferencesKey("keyboard_show_with_hard_keyboard")
        val KEYBOARD_AUTO_CORRECT = booleanPreferencesKey("keyboard_auto_correct")
        val KEYBOARD_SKIN = stringPreferencesKey("keyboard_skin")
        val ORB_SKIN = stringPreferencesKey("orb_skin")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        // Trabalho 3 (shortcut-bars): barras personalizáveis + teclados de atalhos, como JSON.
        val BARS_CONFIG_JSON = stringPreferencesKey("bars_config_json")
        val SHORTCUT_GRIDS_JSON = stringPreferencesKey("shortcut_grids_json")

        fun createSecurePrefs(
            context: Context,
            name: String,
            keyAlias: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS,
        ): SharedPreferences = SecurePrefs.create(context, name, keyAlias)

        fun dataStoreFor(
            file: File,
            scope: CoroutineScope,
        ): DataStore<Preferences> = synchronized(DATASTORES) {
            DATASTORES.getOrPut(file.absolutePath) {
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = {
                        file.parentFile?.mkdirs()
                        file
                    },
                )
            }
        }
    }
}
