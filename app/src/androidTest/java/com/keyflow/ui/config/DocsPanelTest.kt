package com.keyflow.ui.config

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Teste da aba Docs (T15 / MON-01, REC-07): conteúdo-chave e a seção de pausa por modo renderizam. */
@RunWith(AndroidJUnit4::class)
class DocsPanelTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rendersStepsAndPauseSection() {
        rule.setContent { KeyFlowTheme(dark = false) { DocsPanel() } }

        rule.onNodeWithText("Fale").assertIsDisplayed()
        rule.onNodeWithText("Transcreve").assertIsDisplayed()
        rule.onNodeWithText("Cola").assertIsDisplayed()
        // O painel rola (`verticalScroll`): em tela menor que a do emulador a seção de pausa nasce fora
        // da viewport e `assertIsDisplayed` falha sem haver defeito nenhum (A32 do Yan, 2026-09-08).
        rule.onNodeWithTag(DOCS_PAUSE_TAG).performScrollTo().assertIsDisplayed()
    }
}
