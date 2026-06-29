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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.keyflow.core.OverlaySide
import com.keyflow.core.RecordingMode
import com.keyflow.core.TranscriptionSource
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

    fun flow(): Flow<Config> = settingsFlow().map { settings ->
        when (val eff = settings.effectiveConfigFor()) {
            is EffectiveConfig.Ready -> eff.config
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
            overlayModeEnabled = prefs[OVERLAY_MODE_ENABLED] ?: false,
            keyboardModeEnabled = prefs[KEYBOARD_MODE_ENABLED] ?: false,
            overlayRecordingMode = prefs[OVERLAY_RECORDING_MODE].toRecordingMode(),
            keyboardRecordingMode = prefs[KEYBOARD_RECORDING_MODE].toRecordingMode(),
            // Migra o antigo KEYBOARD_AUTO_PASTE único para os dois flags (mesmo valor) quando os novos
            // ainda não existem.
            autoPasteInField = prefs[AUTO_PASTE_IN_FIELD] ?: prefs[KEYBOARD_AUTO_PASTE] ?: true,
            autoPasteOnFocus = prefs[AUTO_PASTE_ON_FOCUS] ?: prefs[KEYBOARD_AUTO_PASTE] ?: true,
            overlaySide = prefs[OVERLAY_SIDE].toOverlaySide(),
            orbSizePx = prefs[ORB_SIZE_PX] ?: 156,
            themeDark = prefs[THEME_DARK] ?: false,
            onboardingDone = prefs[ONBOARDING_DONE] ?: false,
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
            prefs[OVERLAY_MODE_ENABLED] = settings.overlayModeEnabled
            prefs[KEYBOARD_MODE_ENABLED] = settings.keyboardModeEnabled
            prefs[OVERLAY_RECORDING_MODE] = settings.overlayRecordingMode.name
            prefs[KEYBOARD_RECORDING_MODE] = settings.keyboardRecordingMode.name
            prefs[AUTO_PASTE_IN_FIELD] = settings.autoPasteInField
            prefs[AUTO_PASTE_ON_FOCUS] = settings.autoPasteOnFocus
            prefs[OVERLAY_SIDE] = settings.overlaySide.name
            prefs[ORB_SIZE_PX] = settings.orbSizePx
            prefs[THEME_DARK] = settings.themeDark
            prefs[ONBOARDING_DONE] = settings.onboardingDone
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
        val OVERLAY_MODE_ENABLED = booleanPreferencesKey("overlay_mode_enabled")
        val KEYBOARD_MODE_ENABLED = booleanPreferencesKey("keyboard_mode_enabled")
        val OVERLAY_RECORDING_MODE = stringPreferencesKey("overlay_recording_mode")
        val KEYBOARD_RECORDING_MODE = stringPreferencesKey("keyboard_recording_mode")
        val KEYBOARD_AUTO_PASTE = booleanPreferencesKey("keyboard_auto_paste") // legado (migração)
        val AUTO_PASTE_IN_FIELD = booleanPreferencesKey("auto_paste_in_field")
        val AUTO_PASTE_ON_FOCUS = booleanPreferencesKey("auto_paste_on_focus")
        val OVERLAY_SIDE = stringPreferencesKey("overlay_side")
        val ORB_SIZE_PX = intPreferencesKey("orb_size_px")
        val THEME_DARK = booleanPreferencesKey("theme_dark")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        const val OPENAI_TOKEN = "openai_token"
        const val GROQ_TOKEN = "groq_token"

        fun createSecurePrefs(context: Context, name: String): SharedPreferences {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return context.getSharedPreferences(name, Context.MODE_PRIVATE)
            }

            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

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
