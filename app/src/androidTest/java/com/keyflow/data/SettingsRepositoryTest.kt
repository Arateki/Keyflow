package com.keyflow.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.OverlaySide
import com.keyflow.core.FalaSource
import com.keyflow.core.ReasoningSource
import com.keyflow.core.RecordingMode
import com.keyflow.core.TranscriptionSource
import com.keyflow.core.VisionSource
import com.keyflow.auth.CredentialStore
import com.keyflow.auth.SecureCredentialStore
import com.keyflow.data.models.ModelKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var dataStoreFile: File
    private lateinit var securePrefsName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val id = UUID.randomUUID().toString()
        dataStoreFile = File(context.filesDir, "test-datastore/$id.preferences_pb")
        securePrefsName = "test_keyflow_secure_$id"
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
        dataStoreFile.parentFile?.delete()
        context.deleteSharedPreferences(securePrefsName)
        context.deleteSharedPreferences("${SecureCredentialStore.PREFS_NAME}_$securePrefsName")
    }

    private fun newRepository() = SettingsRepository(
        context = context,
        dataStoreFile = dataStoreFile,
        securePrefsName = securePrefsName,
    )

    @Test
    fun saveThenReadPreservesConfig() = runTest {
        val repository = SettingsRepository(
            context = context,
            dataStoreFile = dataStoreFile,
            securePrefsName = securePrefsName,
        )
        // recordingMode não entra aqui: desde o T5, Settings.effectiveConfigFor() sempre constrói o
        // Config com o default (TOGGLE) — o campo virou vestigial nesta API legada (substituída por
        // Settings.overlayRecordingMode, coberto em saveThenReadSettingsPreservesAllFields).
        val saved = Config(
            endpointUrl = "https://example.test/v1/audio/transcriptions",
            authHeaderName = "X-Api-Key",
            authHeaderValue = "Bearer keyflow-secret",
            model = "whisper-large-v3",
            language = "pt",
        )

        repository.save(saved)

        assertEquals(saved, repository.flow().first())
    }

    @Test
    fun authHeaderValueIsNotStoredAsPlaintext() = runTest {
        val repository = SettingsRepository(
            context = context,
            dataStoreFile = dataStoreFile,
            securePrefsName = securePrefsName,
        )
        val secret = "Bearer plain-text-must-not-appear"

        repository.save(
            Config(
                endpointUrl = "https://example.test/v1/audio/transcriptions",
                authHeaderValue = secret,
            )
        )

        val dataStoreBytes = dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)
        val securePrefsFile = File(
            context.applicationInfo.dataDir,
            "shared_prefs/${SecureCredentialStore.PREFS_NAME}_$securePrefsName.xml",
        )
        val securePrefsXml = securePrefsFile.readText()

        assertFalse(dataStoreBytes.contains(secret))
        assertFalse(securePrefsXml.contains(secret))
    }

    @Test
    fun settingsFlowReadsTheSecurePrefsOffTheCollectorThread() = runTest {
        val reads = CopyOnWriteArrayList<Thread>()
        val delegate = SecureCredentialStore(context, "${SecureCredentialStore.PREFS_NAME}_$securePrefsName")
        val recordingStore = object : CredentialStore by delegate {
            override fun getSecret(key: String): String? {
                reads += Thread.currentThread()
                return delegate.getSecret(key)
            }
        }
        val repository = SettingsRepository(
            context = context,
            dataStoreFile = dataStoreFile,
            securePrefsName = securePrefsName,
            credentialStoreFactory = { _, _ -> recordingStore },
        )
        val collector = Thread.currentThread()

        repository.settingsFlow().first()

        assertFalse("o cofre deve ter sido lido", reads.isEmpty())
        assertFalse("settingsFlow leu o cofre na thread de quem coleta", reads.any { it === collector })
    }

    @Test
    fun saveThenReadSettingsPreservesAllFields() = runTest {
        val repository = newRepository()
        val saved = Settings(
            source = TranscriptionSource.OPENAI,
            customEndpoint = "https://self.host/v1/audio/transcriptions",
            customHeaderName = "X-Api-Key",
            customHeaderValue = "custom-secret",
            customModel = "whisper-large-v3",
            customLanguage = "es",
            openaiModel = "whisper-1",
            openaiLanguage = "pt",
            groqModel = "whisper-large-v3",
            groqLanguage = "en",
            localModelId = "whisper-base",
            localLanguage = "pt",
            reasoningSource = ReasoningSource.OPENAI,
            reasoningLocalModelId = "qwen3-0.6b-litertlm",
            reasoningOpenAiModel = "algum-modelo",
            overlayRecordingMode = RecordingMode.PUSH_TO_TALK,
            overlaySide = OverlaySide.LEFT,
            orbSizePx = 250,
            themeDark = true,
            onboardingDone = true,
            transcriptionTimeoutMs = 45_000,
            maxRecordingSec = 600,
            keyboardScalePercent = 85,
            keyboardLanguage = "es",
        )

        repository.saveSettings(saved)
        repository.saveSecret(SettingsCredentialKeys.OPENAI_TOKEN, "sk-openai")
        repository.saveSecret(SettingsCredentialKeys.GROQ_TOKEN, "gsk-groq")

        assertEquals(saved, repository.settingsFlow().first())
        assertEquals("Bearer sk-openai", repository.flow().first().authHeaderValue)
    }

    /** RSC-01/02: o Raciocínio guarda a própria origem e o próprio modelo, sem tocar nos da Transcrição. */
    @Test
    fun reasoningFieldsAreIndependentOfTheTranscriptionOnes() = runTest {
        val repository = newRepository()

        repository.saveSettings(
            Settings(
                source = TranscriptionSource.OPENAI,
                localModelId = "whisper-base-int8",
                reasoningSource = ReasoningSource.LOCAL,
                reasoningLocalModelId = "gemma-4-e2b-litertlm",
            ),
        )

        val read = repository.settingsFlow().first()
        assertEquals(TranscriptionSource.OPENAI, read.source)
        assertEquals("whisper-base-int8", read.localModelId)
        assertEquals(ReasoningSource.LOCAL, read.reasoningSource)
        assertEquals("gemma-4-e2b-litertlm", read.reasoningLocalModelId)
    }

    /** Sem nada salvo, o Raciocínio abre no Local, sem modelo — e limpar o modelo volta a nulo, não a "". */
    @Test
    fun reasoningDefaultsAndClearingTheModelReadBackAsNull() = runTest {
        val repository = newRepository()
        val fresh = repository.settingsFlow().first()
        assertEquals(ReasoningSource.LOCAL, fresh.reasoningSource)
        assertEquals(null, fresh.reasoningLocalModelId)
        assertEquals("", fresh.reasoningOpenAiModel)

        repository.saveSettings(fresh.copy(reasoningLocalModelId = "qwen3-0.6b-litertlm"))
        repository.saveSettings(repository.settingsFlow().first().copy(reasoningLocalModelId = null))
        assertEquals(null, repository.settingsFlow().first().reasoningLocalModelId)
    }

    /** Valor gravado de uma versão futura/corrompida não derruba a leitura: cai no Local. */
    @Test
    fun unknownReasoningSourceFallsBackToLocal() = runTest {
        val repository = newRepository()
        repository.saveSettings(Settings(reasoningSource = ReasoningSource.OPENAI))
        val corrupted = repository.rawPreferencesOnce().toMutablePreferences().also {
            it[stringPreferencesKey("reasoning_source")] = "GEMINI"
        }
        repository.replaceRawPreferences(corrupted)
        assertEquals(ReasoningSource.LOCAL, repository.settingsFlow().first().reasoningSource)
    }

    @Test
    fun persistedCreditsSourcesFallBackToCapabilityDefaults() = runTest {
        val repository = newRepository()
        repository.saveSettings(Settings())
        val legacyCredits = repository.rawPreferencesOnce().toMutablePreferences().also {
            it[stringPreferencesKey("transcription_source")] = "CREDITS"
            it[stringPreferencesKey("reasoning_source")] = "CREDITS"
            it[stringPreferencesKey("vision_source")] = "CREDITS"
            it[stringPreferencesKey("fala_source")] = "CREDITS"
        }
        repository.replaceRawPreferences(legacyCredits)

        val restored = repository.settingsFlow().first()
        assertEquals(TranscriptionSource.CUSTOM, restored.source)
        assertEquals(ReasoningSource.LOCAL, restored.reasoningSource)
        assertEquals(VisionSource.LOCAL, restored.visionSource)
        assertEquals(FalaSource.LOCAL, restored.falaSource)
    }

    /** MGR-03 e o análogo do Raciocínio: quando um modelo instala e nenhum daquele tipo está escolhido, ele é escolhido. */
    @Test
    fun installingATextModelSelectsItForReasoningAndNeverAsTheSpeechModel() = runTest {
        val repository = newRepository()

        assertEquals(true, repository.selectInstalledModelIfNone("qwen3-0.6b-litertlm", ModelKind.TEXT))

        val read = repository.settingsFlow().first()
        assertEquals("qwen3-0.6b-litertlm", read.reasoningLocalModelId)
        assertEquals(null, read.localModelId) // o modelo de texto NUNCA vira o modelo de transcrição
    }

    @Test
    fun installingASpeechModelSelectsItForTranscriptionOnly() = runTest {
        val repository = newRepository()

        assertEquals(true, repository.selectInstalledModelIfNone("whisper-base-int8", ModelKind.SPEECH))

        val read = repository.settingsFlow().first()
        assertEquals("whisper-base-int8", read.localModelId)
        assertEquals(null, read.reasoningLocalModelId)
    }

    @Test
    fun anExistingChoiceOfTheSameKindIsNeverOverwrittenByAnInstall() = runTest {
        val repository = newRepository()
        repository.saveSettings(Settings(reasoningLocalModelId = "gemma-4-e2b-litertlm", localModelId = "whisper-small-int8"))

        assertEquals(false, repository.selectInstalledModelIfNone("qwen3-0.6b-litertlm", ModelKind.TEXT))
        assertEquals(false, repository.selectInstalledModelIfNone("whisper-base-int8", ModelKind.SPEECH))

        val read = repository.settingsFlow().first()
        assertEquals("gemma-4-e2b-litertlm", read.reasoningLocalModelId)
        assertEquals("whisper-small-int8", read.localModelId)
    }

    /** O gancho do downloader escolhe o tipo pelo catálogo; id que o catálogo não conhece não seleciona nada. */
    @Test
    fun selectOnInstallRoutesByTheCatalogKindAndIgnoresUnknownIds() = runTest {
        val repository = newRepository()

        repository.selectOnInstall("qwen3-0.6b-litertlm")
        repository.selectOnInstall("id-que-nao-existe")

        val read = repository.settingsFlow().first()
        assertEquals("qwen3-0.6b-litertlm", read.reasoningLocalModelId)
        assertEquals(null, read.localModelId)
    }

    /** NM-T2: sem modelo escolhido, os campos da origem Local voltam nulos — não string vazia. */
    @Test
    fun localSourceWithoutModelRoundTripsAsNull() = runTest {
        val repository = newRepository()

        repository.saveSettings(Settings(source = TranscriptionSource.LOCAL, localModelId = "whisper-base"))
        repository.saveSettings(repository.settingsFlow().first().copy(localModelId = null))

        val read = repository.settingsFlow().first()
        assertEquals(TranscriptionSource.LOCAL, read.source)
        assertEquals(null, read.localModelId)
        assertEquals(null, read.localLanguage)
    }

    /**
     * NM-T9 / PRV-01: com a origem Local, o Config do fluxo de gravação aponta para o engine local e não
     * carrega endpoint nem token — mesmo com tokens de provedores salvos. Espera generosa.
     */
    @Test
    fun localSourceConfigCarriesTheModelAndNoNetworkCredentials() = runTest {
        val repository = newRepository()
        repository.saveSettings(
            Settings(
                source = TranscriptionSource.LOCAL,
                localModelId = "whisper-base-int8",
                localLanguage = "pt",
                customEndpoint = "https://example.test",
                customHeaderValue = "Bearer guardado",
            ),
        )
        repository.saveSecret(SettingsCredentialKeys.OPENAI_TOKEN, "sk-guardado")

        val cfg = repository.flow().first()

        assertEquals(EffectiveConfig.Local("whisper-base-int8", "pt"), cfg.local)
        assertEquals("", cfg.endpointUrl)
        assertEquals("", cfg.authHeaderValue)
        assertFalse(cfg.isComplete())
        assertEquals(Config.LOCAL_MIN_TIMEOUT_MS, cfg.timeoutMs)
    }

    /** PAR-01: os toggles do parcial persistem e o padrão é desligado. */
    @Test
    fun partialTogglesPersistAndDefaultOff() = runTest {
        val repository = newRepository()
        assertEquals(false, repository.settingsFlow().first().partialOnPause)
        assertEquals(false, repository.settingsFlow().first().partialViewEdit)

        repository.saveSettings(Settings(partialOnPause = true, partialViewEdit = true))

        val read = repository.settingsFlow().first()
        assertEquals(true, read.partialOnPause)
        assertEquals(true, read.partialViewEdit)
    }

    /** MGR-03: instalou e não havia seleção → seleciona; havia → não troca; não mexe no resto. */
    @Test
    fun selectLocalModelIfNoneOnlyFillsAnEmptySelection() = runTest {
        val repository = newRepository()
        repository.saveSettings(Settings(source = TranscriptionSource.LOCAL, localLanguage = "pt"))
        repository.saveSecret(SettingsCredentialKeys.OPENAI_TOKEN, "sk-x")

        assertEquals(true, repository.selectLocalModelIfNone("whisper-base-int8"))
        assertEquals(false, repository.selectLocalModelIfNone("whisper-small-int8"))

        val read = repository.settingsFlow().first()
        assertEquals("whisper-base-int8", read.localModelId)
        assertEquals("pt", read.localLanguage)
        assertEquals("", repository.flow().first().authHeaderValue)
    }

    @Test
    fun transcriptionTimeoutMsDefaultsTo15SecondsWhenNeverSaved() = runTest {
        val repository = newRepository()
        assertEquals(15_000, repository.settingsFlow().first().transcriptionTimeoutMs)
    }

    @Test
    fun maxRecordingSecDefaultsTo5MinutesWhenNeverSaved() = runTest {
        val repository = newRepository()
        assertEquals(300, repository.settingsFlow().first().maxRecordingSec)
    }

    @Test
    fun keyboardScalePercentDefaultsTo100WhenNeverSaved() = runTest {
        val repository = newRepository()
        assertEquals(100, repository.settingsFlow().first().keyboardScalePercent)
        assertEquals(100, repository.keyboardScalePercentOnce())
    }

    /**
     * Quem já tem o app instalado não passou por nenhuma tela nova: precisa receber a flag LIGADA sem
     * tocar em nada, senão o teclado continua sumindo com teclado físico conectado. O default mora em
     * dois lugares (o `Settings` e o `?:` da leitura) e é aí que ele costuma divergir.
     */
    @Test
    fun showWithHardKeyboardDefaultsToOnWhenNeverSaved() = runTest {
        val repository = newRepository()
        assertEquals(true, repository.settingsFlow().first().keyboardShowWithHardKeyboard)
        assertEquals(true, repository.keyboardShowWithHardKeyboardFlow().first())
    }

    @Test
    fun keyboardScalePercentOnceReadsWithoutTouchingSecurePrefs() = runTest {
        val repository = newRepository()
        repository.saveSettings(Settings(keyboardScalePercent = 85))

        assertEquals(85, repository.keyboardScalePercentOnce())
    }

    @Test
    fun keyboardLanguageDefaultsToPtWhenNeverSaved() = runTest {
        val repository = newRepository()
        assertEquals("pt", repository.settingsFlow().first().keyboardLanguage)
        assertEquals("pt", repository.keyboardLanguageOnce())
    }

    @Test
    fun saveKeyboardLanguageOnceWritesWithoutTouchingTheRestOfTheSettings() = runTest {
        val repository = newRepository()
        repository.saveSettings(Settings(themeDark = true, orbSizePx = 250))

        repository.saveKeyboardLanguageOnce("es")

        val settings = repository.settingsFlow().first()
        assertEquals("es", settings.keyboardLanguage)
        assertEquals("es", repository.keyboardLanguageOnce())
        // O resto da config não foi mexido por uma escrita leve (mesmo padrão de tapBurstWindowMs etc.).
        assertEquals(true, settings.themeDark)
        assertEquals(250, settings.orbSizePx)
    }

    @Test
    fun saveModeFlagsWritesOnlyTheRequestedFlag() = runTest {
        val repository = newRepository()
        repository.saveSettings(Settings(themeDark = true, orbSizePx = 250))

        repository.saveOverlayModeEnabled(true)
        repository.saveKeyboardModeEnabled(true)

        val settings = repository.settingsFlow().first()
        assertEquals(true, settings.overlayModeEnabled)
        assertEquals(true, settings.keyboardModeEnabled)
        assertEquals(true, settings.themeDark)
        assertEquals(250, settings.orbSizePx)
    }

    @Test
    fun saveOnboardingDoneDoesNotOverwriteModeFlags() = runTest {
        val repository = newRepository()
        repository.saveSettings(Settings(overlayModeEnabled = true, keyboardModeEnabled = true))

        repository.saveOnboardingDone(true)

        val settings = repository.settingsFlow().first()
        assertEquals(true, settings.onboardingDone)
        assertEquals(true, settings.overlayModeEnabled)
        assertEquals(true, settings.keyboardModeEnabled)
    }

    @Test
    fun themeDarkScaleAndLanguageFlowsReflectLiveChanges() = runTest {
        // Pedido do Yan, 2026-07-03: o teclado aberto precisa reagir sem precisar reabrir — a base disso
        // é o Flow realmente emitir de novo quando o valor muda (mesmo padrão de barsConfigFlow/BAR-07).
        val repository = newRepository()
        assertEquals(false, repository.themeDarkFlow().first())
        assertEquals(100, repository.keyboardScalePercentFlow().first())
        assertEquals("pt", repository.keyboardLanguageFlow().first())

        repository.saveSettings(Settings(themeDark = true, keyboardScalePercent = 85))
        repository.saveKeyboardLanguageOnce("en")

        assertEquals(true, repository.themeDarkFlow().first())
        assertEquals(85, repository.keyboardScalePercentFlow().first())
        assertEquals("en", repository.keyboardLanguageFlow().first())
    }

    @Test
    fun switchingPresetPreservesEveryProviderToken() = runTest {
        val repository = newRepository()
        // Usuário configurou os 3 provedores e está com a origem OpenAI selecionada.
        repository.saveSettings(
            Settings(
                source = TranscriptionSource.OPENAI,
                customHeaderValue = "custom-key",
            )
        )
        repository.saveSecret(SettingsCredentialKeys.OPENAI_TOKEN, "sk-openai")
        repository.saveSecret(SettingsCredentialKeys.GROQ_TOKEN, "gsk-groq")

        val restored = repository.settingsFlow().first()
        assertEquals("custom-key", restored.customHeaderValue)
        assertEquals("Bearer sk-openai", repository.flow().first().authHeaderValue)
        repository.saveSettings(restored.copy(source = TranscriptionSource.GROQ))
        assertEquals("Bearer gsk-groq", repository.flow().first().authHeaderValue)
    }

    @Test
    fun providerTokensAreNotStoredAsPlaintext() = runTest {
        val repository = newRepository()
        val openai = "sk-openai-must-not-appear"
        val groq = "gsk-groq-must-not-appear"

        repository.saveSecret(SettingsCredentialKeys.OPENAI_TOKEN, openai)
        repository.saveSecret(SettingsCredentialKeys.GROQ_TOKEN, groq)

        val dataStoreBytes = if (dataStoreFile.exists()) dataStoreFile.readBytes().toString(Charsets.ISO_8859_1) else ""
        val securePrefsXml = File(
            context.applicationInfo.dataDir,
            "shared_prefs/${SecureCredentialStore.PREFS_NAME}_$securePrefsName.xml",
        ).readText()
        assertFalse(dataStoreBytes.contains(openai))
        assertFalse(dataStoreBytes.contains(groq))
        assertFalse(securePrefsXml.contains(openai))
        assertFalse(securePrefsXml.contains(groq))
    }

    @Test
    fun saveThenReadOverlayPositionPreservesCoordinates() = runTest {
        val repository = SettingsRepository(
            context = context,
            dataStoreFile = dataStoreFile,
            securePrefsName = securePrefsName,
        )
        val saved = OverlayPosition(x = 42, y = 99)

        repository.saveOverlayPosition(saved)

        assertEquals(saved, repository.overlayPositionFlow().first())
    }

    /**
     * O teclado lê tudo de UMA vez ao ser criado (`onCreateInputView`, thread principal): eram oito idas ao DataStore, até
     * 400 ms no emulador lento (esteira de instabilidade, 2026-10-10). O retrato tem que dizer exatamente o que as leituras
     * avulsas dizem — gravado e no padrão.
     */
    @Test
    fun keyboardSnapshotMatchesTheSingleReads() = runTest {
        val repository = newRepository()
        repository.saveSettings(
            Settings(themeDark = true, keyboardScalePercent = 85, keyboardNumberRowEnabled = true, keyboardSkin = "glitch", vibrationIntensity = 30),
        )
        repository.saveKeyboardLanguageOnce("en")

        val snapshot = repository.keyboardSnapshotOnce()

        assertEquals(repository.themeDarkOnce(), snapshot.dark)
        assertEquals(repository.keyboardScalePercentOnce(), snapshot.scalePercent)
        assertEquals(repository.keyboardLanguageOnce(), snapshot.language)
        assertEquals(repository.keyboardNumberRowEnabledOnce(), snapshot.numberRowEnabled)
        assertEquals(repository.keyboardSkinOnce(), snapshot.skin)
        assertEquals(repository.barsConfigOnce(), snapshot.bars)
        assertEquals(repository.shortcutGridsOnce(), snapshot.grids)
        assertEquals(repository.vibrationIntensityOnce(), snapshot.vibration)
        assertEquals(KeyboardSnapshot(true, 85, "en", true, "glitch", snapshot.bars, snapshot.grids, 30), snapshot)
    }

    @Test
    fun keyboardSnapshotUsesTheSameDefaultsAsTheSingleReads() = runTest {
        val repository = newRepository()

        val snapshot = repository.keyboardSnapshotOnce()

        assertEquals(
            KeyboardSnapshot(
                dark = repository.themeDarkOnce(),
                scalePercent = repository.keyboardScalePercentOnce(),
                language = repository.keyboardLanguageOnce(),
                numberRowEnabled = repository.keyboardNumberRowEnabledOnce(),
                skin = repository.keyboardSkinOnce(),
                bars = repository.barsConfigOnce(),
                grids = repository.shortcutGridsOnce(),
                vibration = repository.vibrationIntensityOnce(),
            ),
            snapshot,
        )
    }
}
