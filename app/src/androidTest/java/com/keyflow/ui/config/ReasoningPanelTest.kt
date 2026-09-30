package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.ReasoningSource
import com.keyflow.core.TranscriptionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** RSC-01/02/04/06: o painel Raciocínio — origem, lista de modelos de TEXTO, seleção e apagar, sem tocar na Transcrição. */
@RunWith(AndroidJUnit4::class)
class ReasoningPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private val qwen = ModelCatalog.QWEN3_0_6B
    private val gemma = ModelCatalog.GEMMA_4_E2B
    private val whisper = ModelCatalog.WHISPER_BASE

    private class FakeActions : LocalModelActions {
        val deletes = mutableListOf<String>()
        override suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult = StartResult.Started
        override fun cancel(modelId: String) = Unit
        override fun delete(modelId: String) { deletes += modelId }
    }

    private inner class Harness(initial: Settings, val models: LocalModelsUi, val actions: FakeActions = FakeActions()) {
        var settings by mutableStateOf(initial)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                ReasoningPanel(settings = settings, onSettingsChange = { settings = it }, models = models, actions = actions)
            }
        }
    }

    private fun textUi(installed: Set<String> = emptySet(), selected: String? = null) = LocalModelsUi.compose(
        catalog = ModelCatalog.TEXT, installed = installed, active = null, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_000,
    )

    @Test
    fun opensOnTheLocalSourceWithTheTextModelsListed() {
        val h = Harness(Settings(), textUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(reasoningSourceTag(ReasoningSource.LOCAL)).assertIsDisplayed()
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("raciocinio-local-model-${gemma.id}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(RACIOCINIO_LOCAL_PRIVACY_TAG).performScrollTo().assertIsDisplayed()
    }

    /** O Raciocínio nunca lista os modelos de fala. */
    @Test
    fun theReasoningListNeverShowsSpeechModels() {
        val h = Harness(Settings(), textUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("raciocinio-local-model-${whisper.id}").assertDoesNotExist()
        rule.onNodeWithTag(localModelTag(whisper.id)).assertDoesNotExist()
    }

    @Test
    fun switchingTheSourcePersistsAndSwapsThePanel() {
        val h = Harness(Settings(), textUi())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(reasoningSourceTag(ReasoningSource.OPENAI)).performClick()

        rule.runOnIdle { assertEquals(ReasoningSource.OPENAI, h.settings.reasoningSource) }
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").assertDoesNotExist()
        rule.onNodeWithTag(RACIOCINIO_OPENAI_PRIVACY_TAG).performScrollTo().assertIsDisplayed()

        rule.onNodeWithTag(reasoningSourceTag(ReasoningSource.LOCAL)).performClick()
        rule.runOnIdle { assertEquals(ReasoningSource.LOCAL, h.settings.reasoningSource) }
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingAnInstalledModelPersistsItAsTheReasoningModelOnly() {
        val h = Harness(Settings(source = TranscriptionSource.LOCAL, localModelId = whisper.id), textUi(installed = setOf(qwen.id)))
        rule.setContent { h.Content() }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(qwen.id, h.settings.reasoningLocalModelId)
            assertEquals(whisper.id, h.settings.localModelId) // a Transcrição não é tocada
        }
    }

    @Test
    fun deletingTheSelectedModelClearsTheChoice() {
        val h = Harness(Settings(reasoningLocalModelId = qwen.id), textUi(installed = setOf(qwen.id), selected = qwen.id))
        rule.setContent { h.Content() }

        rule.onNodeWithTag("raciocinio-local-delete-${qwen.id}").performScrollTo().performClick()
        rule.onNodeWithTag("raciocinio-local-confirm-yes").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(listOf(qwen.id), h.actions.deletes)
            assertEquals(null, h.settings.reasoningLocalModelId)
        }
    }

    @Test
    fun deletingAModelThatIsNotTheSelectedOneKeepsTheChoice() {
        val h = Harness(
            Settings(reasoningLocalModelId = gemma.id),
            textUi(installed = setOf(qwen.id, gemma.id), selected = gemma.id),
        )
        rule.setContent { h.Content() }

        rule.onNodeWithTag("raciocinio-local-delete-${qwen.id}").performScrollTo().performClick()
        rule.onNodeWithTag("raciocinio-local-confirm-yes").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(listOf(qwen.id), h.actions.deletes)
            assertEquals(gemma.id, h.settings.reasoningLocalModelId)
        }
    }

    /** RSC-06: e a lista da Transcrição, por sua vez, nunca mostra Gemma nem Qwen. */
    @Test
    fun theTranscriptionListNeverShowsTextModels() {
        val speechUi = LocalModelsUi.compose(installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 8_000)
        rule.setContent {
            KeyFlowTheme(dark = false) {
                VozPanel(
                    settings = Settings(source = TranscriptionSource.LOCAL),
                    onSettingsChange = {},
                    localModels = speechUi,
                    localActions = FakeActions(),
                )
            }
        }

        rule.onNodeWithTag(localModelTag(whisper.id)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(localModelTag(qwen.id)).assertDoesNotExist()
        rule.onNodeWithTag(localModelTag(gemma.id)).assertDoesNotExist()
    }
}
