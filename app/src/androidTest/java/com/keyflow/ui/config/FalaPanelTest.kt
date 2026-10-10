package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.FalaSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FalaPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private val piper = ModelCatalog.VOICE_FAST
    private val kokoro = ModelCatalog.VOICE_BALANCED
    private val melo = ModelCatalog.VOICE_QUALITY
    private val whisper = ModelCatalog.WHISPER_BASE
    private val qwen = ModelCatalog.QWEN3_0_6B
    private val ocr = ModelCatalog.OCR_FAST

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
                )
            }
        }
    }

    private fun voiceUi(installed: Set<String> = emptySet(), selected: String? = null) = LocalModelsUi.compose(
        catalog = ModelCatalog.VOICE, installed = installed, active = null, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_000,
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
}
