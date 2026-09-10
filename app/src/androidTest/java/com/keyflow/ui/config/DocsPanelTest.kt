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

    /**
     * DOC-01: cancelar/desfazer, rajada configurável e os controles da notificação chegaram na fase 2 de
     * gestos sem passar pela Docs, que ficou descrevendo um app que não existe mais.
     */
    @Test
    fun documentsTheGesturesThatArrivedAfterTheOriginalText() {
        rule.setContent { KeyFlowTheme(dark = false) { DocsPanel() } }

        rule.onNodeWithTag(DOCS_CANCEL_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DOCS_NOTIFICATION_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theShortcutSectionDescribesTheRealRules() {
        rule.setContent { KeyFlowTheme(dark = false) { DocsPanel() } }

        rule.onNodeWithTag(DOCS_SHORTCUTS_TAG).performScrollTo()
        // A tag fica na seção, cujo nó não funde o texto dos filhos — a asserção vai no nó do texto.
        // O texto antigo dizia que 1 toque grava e nem mencionava os 2 toques; os defaults reais são
        // 1 sem atalho (e só no Push-to-talk), 2 retrair/expandir, 3 trocar de teclado.
        rule.onNodeWithText("2 toques retraem ou expandem", substring = true).assertIsDisplayed()
        rule.onNodeWithText("só existe no modo Push-to-talk", substring = true).assertIsDisplayed()
    }
}
