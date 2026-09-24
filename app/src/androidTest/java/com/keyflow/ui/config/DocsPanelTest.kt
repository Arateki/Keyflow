package com.keyflow.ui.config

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.ui.theme.KeyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── GT-T9: índice, seções de detalhe e feedback ─────────────────────────────

    /** DOC-02: o índice sai da mesma lista que desenha a aba — toda seção tem item. */
    @Test
    fun indexListsEverySection() {
        rule.setContent { KeyFlowTheme(dark = false) { DocsPanel() } }

        rule.onNodeWithTag(DOCS_INDEX_TAG).assertIsDisplayed()
        docsSections.forEach { rule.onNodeWithTag(docsIndexItemTag(it.id)).assertExists() }
    }

    /**
     * DOC-02 + edge case do spec: a rolagem vai à posição medida da seção, inclusive com a aba já rolada
     * (primeiro desce até Erros, depois volta a Atalhos pelo índice).
     */
    @Test
    fun tappingAnIndexItemScrollsToThatSectionEvenWhenAlreadyScrolled() {
        rule.setContent { KeyFlowTheme(dark = false) { DocsPanel(Modifier.testTag(PANEL)) } }
        val panelTop = rule.onNodeWithTag(PANEL).getUnclippedBoundsInRoot().top

        rule.onNodeWithTag(docsSectionTag("erros")).assertIsNotDisplayed()
        rule.onNodeWithTag(docsIndexItemTag("erros")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(docsSectionTag("erros")).assertIsDisplayed()

        // O índice saiu da tela com a rolagem; volta ao topo dele pelo scroll e escolhe outra seção.
        rule.onNodeWithTag(docsIndexItemTag("atalhos")).performScrollTo().performClick()
        rule.waitForIdle()
        val top = rule.onNodeWithTag(docsSectionTag("atalhos")).getUnclippedBoundsInRoot().top
        // A tag fica depois do respiro de 13dp da seção; a âncora (antes dele) encosta no topo do painel.
        val offset = top - panelTop
        assertTrue("seção a $offset do topo", offset.value in 11f..15f)
    }

    /** DOC-04: "Reportar bug" abre um mailto com destinatário, assunto do tipo e diagnóstico no corpo. */
    @Test
    fun reportBugSendsAMailtoWithSubjectAndDiagnostics() {
        val sent = mutableListOf<Intent>()
        rule.setContent { KeyFlowTheme(dark = false) { DocsPanel(sendFeedback = { sent += it }) } }

        rule.onNodeWithTag(FeedbackKind.BUG.tag).performScrollTo().performClick()

        assertEquals(1, sent.size)
        val intent = sent.single()
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("mailto", intent.data?.scheme)
        assertTrue(intent.data.toString().startsWith("mailto:$FEEDBACK_EMAIL"))
        assertEquals(FEEDBACK_EMAIL, intent.getStringArrayExtra(Intent.EXTRA_EMAIL)?.single())
        assertEquals(FeedbackKind.BUG.subject, intent.getStringExtra(Intent.EXTRA_SUBJECT))
        val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(body.contains(Build.MODEL))
        assertTrue(body.contains("API ${Build.VERSION.SDK_INT}"))
        rule.onNodeWithTag(DOCS_FEEDBACK_NO_APP_TAG).assertDoesNotExist()
    }

    @Test
    fun suggestionUsesItsOwnSubject() {
        val sent = mutableListOf<Intent>()
        rule.setContent { KeyFlowTheme(dark = false) { DocsPanel(sendFeedback = { sent += it }) } }

        rule.onNodeWithTag(FeedbackKind.SUGGESTION.tag).performScrollTo().performClick()

        assertEquals(FeedbackKind.SUGGESTION.subject, sent.single().getStringExtra(Intent.EXTRA_SUBJECT))
    }

    /** DOC-05: sem app de e-mail, aviso com o endereço — nunca crash. */
    @Test
    fun withoutAnEmailAppShowsTheAddressInstead() {
        rule.setContent {
            KeyFlowTheme(dark = false) { DocsPanel(sendFeedback = { throw ActivityNotFoundException() }) }
        }

        rule.onNodeWithTag(FeedbackKind.BUG.tag).performScrollTo().performClick()

        rule.onNodeWithTag(DOCS_FEEDBACK_NO_APP_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(FEEDBACK_EMAIL, substring = true).assertIsDisplayed()
    }

    private companion object {
        const val PANEL = "docs-panel-test"
    }
}
