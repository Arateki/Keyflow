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
}
