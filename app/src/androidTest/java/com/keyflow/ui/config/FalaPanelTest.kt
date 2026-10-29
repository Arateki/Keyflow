package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.FalaSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.speech.SpeechEngine
import com.keyflow.speech.SpeechError
import com.keyflow.speech.SpeechEvent
import com.keyflow.speech.SpeechPlayer
import com.keyflow.speech.SpeechRequest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import com.keyflow.ui.gallery.SamplePanelModels
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FalaPanelTest {

    @get:Rule
    val rule = createComposeRule()

    // O catálogo real de Voz está vazio (2026-10-24): o painel é exercitado com modelos de amostra.
    private val piper = SamplePanelModels.VOICE_LIGHT
    private val kokoro = SamplePanelModels.VOICE_BALANCED
    private val melo = SamplePanelModels.VOICE_HEAVY
    private val whisper = ModelCatalog.WHISPER_BASE
    private val qwen = ModelCatalog.QWEN3_0_6B
    private val ocr = SamplePanelModels.VISION_LIGHT

    private class FakeActions : LocalModelActions {
        val deletes = mutableListOf<String>()
        override suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult = StartResult.Started
        override fun cancel(modelId: String) = Unit
        override fun delete(modelId: String) { deletes += modelId }
    }

    private inner class Harness(
        initial: Settings,
        val models: LocalModelsUi,
        val actions: FakeActions = FakeActions(),
        val tester: SpeechTester = SpeechTester.None,
        val openAiAuth: AuthActions = AuthActions.None,
        val googleAuth: AuthActions = AuthActions.None,
        val elevenLabsAuth: AuthActions = AuthActions.None,
    ) {
        var settings by mutableStateOf(initial)
        var localSupported by mutableStateOf(true)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                FalaPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    models = models,
                    actions = actions,
                    localSupported = localSupported,
                    tester = tester,
                    openAiAuth = openAiAuth,
                    googleAuth = googleAuth,
                    elevenLabsAuth = elevenLabsAuth,
                )
            }
        }
    }

    private fun voiceUi(installed: Set<String> = emptySet(), selected: String? = null) = LocalModelsUi.compose(
        catalog = SamplePanelModels.VOICE, installed = installed, active = null, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_000,
    )

    @Test
    fun aDeviceWithoutTheLocalEngineShowsNoDownloadsAndSaysWhy() {
        val h = Harness(Settings(), voiceUi())
        h.localSupported = false
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_LOCAL_UNSUPPORTED_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fala-local-model-${piper.id}").assertDoesNotExist()
        rule.onNodeWithTag("fala-local-model-${kokoro.id}").assertDoesNotExist()
        rule.onNodeWithTag(FALA_LOCAL_PRIVACY_TAG).assertDoesNotExist()
    }

    @Test
    fun opensOnTheLocalSourceWithTheVoiceModelsListed() {
        val h = Harness(Settings(), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(falaSourceTag(FalaSource.LOCAL)).assertIsDisplayed()
        rule.onNodeWithTag("fala-local-model-${piper.id}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fala-local-model-${kokoro.id}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(FALA_LOCAL_PRIVACY_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(FALA_SPEED_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theVoiceListNeverShowsSpeechTextOrVisionModels() {
        val h = Harness(Settings(), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag("fala-local-model-${whisper.id}").assertDoesNotExist()
        rule.onNodeWithTag("fala-local-model-${qwen.id}").assertDoesNotExist()
        rule.onNodeWithTag("fala-local-model-${ocr.id}").assertDoesNotExist()
        rule.onNodeWithTag("visao-local-model-${piper.id}").assertDoesNotExist()
        rule.onNodeWithTag("raciocinio-local-model-${piper.id}").assertDoesNotExist()
        rule.onNodeWithTag("voz-local-model-${piper.id}").assertDoesNotExist()
    }

    @Test
    fun selectingAnInstalledVoiceModelUpdatesSettings() {
        val h = Harness(Settings(), voiceUi(installed = setOf(piper.id)))
        rule.setContent { h.Content() }

        rule.onNodeWithTag("fala-local-model-${piper.id}").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(piper.id, h.settings.falaLocalModelId) }
    }

    @Test
    fun deletingTheSelectedVoiceModelClearsSelection() {
        val h = Harness(Settings(falaLocalModelId = piper.id), voiceUi(installed = setOf(piper.id), selected = piper.id))
        rule.setContent { h.Content() }

        rule.onNodeWithTag("fala-local-delete-${piper.id}").performScrollTo().performClick()
        rule.onNodeWithTag("fala-local-confirm-yes").performClick()
        rule.runOnIdle {
            assertEquals(listOf(piper.id), h.actions.deletes)
            assertNull(h.settings.falaLocalModelId)
        }
    }

    @Test
    fun switchingSourceToOpenAiShowsCloudInfoAndVoiceSelector() {
        val h = Harness(Settings(), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(falaSourceTag(FalaSource.OPENAI)).performClick()
        rule.runOnIdle { assertEquals(FalaSource.OPENAI, h.settings.falaSource) }
        rule.onNodeWithTag(FALA_OPENAI_INFO_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(FALA_CLOUD_VOICE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun testAreaRequiresInstalledModelOnLocal() {
        val h = Harness(Settings(), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(FALA_TEST_REASON_TAG).performScrollTo().assertIsDisplayed()
    }

    // ── FAL-T4: Créditos, Sistema, OpenAI, Custom e o Testar de verdade ─────────────────────────────────────────────────────────

    private fun keyed(key: String): AuthActions = object : AuthActions by AuthActions.None {
        override fun apiKeyFlow(): Flow<String?> = flowOf(key)
    }

    /** Anota as Settings de cada motor pedido, o que foi falado e o que tocou. */
    private class Recorder(val script: (SpeechRequest) -> Flow<SpeechEvent>) {
        val engineSettings = mutableListOf<Settings>()
        val requests = mutableListOf<SpeechRequest>()
        val played = mutableListOf<Int>()
        var stopped = 0
        val tester = SpeechTester(
            engineFor = { s ->
                engineSettings += s
                object : SpeechEngine {
                    override fun speak(request: SpeechRequest): Flow<SpeechEvent> { requests += request; return script(request) }
                }
            },
            newPlayer = {
                object : SpeechPlayer {
                    override suspend fun write(pcm: ByteArray, sampleRate: Int) { played += pcm.size }
                    override suspend fun finish() = Unit
                    override fun stop() { stopped++ }
                }
            },
            installTtsData = { },
        )
    }

    private val twoBuffers = { _: SpeechRequest ->
        flowOf<SpeechEvent>(
            SpeechEvent.Audio(ByteArray(4800), 24_000), SpeechEvent.FirstAudio(120),
            SpeechEvent.Audio(ByteArray(4800), 24_000), SpeechEvent.Done(2500, 120),
        )
    }

    @Test
    fun creditsIsDisabledAndNotSelectable() {
        val h = Harness(Settings(), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(falaSourceTag(FalaSource.CREDITS)).performClick()
        rule.runOnIdle { assertEquals(FalaSource.LOCAL, h.settings.falaSource) }
    }

    @Test
    fun systemSourceShowsThePrivacyNoteAndTheDefaultVoice() {
        val h = Harness(Settings(falaSource = FalaSource.SYSTEM), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_SYSTEM_NOTE_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(FALA_SYSTEM_VOICE_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun openAiShowsTheShutdownNoticeAndNoInventedUsageBars() {
        val h = Harness(Settings(falaSource = FalaSource.OPENAI), voiceUi(), openAiAuth = keyed("sk-falso"))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_OPENAI_SHUTDOWN_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Reseta às 15:30").assertDoesNotExist()
        rule.onNodeWithText("Reseta qua, 14:00").assertDoesNotExist()
    }

    @Test
    fun customShowsVoiceAndFormatAndSavesThem() {
        val h = Harness(Settings(falaSource = FalaSource.CUSTOM), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag("fala-custom-endpoint").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fala-custom-voice").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fala-custom-format").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(FALA_CUSTOM_PRIVACY_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fala-custom-voice").performTextClearance()
        rule.onNodeWithTag("fala-custom-voice").performTextInput("Fritz-PlayAI")
        rule.onNodeWithTag(FALA_TEST_TEXT_TAG).performScrollTo().performClick() // tira o foco: o campo grava
        rule.runOnIdle { assertEquals("Fritz-PlayAI", h.settings.falaCustomVoice) }
    }

    @Test
    fun visionAndReasoningCustomFieldsStayWithoutVoiceAndFormat() {
        rule.setContent {
            KeyFlowTheme(dark = false) {
                VisaoPanel(settings = Settings(visionSource = com.keyflow.core.VisionSource.CUSTOM), onSettingsChange = {})
            }
        }
        rule.onNodeWithTag("visao-custom-endpoint").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("visao-custom-voice").assertDoesNotExist()
        rule.onNodeWithTag("visao-custom-format").assertDoesNotExist()
    }

    @Test
    fun cloudWithoutAKeyDisablesTestWithTheReason() {
        val h = Harness(Settings(falaSource = FalaSource.GOOGLE), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(FALA_TEST_REASON_TAG).performScrollTo().assertTextContains("Conecte nesta aba", substring = true)
    }

    @Test
    fun customWithoutEndpointDisablesTestAndWithOneEnablesIt() {
        val h = Harness(Settings(falaSource = FalaSource.CUSTOM), voiceUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(FALA_TEST_REASON_TAG).performScrollTo().assertTextContains("Informe o endpoint", substring = true)
        rule.runOnIdle { h.settings = h.settings.copy(falaCustomEndpoint = "https://exemplo.test/v1/audio/speech") }
        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).assertIsEnabled()
        rule.onNodeWithTag(FALA_TEST_REASON_TAG).assertDoesNotExist()
    }

    @Test
    fun testSpeaksTheTypedTextPlaysTheAudioAndShowsTheTimes() {
        val rec = Recorder(twoBuffers)
        val h = Harness(Settings(falaSource = FalaSource.SYSTEM, falaSpeed = 1.25f), voiceUi(), tester = rec.tester)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_TEXT_TAG).performScrollTo().performTextClearance()
        rule.onNodeWithTag(FALA_TEST_TEXT_TAG).performTextInput("Boa tarde")
        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals(listOf(SpeechRequest("Boa tarde", 1.25f)), rec.requests)
            assertEquals(listOf(4800, 4800), rec.played)
            assertEquals(FalaSource.SYSTEM, rec.engineSettings.single().falaSource)
            assertTrue(rec.stopped >= 1) // o player é liberado no fim
        }
        rule.onNodeWithTag(FALA_TEST_TIMES_TAG).performScrollTo().assertTextContains("1º áudio: 120 ms", substring = true)
        rule.onNodeWithTag(FALA_TEST_TIMES_TAG).assertTextContains("duração: 2.5 s", substring = true)
    }

    @Test
    fun emptyTextSpeaksTheDefaultPhrase() {
        val rec = Recorder(twoBuffers)
        val h = Harness(Settings(falaSource = FalaSource.SYSTEM), voiceUi(), tester = rec.tester)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_TEXT_TAG).performScrollTo().performTextClearance()
        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("Olá. Esta é a voz do KeyFlow.", rec.requests.single().text) }
    }

    @Test
    fun stopCancelsTheSynthesisAndNoTimesAreShown() {
        var cancelled = false
        val rec = Recorder { _ ->
            flow {
                try {
                    emit(SpeechEvent.Audio(ByteArray(100), 24_000))
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val h = Harness(Settings(falaSource = FalaSource.SYSTEM), voiceUi(), tester = rec.tester)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).assertTextContains("Parar", substring = true)
        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performClick()
        rule.waitForIdle()

        rule.runOnIdle {
            assertTrue(cancelled)
            assertTrue(rec.stopped >= 1)
        }
        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).assertTextContains("Testar", substring = true)
        rule.onNodeWithTag(FALA_TEST_TIMES_TAG).assertDoesNotExist()
    }

    @Test
    fun changingTheSourceWhileSpeakingInterruptsTheTestAndDropsItsResult() {
        var cancelled = false
        val rec = Recorder { _ -> flow { try { awaitCancellation() } finally { cancelled = true } } }
        val h = Harness(Settings(falaSource = FalaSource.SYSTEM), voiceUi(), tester = rec.tester)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle { h.settings = h.settings.copy(falaSource = FalaSource.LOCAL) }
        rule.waitForIdle()

        rule.runOnIdle { assertTrue(cancelled) }
        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).assertTextContains("Testar", substring = true)
    }

    @Test
    fun theEngineGetsTheSettingsConfirmedAtTheTapNotLaterEdits() {
        val rec = Recorder(twoBuffers)
        val h = Harness(Settings(falaSource = FalaSource.SYSTEM, falaSpeed = 1.0f), voiceUi(), tester = rec.tester)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            h.settings = h.settings.copy(falaSpeed = 1.5f)
            assertEquals(1.0f, rec.engineSettings.single().falaSpeed)
            assertEquals(1.0f, rec.requests.single().speed)
        }
    }

    @Test
    fun anEngineErrorShowsAShortMessageAndTheDataInstallShortcut() {
        val rec = Recorder { _ -> flowOf(SpeechEvent.Error(SpeechError.TTS_DATA_MISSING, "Faltam os dados de voz deste idioma no aparelho.")) }
        val h = Harness(Settings(falaSource = FalaSource.SYSTEM), voiceUi(), tester = rec.tester)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(FALA_TEST_RESPONSE_TAG).performScrollTo().assertTextContains("Faltam os dados", substring = true)
        rule.onNodeWithTag(FALA_INSTALL_DATA_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(FALA_TEST_TIMES_TAG).assertDoesNotExist()
    }

    @Test
    fun localWithoutAnInstalledModelNeverCallsTheEngine() {
        val rec = Recorder(twoBuffers)
        val h = Harness(Settings(), voiceUi(), tester = rec.tester)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(FALA_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        rule.runOnIdle {
            assertTrue(rec.engineSettings.isEmpty())
            assertEquals(FalaSource.LOCAL, h.settings.falaSource) // não trocou para Sistema
        }
    }
}
