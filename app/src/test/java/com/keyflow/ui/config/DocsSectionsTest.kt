package com.keyflow.ui.config

import com.keyflow.core.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DOC-02..04: a Docs é uma lista de dados — o índice sai dela, as tags antigas continuam valendo e o
 * e-mail de feedback carrega o diagnóstico.
 */
class DocsSectionsTest {

    private val ids = docsSections.map { it.id }

    @Test
    fun sectionIdsAreUniqueSoEveryIndexItemHasOneTarget() {
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun tagsThatExistedBeforeTheListStillPointAtTheirSections() {
        val tags = ids.map(::docsSectionTag)
        listOf(DOCS_PAUSE_TAG, DOCS_SHORTCUTS_TAG, DOCS_CANCEL_TAG, DOCS_NOTIFICATION_TAG).forEach {
            assertTrue("$it sumiu", it in tags)
        }
    }

    @Test
    fun detailSectionsRequiredByDoc03Exist() {
        listOf("gestos", "teclado", "notificacao", "erros").forEach {
            assertTrue("seção $it ausente", it in ids)
        }
    }

    @Test
    fun feedbackIsTheLastSectionAndTheOnlyOneWithActions() {
        assertEquals("feedback", ids.last())
        assertEquals(listOf("feedback"), docsSections.filter { it.feedbackActions }.map { it.id })
    }

    @Test
    fun orbGesturesQuoteTheSameSentencesAsTheModeTab() {
        val texts = docsSections.single { it.id == "gestos" }.blocks
            .filterIsInstance<DocBlock.Labeled>()
            .map { it.text }
        assertTrue(pauseHintFor(RecordingMode.TOGGLE) in texts)
        assertTrue(pauseHintFor(RecordingMode.PUSH_TO_TALK) in texts)
    }

    @Test
    fun bugAndSuggestionHaveDistinctSubjects() {
        assertNotEquals(FeedbackKind.BUG.subject, FeedbackKind.SUGGESTION.subject)
    }

    @Test
    fun feedbackBodyCarriesDiagnosticsBelowTheUserSpace() {
        val diag = FeedbackDiagnostics(appVersion = "0.1.0 (1)", android = "14 (API 34)", device = "samsung SM-A325M")
        val body = feedbackBody(FeedbackKind.BUG, diag)

        assertTrue(body.startsWith(FeedbackKind.BUG.prompt))
        assertTrue(body.contains("KeyFlow 0.1.0 (1)"))
        assertTrue(body.contains("Android 14 (API 34)"))
        assertTrue(body.contains("samsung SM-A325M"))
        assertTrue(body.indexOf(FeedbackKind.BUG.prompt) < body.indexOf("KeyFlow 0.1.0"))
    }
}
