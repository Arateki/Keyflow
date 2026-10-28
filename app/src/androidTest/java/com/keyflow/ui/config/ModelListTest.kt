package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import com.keyflow.data.models.ModelPreparation
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RSC-06: a lista de modelos é a mesma para a Transcrição e para o Raciocínio — só as tags mudam. Aqui a lista sozinha,
 * sem painel em volta, para provar as duas famílias de tag e os dois avisos (`onSelect`, `onDeleted`).
 */
@RunWith(AndroidJUnit4::class)
class ModelListTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeActions : LocalModelActions {
        val deletes = mutableListOf<String>()
        override suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult = StartResult.Started
        override fun cancel(modelId: String) = Unit
        override fun delete(modelId: String) { deletes += modelId }
    }

    private val base = ModelCatalog.WHISPER_BASE
    private val small = ModelCatalog.WHISPER_SMALL
    private val qwen = ModelCatalog.QWEN3_0_6B
    private val gemma = ModelCatalog.GEMMA_4_E2B

    private fun speech(installed: Set<String>, selected: String? = null) = LocalModelsUi.compose(
        catalog = ModelCatalog.SPEECH, installed = installed, active = null, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_000,
    )

    private fun text(installed: Set<String>, selected: String? = null) = LocalModelsUi.compose(
        catalog = ModelCatalog.TEXT, installed = installed, active = null, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_000,
    )

    @Composable
    private fun Host(
        models: LocalModelsUi,
        actions: LocalModelActions,
        tags: ModelListTags,
        onSelect: (String) -> Unit = {},
        onDeleted: (String) -> Unit = {},
    ) {
        KeyFlowTheme(dark = false) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ModelList(models, actions, onSelect, onDeleted, tags)
            }
        }
    }

    /** Toda a fala existente (LocalPanelTest) procura por estas tags — não podem mudar. */
    @Test
    fun theSpeechTagsAreExactlyTheOnesTheTranscriptionTabAlwaysUsed() {
        rule.setContent { Host(speech(installed = setOf(base.id)), FakeActions(), ModelListTags.Voz) }

        rule.onNodeWithTag(localModelTag(base.id)).assertExists()
        rule.onNodeWithTag(localDeleteTag(base.id)).assertExists()
        rule.onNodeWithTag(localDownloadTag(small.id)).assertExists()
        rule.onNodeWithTag("voz-local-model-${base.id}").assertExists()
        rule.onNodeWithTag("voz-local-download-${small.id}").assertExists()
    }

    @Test
    fun theReasoningTagsUseTheirOwnPrefixAndNeverTheSpeechOnes() {
        rule.setContent { Host(text(installed = setOf(qwen.id)), FakeActions(), ModelListTags.Raciocinio) }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").assertExists()
        rule.onNodeWithTag("raciocinio-local-delete-${qwen.id}").assertExists()
        rule.onNodeWithTag("raciocinio-local-download-${gemma.id}").assertExists()
        rule.onNodeWithTag(localModelTag(qwen.id)).assertDoesNotExist()
    }

    @Test
    fun everyCardShowsTheLogoOfTheCompanyThatMakesTheModel() {
        rule.setContent { Host(speech(installed = emptySet()), FakeActions(), ModelListTags.Voz) }
        // Whisper (os dois) é da OpenAI; o Parakeet, da NVIDIA — o nome da empresa é a descrição para o leitor de tela.
        rule.onAllNodesWithTag("model-vendor-openai").assertCountEquals(2)
        rule.onNodeWithTag("model-vendor-nvidia").assertExists()
        rule.onNodeWithContentDescription("NVIDIA").assertExists()
    }

    @Test
    fun theTextCardsCarryTheirOwnVendorLogo() {
        rule.setContent { Host(text(installed = emptySet()), FakeActions(), ModelListTags.Raciocinio) }
        rule.onAllNodesWithTag("model-vendor-qwen").assertCountEquals(5)
        rule.onAllNodesWithTag("model-vendor-hugging_face").assertCountEquals(2)
        rule.onNodeWithTag("model-vendor-deepseek").assertExists()
        rule.onAllNodesWithTag("model-vendor-google").assertCountEquals(3)
        rule.onNodeWithTag("model-vendor-ibm").assertExists()
        rule.onNodeWithTag("model-vendor-nanbeige").assertExists()
    }

    /** Só informativo: Qwen3 0.6B e Gemma 4 suportam pensamento, o DeepSeek sempre pensa; Qwen2.5, SmolLM2 e Qwen3 4B Instruct-2507 não ganham linha. */
    @Test
    fun theTextCardsSayWhetherTheModelThinks() {
        rule.setContent { Host(text(installed = emptySet()), FakeActions(), ModelListTags.Raciocinio) }

        fun thinking(spec: ModelSpec) = rule.onNodeWithTag("raciocinio-local-model-${spec.id}-thinking")
        thinking(ModelCatalog.QWEN3_0_6B).performScrollTo().assertTextEquals("Suporta modo pensamento.")
        thinking(ModelCatalog.GEMMA_4_E2B).performScrollTo().assertTextEquals("Suporta modo pensamento.")
        thinking(ModelCatalog.GEMMA_4_E4B).performScrollTo().assertTextEquals("Suporta modo pensamento.")
        thinking(ModelCatalog.DEEPSEEK_R1_1_5B).performScrollTo().assertTextEquals("Sempre pensa antes de responder.")
        thinking(ModelCatalog.QWEN2_5_1_5B).assertDoesNotExist()
        thinking(ModelCatalog.QWEN3_4B).assertDoesNotExist()
        thinking(ModelCatalog.SMOLLM2_1_7B).assertDoesNotExist()
    }

    /** Quem tem o arquivo antigo do modelo vê "Atualizar modelo" (não "Baixar") e o porquê. */
    @Test
    fun anOutdatedInstallOffersToUpdateInsteadOfDownload() {
        val ui = LocalModelsUi.compose(
            catalog = ModelCatalog.TEXT, installed = emptySet(), outdated = setOf(qwen.id), active = null, failures = emptyMap(),
            selectedId = qwen.id, deviceRamMb = 8_000,
        )
        rule.setContent { Host(ui, FakeActions(), ModelListTags.Raciocinio) }

        rule.onNodeWithTag("raciocinio-local-download-${qwen.id}").performScrollTo().assertTextEquals("Atualizar modelo")
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-outdated").performScrollTo().assertExists()
        rule.onNodeWithTag("raciocinio-local-download-${gemma.id}").performScrollTo().assertTextEquals("Baixar")
        rule.onNodeWithTag("raciocinio-local-model-${gemma.id}-outdated").assertDoesNotExist()
        rule.onNodeWithTag("raciocinio-local-delete-${gemma.id}").assertDoesNotExist()
    }

    /** Achado 4 da revisão: quem precisa do espaço da cópia antiga a apaga pelo próprio card desatualizado. */
    @Test
    fun anOutdatedCardOffersDeleteAndConfirmsBeforeDeleting() {
        val ui = LocalModelsUi.compose(
            catalog = ModelCatalog.TEXT, installed = emptySet(), outdated = setOf(qwen.id), active = null, failures = emptyMap(),
            selectedId = null, deviceRamMb = 8_000,
        )
        val actions = FakeActions()
        val deleted = mutableListOf<String>()
        rule.setContent { Host(ui, actions, ModelListTags.Raciocinio, onDeleted = { deleted += it }) }

        rule.onNodeWithTag("raciocinio-local-delete-${qwen.id}").performScrollTo().performClick()
        rule.onNodeWithTag("raciocinio-local-confirm-${qwen.id}").performScrollTo().assertExists()
        rule.onNodeWithTag("raciocinio-local-confirm-yes").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(listOf(qwen.id), actions.deletes)
            assertEquals(listOf(qwen.id), deleted)
        }
    }

    @Test
    fun tappingAnInstalledModelReportsItsId() {
        val selected = mutableListOf<String>()
        rule.setContent { Host(text(installed = setOf(qwen.id)), FakeActions(), ModelListTags.Raciocinio, onSelect = { selected += it }) }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").performScrollTo().performClick()

        rule.runOnIdle { assertEquals(listOf(qwen.id), selected) }
    }

    @Test
    fun confirmingDeleteDeletesAndReportsTheId() {
        val actions = FakeActions()
        val deleted = mutableListOf<String>()
        rule.setContent {
            Host(text(installed = setOf(qwen.id), selected = qwen.id), actions, ModelListTags.Raciocinio, onDeleted = { deleted += it })
        }

        rule.onNodeWithTag("raciocinio-local-delete-${qwen.id}").performScrollTo().performClick()
        rule.onNodeWithTag("raciocinio-local-confirm-yes").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(listOf(qwen.id), actions.deletes)
            assertEquals(listOf(qwen.id), deleted)
        }
    }

    @Test
    fun cancellingTheDeleteConfirmationDeletesNothing() {
        val actions = FakeActions()
        val deleted = mutableListOf<String>()
        rule.setContent {
            Host(text(installed = setOf(qwen.id)), actions, ModelListTags.Raciocinio, onDeleted = { deleted += it })
        }

        rule.onNodeWithTag("raciocinio-local-delete-${qwen.id}").performScrollTo().performClick()
        rule.onNodeWithTag("raciocinio-local-confirm-no").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(emptyList<String>(), actions.deletes)
            assertEquals(emptyList<String>(), deleted)
        }
    }

    @Test
    fun preparacaoDeVozMostraTempoNoCardESomeQuandoPronto() {
        preparationIndicator(ModelListTags.Voz, base)
    }

    @Test
    fun preparacaoDeTextoMostraTempoNoCardESomeQuandoPronto() {
        preparationIndicator(ModelListTags.Raciocinio, qwen)
    }

    private fun preparationIndicator(tags: ModelListTags, spec: ModelSpec) {
        val preparing = mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalModelPreparationClock provides { 5000L }) {
                Host(
                    LocalModelsUi.compose(
                        catalog = listOf(spec), installed = setOf(spec.id), active = null, failures = emptyMap(),
                        selectedId = spec.id, deviceRamMb = 8192,
                        preparation = if (preparing.value) ModelPreparation(spec.id, 0) else null,
                    ),
                    FakeActions(), tags,
                )
            }
        }
        rule.onNodeWithTag("${tags.model(spec.id)}-preparation", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Preparando modelo… 5 s", useUnmergedTree = true).assertExists()
        rule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        rule.runOnIdle { preparing.value = false }
        rule.onNodeWithTag("${tags.model(spec.id)}-preparation", useUnmergedTree = true).assertDoesNotExist()
    }
}
