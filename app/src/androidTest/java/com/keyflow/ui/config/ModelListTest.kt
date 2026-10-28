package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import com.keyflow.core.ComputeBackend
import com.keyflow.data.LocalBackendPrefs
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
import org.junit.Assert.assertTrue
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
        val backends = mutableListOf<Triple<String, ComputeBackend, Boolean>>()
        override suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult = StartResult.Started
        override fun cancel(modelId: String) = Unit
        override fun delete(modelId: String) { deletes += modelId }
        override fun chooseBackend(modelId: String, backend: ComputeBackend, prepare: Boolean) { backends += Triple(modelId, backend, prepare) }
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

    private fun textWithGpu(
        installed: Set<String>,
        selected: String? = null,
        prefs: LocalBackendPrefs = LocalBackendPrefs.EMPTY,
        gpu: Boolean = true,
    ) = LocalModelsUi.compose(
        catalog = ModelCatalog.TEXT, installed = installed, active = null, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_000,
        backendPrefs = prefs, gpuAvailable = gpu,
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
        rule.onAllNodesWithTag("model-vendor-qwen").assertCountEquals(3)
        rule.onNodeWithTag("model-vendor-hugging_face").assertExists()
        rule.onNodeWithTag("model-vendor-deepseek").assertExists()
        rule.onAllNodesWithTag("model-vendor-google").assertCountEquals(2)
    }

    /** O card não mostra mais o resumo: o que o modelo é vem em etiquetas (idioma, pensamento, RAM, capacidades). */
    @Test
    fun theCardShowsTagsAndNoLongerTheSummary() {
        rule.setContent { Host(text(installed = emptySet()), FakeActions(), ModelListTags.Raciocinio) }

        fun tag(spec: ModelSpec, key: String) = rule.onNodeWithTag("raciocinio-local-model-${spec.id}-tag-$key")
        tag(ModelCatalog.QWEN3_0_6B, "language").performScrollTo().assertTextEquals("Multilíngue")
        tag(ModelCatalog.QWEN3_0_6B, "thinking").assertTextEquals("Pensamento opcional")
        tag(ModelCatalog.QWEN3_0_6B, "capability_tools").assertTextEquals("Ferramentas")
        tag(ModelCatalog.QWEN3_0_6B, "ram").assertTextEquals("≥ 3 GB de RAM")
        tag(ModelCatalog.DEEPSEEK_R1_1_5B, "thinking").performScrollTo().assertTextEquals("Sempre pensa")
        tag(ModelCatalog.SMOLLM2_1_7B, "language").performScrollTo().assertTextEquals("Inglês")
        tag(ModelCatalog.DEEPSEEK_R1_1_5B, "capability_tools").assertDoesNotExist()
        tag(ModelCatalog.QWEN2_5_1_5B, "thinking").assertDoesNotExist()
        tag(ModelCatalog.QWEN3_4B, "thinking").assertDoesNotExist()
        tag(ModelCatalog.SMOLLM2_1_7B, "thinking").assertDoesNotExist()
        ModelCatalog.TEXT.forEach { rule.onNodeWithText(it.summary).assertDoesNotExist() }
    }

    @Test
    fun theSpeechAndVisionCardsTagTheirOwnDifferentials() {
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KeyFlowTheme(dark = false) {
                    ModelList(speech(installed = emptySet()), FakeActions(), {}, {}, ModelListTags.Voz)
                    ModelList(
                        LocalModelsUi.compose(
                            catalog = listOf(ModelCatalog.OCR_PADDLE_LATIN), installed = emptySet(), active = null, failures = emptyMap(),
                            selectedId = null, deviceRamMb = 8_000,
                        ),
                        FakeActions(), {}, {}, ModelListTags.Visao,
                    )
                }
            }
        }

        rule.onNodeWithTag("voz-local-model-${ModelCatalog.PARAKEET_V3.id}-tag-native_portuguese").performScrollTo().assertTextEquals("Português nativo")
        rule.onNodeWithTag("voz-local-model-${ModelCatalog.PARAKEET_V3.id}-tag-language").assertTextEquals("25 idiomas europeus")
        rule.onNodeWithTag("voz-local-model-${base.id}-tag-native_portuguese").assertDoesNotExist()
        rule.onNodeWithTag("visao-local-model-${ModelCatalog.OCR_PADDLE_LATIN.id}-tag-capability_read_text").performScrollTo()
            .assertTextEquals("Ler texto da tela")
    }

    /** Os três estados se distinguem sem depender de cor: não baixado (só "Baixar"), baixado (o check) e selecionado (o item de rádio marcado). */
    @Test
    fun notDownloadedDownloadedAndSelectedAreThreeDistinctStates() {
        rule.setContent { Host(text(installed = setOf(qwen.id, gemma.id), selected = gemma.id), FakeActions(), ModelListTags.Raciocinio) }

        fun mark(spec: ModelSpec) = rule.onNodeWithTag("raciocinio-local-model-${spec.id}-downloaded", useUnmergedTree = true)
        // Não baixado: nenhum check, o botão é "Baixar" e o card nem é item de seleção (sem estado de seleção).
        mark(ModelCatalog.SMOLLM2_1_7B).assertDoesNotExist()
        rule.onNodeWithTag("raciocinio-local-download-${ModelCatalog.SMOLLM2_1_7B.id}").performScrollTo().assertTextEquals("Baixar")
        rule.onNodeWithTag("raciocinio-local-model-${ModelCatalog.SMOLLM2_1_7B.id}").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        // Baixado e não selecionado: o check "Baixado" e o item de rádio desmarcado.
        mark(qwen).performScrollTo().assertContentDescriptionEquals("Baixado")
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").assertIsNotSelected()
        rule.onNodeWithTag("raciocinio-local-delete-${qwen.id}").assertTextEquals("Apagar")
        // Baixado e selecionado: o mesmo check, mais o item de rádio marcado.
        mark(gemma).performScrollTo().assertContentDescriptionEquals("Baixado")
        rule.onNodeWithTag("raciocinio-local-model-${gemma.id}").assertIsSelected()
    }

    /** O logo do fabricante é grande o bastante para reconhecer a marca (era 15 dp, no topo) e fica à esquerda do nome. */
    @Test
    fun theVendorLogoIsBigAndSitsBesideTheName() {
        rule.setContent { Host(text(installed = emptySet()), FakeActions(), ModelListTags.Raciocinio) }

        // Os três cards da Qwen têm o logo; o de cima é o do Leve (Qwen3 0.6B), o primeiro da lista pelo tamanho do arquivo.
        val logo = rule.onAllNodesWithTag("model-vendor-qwen", useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        assertTrue("logo de ${logo.width / density} dp", logo.width / density >= 28f && logo.width / density <= 32f)
        val card = rule.onNodeWithTag("raciocinio-local-model-${qwen.id}").fetchSemanticsNode().boundsInRoot
        assertTrue("o logo fica no começo do card", logo.left - card.left < 24f * density)
    }

    @Test
    fun theParakeetNameStaysOnOneLineWithTheDownloadedMark() {
        val ui = LocalModelsUi.compose(
            catalog = listOf(ModelCatalog.PARAKEET_V3), installed = setOf(ModelCatalog.PARAKEET_V3.id), active = null, failures = emptyMap(),
            selectedId = ModelCatalog.PARAKEET_V3.id, deviceRamMb = 8_000,
        )
        rule.setContent { Host(ui, FakeActions(), ModelListTags.Voz) }

        rule.onNodeWithText("Máxima qualidade", useUnmergedTree = true).assertIsDisplayed()
        val name = rule.onNodeWithText("Máxima qualidade", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // Uma linha de 13 sp tem menos de 24 dp de altura; duas linhas passariam de 30.
        assertTrue("o nome quebrou: ${name.height / rule.density.density} dp", name.height / rule.density.density < 24f)
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

    // ── seletor "Roda em" (CPU · GPU) ─────────────────────────────────────────────────────────────

    @Test
    fun anInstalledTextCardOnADeviceWithAGpuOffersCpuAndGpuWithTheCpuSelected() {
        rule.setContent { Host(textWithGpu(installed = setOf(qwen.id), selected = qwen.id), FakeActions(), ModelListTags.Raciocinio) }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-cpu").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-gpu").performScrollTo().assertIsNotSelected()
        rule.onNodeWithText("Roda em").assertExists()
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-hint", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun withoutAGpuTheSelectorIsNotShown() {
        rule.setContent { Host(textWithGpu(installed = setOf(qwen.id), selected = qwen.id, gpu = false), FakeActions(), ModelListTags.Raciocinio) }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-cpu").assertDoesNotExist()
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-gpu").assertDoesNotExist()
        rule.onNodeWithText("Roda em").assertDoesNotExist()
    }

    @Test
    fun onlyInstalledTextCardsShowTheSelector() {
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KeyFlowTheme(dark = false) {
                    ModelList(textWithGpu(installed = setOf(qwen.id)), FakeActions(), {}, {}, ModelListTags.Raciocinio)
                    ModelList(speech(installed = setOf(base.id)), FakeActions(), {}, {}, ModelListTags.Voz)
                }
            }
        }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-gpu").assertExists()
        rule.onNodeWithTag("raciocinio-local-model-${gemma.id}-backend-gpu").assertDoesNotExist()   // não baixado
        rule.onNodeWithTag("voz-local-model-${base.id}-backend-gpu").assertDoesNotExist()           // fala nunca tem GPU
    }

    @Test
    fun tappingGpuOnTheSelectedModelChoosesItAndAsksToPrepareIt() {
        val actions = FakeActions()
        rule.setContent { Host(textWithGpu(installed = setOf(qwen.id), selected = qwen.id), actions, ModelListTags.Raciocinio) }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-gpu").performScrollTo().performClick()

        rule.runOnIdle { assertEquals(listOf(Triple(qwen.id, ComputeBackend.GPU, true)), actions.backends) }
    }

    /** Escolher o processador de um modelo instalado que não é o selecionado só grava: não há por que carregá-lo agora. */
    @Test
    fun tappingGpuOnAnInstalledModelThatIsNotSelectedDoesNotAskToPrepare() {
        val actions = FakeActions()
        val selectedIds = mutableListOf<String>()
        rule.setContent {
            Host(textWithGpu(installed = setOf(qwen.id, gemma.id), selected = qwen.id), actions, ModelListTags.Raciocinio, onSelect = { selectedIds += it })
        }

        rule.onNodeWithTag("raciocinio-local-model-${gemma.id}-backend-gpu").performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(listOf(Triple(gemma.id, ComputeBackend.GPU, false)), actions.backends)
            assertEquals("o toque no seletor não seleciona o card", emptyList<String>(), selectedIds)
        }
    }

    @Test
    fun theGpuChoiceShowsSelectedAndWarnsTheFirstLoadTakesMinutes() {
        val prefs = LocalBackendPrefs.EMPTY.choose(qwen.id, ComputeBackend.GPU)
        rule.setContent { Host(textWithGpu(installed = setOf(qwen.id), selected = qwen.id, prefs = prefs), FakeActions(), ModelListTags.Raciocinio) }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-gpu").performScrollTo().assertIsSelected()
        // O card instalado é um item de seleção (junta os filhos): o texto avulso só existe na árvore sem junção.
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-hint", useUnmergedTree = true)
            .assertTextEquals("Na GPU, a primeira carga pode levar alguns minutos.")
    }

    @Test
    fun onceTheGpuHasLoadedTheModelTheWarningIsGone() {
        val prefs = LocalBackendPrefs.EMPTY.choose(qwen.id, ComputeBackend.GPU, ticket = 7L).markGpuReady(qwen.id, 7L)
        rule.setContent { Host(textWithGpu(installed = setOf(qwen.id), selected = qwen.id, prefs = prefs), FakeActions(), ModelListTags.Raciocinio) }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-gpu").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-hint", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aGpuThatFellBackShowsTheNoticeWithTheCpuSelected() {
        val prefs = LocalBackendPrefs.EMPTY.choose(qwen.id, ComputeBackend.GPU, ticket = 7L).fellBack(qwen.id, ComputeBackend.GPU, 7L)
        rule.setContent { Host(textWithGpu(installed = setOf(qwen.id), selected = qwen.id, prefs = prefs), FakeActions(), ModelListTags.Raciocinio) }

        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-cpu").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-notice", useUnmergedTree = true)
            .assertTextEquals("A GPU não funcionou neste aparelho; usando a CPU.")
        rule.onNodeWithTag("raciocinio-local-model-${qwen.id}-backend-hint", useUnmergedTree = true).assertDoesNotExist()
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
