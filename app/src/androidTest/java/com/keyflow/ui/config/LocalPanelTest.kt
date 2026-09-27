package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.TranscriptionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.DownloadProgress
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** NM-T10: o painel Local com gerenciador fake — estados, seleção, apagar com confirmação, dados móveis. */
@RunWith(AndroidJUnit4::class)
class LocalPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private val base = ModelCatalog.WHISPER_BASE
    private val small = ModelCatalog.WHISPER_SMALL
    private val parakeet = ModelCatalog.PARAKEET_V3

    private class FakeActions(private val results: MutableList<StartResult>) : LocalModelActions {
        val downloads = mutableListOf<Pair<String, Boolean>>()
        val cancels = mutableListOf<String>()
        val deletes = mutableListOf<String>()

        override suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult {
            downloads += spec.id to allowMetered
            return if (results.size > 1) results.removeAt(0) else results.first()
        }

        override fun cancel(modelId: String) { cancels += modelId }
        override fun delete(modelId: String) { deletes += modelId }
    }

    private inner class Harness(
        initial: Settings,
        val models: LocalModelsUi,
        val actions: FakeActions = FakeActions(mutableListOf(StartResult.Started)),
    ) {
        var settings by mutableStateOf(initial)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                VozPanel(settings = settings, onSettingsChange = { settings = it }, localModels = models, localActions = actions)
            }
        }
    }

    private fun ui(installed: Set<String> = emptySet(), active: DownloadProgress? = null, selected: String? = null, ramMb: Long = 8_000) =
        LocalModelsUi.compose(installed = installed, active = active, failures = emptyMap(), selectedId = selected, deviceRamMb = ramMb)

    private fun local(modelId: String? = null) = Settings(source = TranscriptionSource.LOCAL, localModelId = modelId)

    @Test
    fun theFourStatesShowTheirActions() {
        val h = Harness(
            local(base.id),
            ui(installed = setOf(base.id, parakeet.id), active = DownloadProgress(small.id, DownloadState.RUNNING, 50, 100), selected = base.id),
        )
        rule.setContent { h.Content() }

        // selecionado (o card é a opção de rádio)
        rule.onNodeWithTag(localModelTag(base.id)).assertIsSelected()
        rule.onNodeWithTag(localDeleteTag(base.id)).assertExists()
        // baixando
        rule.onNodeWithTag(localProgressTag(small.id)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(localProgressTag(small.id)).assertTextContains("50%", substring = true)
        rule.onNodeWithTag(localCancelTag(small.id)).assertExists()
        // baixado, não selecionado
        rule.onNodeWithTag(localDeleteTag(parakeet.id)).assertExists()
        rule.onNodeWithTag(localModelTag(parakeet.id)).assertIsNotSelected()
    }

    @Test
    fun availableCanDownloadButNotWhileAnotherIsDownloading() {
        val actions = FakeActions(mutableListOf(StartResult.Started))
        val h = Harness(local(), ui(active = DownloadProgress(small.id, DownloadState.RUNNING, 1, 100)), actions)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(localDownloadTag(base.id)).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(localCancelTag(small.id)).performScrollTo().performClick()

        rule.runOnIdle { assertEquals(listOf(small.id), actions.cancels) }
    }

    @Test
    fun tappingAnInstalledModelSelectsIt() {
        val h = Harness(local(), ui(installed = setOf(small.id)))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(localModelTag(small.id)).performScrollTo().performClick()

        rule.runOnIdle { assertEquals(small.id, h.settings.localModelId) }
    }

    @Test
    fun deletingAsksFirstAndDeletingTheSelectedClearsTheSelection() {
        val h = Harness(local(base.id), ui(installed = setOf(base.id), selected = base.id))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(localDeleteTag(base.id)).performScrollTo().performClick()
        // A confirmação abre no fim do card (abaixo da dobra) e, dentro do card selecionável, só existe
        // na árvore não mesclada.
        rule.onNodeWithTag(localConfirmTag(base.id), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(VOZ_LOCAL_CONFIRM_NO_TAG).performScrollTo().performClick()
        rule.onNodeWithTag(localConfirmTag(base.id), useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle { assertTrue(h.actions.deletes.isEmpty()) }

        rule.onNodeWithTag(localDeleteTag(base.id)).performScrollTo().performClick()
        rule.onNodeWithTag(VOZ_LOCAL_CONFIRM_YES_TAG).performScrollTo().performClick()

        rule.runOnIdle {
            assertEquals(listOf(base.id), h.actions.deletes)
            assertEquals(null, h.settings.localModelId)
        }
    }

    @Test
    fun meteredNetworkAsksWithTheSizeThenDownloadsWithTheFlag() {
        val actions = FakeActions(mutableListOf(StartResult.NeedsMeteredConfirmation(parakeet.totalBytes), StartResult.Started))
        val h = Harness(local(), ui(), actions)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(localDownloadTag(parakeet.id)).performScrollTo().performClick()
        rule.waitUntil(5_000) { actions.downloads.isNotEmpty() }
        rule.onNodeWithText("Baixar ~639 MB por dados móveis?").assertExists()
        rule.onNodeWithTag(VOZ_LOCAL_CONFIRM_YES_TAG).performScrollTo().performClick()
        rule.waitUntil(5_000) { actions.downloads.size == 2 }

        rule.runOnIdle { assertEquals(listOf(parakeet.id to false, parakeet.id to true), actions.downloads) }
    }

    @Test
    fun notEnoughSpaceIsExplainedOnTheCard() {
        val actions = FakeActions(mutableListOf(StartResult.NotEnoughSpace(requiredBytes = 400L shl 20, freeBytes = 100L shl 20)))
        val h = Harness(local(), ui(), actions)
        rule.setContent { h.Content() }

        rule.onNodeWithTag(localDownloadTag(small.id)).performScrollTo().performClick()
        rule.waitUntil(5_000) { actions.downloads.isNotEmpty() }

        rule.onNodeWithTag(localNoticeTag(small.id)).assertTextContains("Espaço insuficiente", substring = true)
    }

    @Test
    fun heavyModelsAreFlaggedAndPrivacyNoteIsShown() {
        val h = Harness(local(), ui(ramMb = 1_500))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(localHeavyTag(parakeet.id)).assertExists()
        rule.onNodeWithTag(localHeavyTag(base.id)).assertDoesNotExist()
        rule.onNodeWithTag(VOZ_LOCAL_PRIVACY_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun languagePersistsInLocalLanguage() {
        val h = Harness(local(), ui())
        rule.setContent { h.Content() }

        rule.onNodeWithTag(VOZ_LOCAL_LANGUAGE_TAG).performScrollTo().performClick()
        rule.onNodeWithText("Português").performClick()

        rule.runOnIdle { assertEquals("pt", h.settings.localLanguage) }
    }
}
