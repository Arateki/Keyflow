package com.keyflow.ui.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.core.TranscriptionSource
import com.keyflow.data.Settings
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Testes da aba Voz (T12 / VOZ-01..06): selecionar a origem persiste, Créditos mostra o cartão stub e
 * editar o token persiste via auto-save (ImeAction Done).
 */
@RunWith(AndroidJUnit4::class)
class VozPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private inner class Harness(initial: Settings) {
        var settings by mutableStateOf(initial)

        @Composable
        fun Content() {
            KeyFlowTheme(dark = false) {
                VozPanel(settings = settings, onSettingsChange = { settings = it })
            }
        }
    }

    @Test
    fun selectingSourcePersists() {
        val h = Harness(Settings(source = TranscriptionSource.CREDITS))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(sourceTag(TranscriptionSource.OPENAI)).performClick()

        rule.runOnIdle { assertEquals(TranscriptionSource.OPENAI, h.settings.source) }
    }

    /** MGR-01 (parcial, NM-T2): "Local" é a 5ª opção; escolhida, mostra o painel dela, sem campos de token. */
    @Test
    fun localIsTheFifthSourceAndShowsItsPanel() {
        val h = Harness(Settings(source = TranscriptionSource.CREDITS))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(sourceTag(TranscriptionSource.LOCAL)).assertIsDisplayed().performClick()

        rule.runOnIdle { assertEquals(TranscriptionSource.LOCAL, h.settings.source) }
        rule.onNodeWithTag(VOZ_LOCAL_PLACEHOLDER_TAG).assertIsDisplayed()
        rule.onAllNodesWithTag(VOZ_TOKEN_TAG).assertCountEquals(0)
    }

    @Test
    fun creditsShowsBalanceCard() {
        val h = Harness(Settings(source = TranscriptionSource.CREDITS))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(VOZ_CREDITS_CARD_TAG).assertIsDisplayed()
        rule.onAllNodesWithTag(VOZ_TOKEN_TAG).assertCountEquals(0)
    }

    @Test
    fun editingTokenPersistsOnImeDone() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI, openaiToken = ""))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(VOZ_TOKEN_TAG).performTextReplacement("sk-new-token")
        rule.onNodeWithTag(VOZ_TOKEN_TAG).performImeAction()

        rule.runOnIdle { assertEquals("sk-new-token", h.settings.openaiToken) }
    }

    @Test
    fun timeoutDefaultsTo15SecondsSelected() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(timeoutTag(15_000)).assertIsDisplayed()
    }

    @Test
    fun selectingTimeoutPersists() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(timeoutTag(30_000)).performClick()

        rule.runOnIdle { assertEquals(30_000, h.settings.transcriptionTimeoutMs) }
    }

    @Test
    fun timeoutSectionIsSharedAcrossEveryProvider() {
        // Não é por preset (CREDITS ainda mostra Geral, mesmo sendo um card stub sem token).
        val h = Harness(Settings(source = TranscriptionSource.CREDITS))
        rule.setContent { h.Content() }

        rule.onAllNodesWithTag(timeoutTag(15_000)).assertCountEquals(1)
    }

    @Test
    fun maxRecordingDefaultsTo5MinutesSelected() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(maxRecordingTag(300)).assertIsDisplayed()
    }

    @Test
    fun selectingMaxRecordingPersists() {
        val h = Harness(Settings(source = TranscriptionSource.OPENAI))
        rule.setContent { h.Content() }

        rule.onNodeWithTag(maxRecordingTag(600)).performClick()

        rule.runOnIdle { assertEquals(600, h.settings.maxRecordingSec) }
    }
}
