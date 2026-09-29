package com.keyflow.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.security.crypto.MasterKey
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.core.ShortcutOverflow
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.shortcuts.BarsConfig
import com.keyflow.core.shortcuts.ShortcutCodec
import com.keyflow.core.shortcuts.ShortcutGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    suspend fun selectLocalModelIfNone(modelId: String): Boolean {
        var selected = false
        dataStore.edit { prefs ->
            if (prefs[LOCAL_MODEL_ID].isNullOrBlank()) {
                prefs[LOCAL_MODEL_ID] = modelId
                selected = true
            }
        }
        return selected
    }

    /**
     * O DataStore inteiro, chave por chave, como está gravado. Existe para o gate de instrumentados
     * guardar a config de quem é dono do aparelho antes de rodar a suíte no estado de fábrica
     * (`FactorySettingsListener`, androidTest) — nenhuma tela do app usa isto.
     */
    internal suspend fun rawPreferencesOnce(): Preferences = dataStore.data.first()

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
     * tokens de preset vêm do armazenamento criptografado e são preservados entre trocas de origem.
     */
    fun settingsFlow(): Flow<Settings> = dataStore.data.map { prefs ->
        Settings(
            source = prefs[SOURCE].toSource(),
            customEndpoint = prefs[ENDPOINT_URL].orEmpty(),
            customHeaderName = prefs[AUTH_HEADER_NAME] ?: "Authorization",
            customHeaderValue = securePrefs.getString(AUTH_HEADER_VALUE, "").orEmpty(),
            customModel = prefs[MODEL] ?: "whisper-1",
            customLanguage = prefs[LANGUAGE]?.takeIf { it.isNotBlank() },
            openaiToken = securePrefs.getString(OPENAI_TOKEN, "").orEmpty(),
            openaiModel = prefs[OPENAI_MODEL] ?: Settings.OPENAI_MODEL,
            openaiLanguage = prefs[OPENAI_LANGUAGE]?.takeIf { it.isNotBlank() },
            groqToken = securePrefs.getString(GROQ_TOKEN, "").orEmpty(),
            groqModel = prefs[GROQ_MODEL] ?: Settings.GROQ_MODEL,
            groqLanguage = prefs[GROQ_LANGUAGE]?.takeIf { it.isNotBlank() },
            localModelId = prefs[LOCAL_MODEL_ID]?.takeIf { it.isNotBlank() },
            localLanguage = prefs[LOCAL_LANGUAGE]?.takeIf { it.isNotBlank() },
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
            appLanguage = prefs[APP_LANGUAGE] ?: "",
        )
    }

    /**
     * Persiste o estado completo (T5). Tokens criptografados; `null` em texto remove a chave do DataStore.
     * Não apaga credenciais de outros provedores — todos os 3 tokens são reescritos a partir de [settings].
     */
    suspend fun saveSettings(settings: Settings) {
        val committed = securePrefs.edit()
            .putString(AUTH_HEADER_VALUE, settings.customHeaderValue)
            .putString(OPENAI_TOKEN, settings.openaiToken)
            .putString(GROQ_TOKEN, settings.groqToken)
            .commit()
        check(committed) { "Unable to persist provider tokens" }

        dataStore.edit { prefs ->
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
            prefs[APP_LANGUAGE] = settings.appLanguage
        }
    }

    private fun String?.putOrRemove(prefs: MutablePreferences, key: Preferences.Key<String>) {
        if (this.isNullOrBlank()) prefs.remove(key) else prefs[key] = this
    }

    fun overlayPositionFlow(): Flow<OverlayPosition> = dataStore.data.map { prefs ->
        OverlayPosition(
            x = prefs[OVERLAY_X] ?: OverlayPosition().x,
            y = prefs[OVERLAY_Y] ?: OverlayPosition().y,
        )
    }

    suspend fun save(cfg: Config) {
        val committed = securePrefs.edit()
            .putString(AUTH_HEADER_VALUE, cfg.authHeaderValue)
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
        val DATASTORES = mutableMapOf<String, DataStore<Preferences>>()

        val ENDPOINT_URL = stringPreferencesKey("endpoint_url")
        val AUTH_HEADER_NAME = stringPreferencesKey("auth_header_name")
        val MODEL = stringPreferencesKey("model")
        val LANGUAGE = stringPreferencesKey("language")
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        const val AUTH_HEADER_VALUE = "auth_header_value"

        // T5 — novas chaves
        val SOURCE = stringPreferencesKey("transcription_source")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val OPENAI_LANGUAGE = stringPreferencesKey("openai_language")
        val GROQ_MODEL = stringPreferencesKey("groq_model")
        val GROQ_LANGUAGE = stringPreferencesKey("groq_language")
        val LOCAL_MODEL_ID = stringPreferencesKey("local_model_id")
        val LOCAL_LANGUAGE = stringPreferencesKey("local_language")
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
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        // Trabalho 3 (shortcut-bars): barras personalizáveis + teclados de atalhos, como JSON.
        val BARS_CONFIG_JSON = stringPreferencesKey("bars_config_json")
        val SHORTCUT_GRIDS_JSON = stringPreferencesKey("shortcut_grids_json")
        const val OPENAI_TOKEN = "openai_token"
        const val GROQ_TOKEN = "groq_token"

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
