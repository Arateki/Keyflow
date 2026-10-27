package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.ui.gallery.SamplePanelModels
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisaoPanelTest {

    @get:Rule
    val rule = createComposeRule()

    // O catálogo real de Visão está vazio (2026-10-24): o painel é exercitado com modelos de amostra.
    private val fastOcr = SamplePanelModels.VISION_LIGHT
    private val balancedOcr = SamplePanelModels.VISION_BALANCED
    private val smolVlm = SamplePanelModels.VISION_HEAVY
    private val whisper = ModelCatalog.WHISPER_BASE
    private val qwen = ModelCatalog.QWEN3_0_6B

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
                VisaoPanel(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    models = models,
                    actions = actions,
                    localSupported = localSupported,
                )
            }
        }
    }

    private fun visionUi(installed: Set<String> = emptySet(), selected: String? = null) = LocalModelsUi.compose(
        catalog = SamplePanelModels.VISION, installed = installed, active = null, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_000,
    )

    @Test
    fun aDeviceWithoutTheLocalEngineShowsNoDownloadsAndSaysWhy() {
        val h = Harness(Settings(), visionUi())
        h.localSupported = false
        rule.setContent { h.Content() }

        rule.onNodeWithTag(VISAO_LOCAL_UNSUPPORTED_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("visao-local-model-${fastOcr.id}").assertDoesNotExist()
        rule.onNodeWithTag("visao-local-model-${balancedOcr.id}").assertDoesNotExist()
        rule.onNodeWithTag(VISAO_LOCAL_PRIVACY_TAG).assertDoesNotExist()
    }

    @Test
    fun opensOnTheLocalSourceWithTheVisionModelsListed() {
        val h = Harness(Settings(), visionUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(visionSourceTag(VisionSource.LOCAL)).assertIsDisplayed()
        rule.onNodeWithTag("visao-local-model-${fastOcr.id}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("visao-local-model-${balancedOcr.id}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(VISAO_LOCAL_PRIVACY_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(VISAO_TRANSLATE_TARGET_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theRealOcrCardShowsThePaddlePaddleLogo() {
        // O OCR do catálogo é da PaddlePaddle (Baidu), não do repositório que hospeda os arquivos.
        val real = LocalModelsUi.compose(
            catalog = listOf(ModelCatalog.OCR_PADDLE_LATIN), installed = emptySet(), active = null, failures = emptyMap(),
            selectedId = null, deviceRamMb = 8_000,
        )
        val h = Harness(Settings(), real)
        rule.setContent { h.Content() }

        rule.onNodeWithTag("model-vendor-paddlepaddle").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("PaddlePaddle").assertExists()
    }

    @Test
    fun theVisionListNeverShowsSpeechOrTextModels() {
        val h = Harness(Settings(), visionUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag("visao-local-model-${whisper.id}").assertDoesNotExist()
        rule.onNodeWithTag("visao-local-model-${qwen.id}").assertDoesNotExist()
        rule.onNodeWithTag("raciocinio-local-model-${fastOcr.id}").assertDoesNotExist()
        rule.onNodeWithTag("voz-local-model-${fastOcr.id}").assertDoesNotExist()
    }

    @Test
    fun selectingAnInstalledVisionModelUpdatesSettings() {
        val h = Harness(Settings(), visionUi(installed = setOf(fastOcr.id)))
        rule.setContent { h.Content() }

        rule.onNodeWithTag("visao-local-model-${fastOcr.id}").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(fastOcr.id, h.settings.visionLocalModelId) }
    }

    @Test
    fun deletingTheSelectedVisionModelClearsSelection() {
        val h = Harness(Settings(visionLocalModelId = fastOcr.id), visionUi(installed = setOf(fastOcr.id), selected = fastOcr.id))
        rule.setContent { h.Content() }

        rule.onNodeWithTag("visao-local-delete-${fastOcr.id}").performScrollTo().performClick()
        rule.onNodeWithTag("visao-local-confirm-yes").performClick()
        rule.runOnIdle {
            assertEquals(listOf(fastOcr.id), h.actions.deletes)
            assertNull(h.settings.visionLocalModelId)
        }
    }

    @Test
    fun switchingSourceToOpenAiShowsCloudInfo() {
        val h = Harness(Settings(), visionUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(visionSourceTag(VisionSource.OPENAI)).performClick()
        rule.runOnIdle { assertEquals(VisionSource.OPENAI, h.settings.visionSource) }
        rule.onNodeWithTag(VISAO_OPENAI_INFO_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun testAreaRequiresInstalledModelOnLocal() {
        val h = Harness(Settings(), visionUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(VISAO_TEST_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(VISAO_TEST_REASON_TAG).performScrollTo().assertIsDisplayed()
    }
}
