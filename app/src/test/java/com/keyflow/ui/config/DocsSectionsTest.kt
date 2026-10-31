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

    /** STO-03: a Docs atribui o motor e cada modelo do catálogo. */
    @Test
    fun licensesNameTheEngineAndEveryCatalogModel() {
        val labels = docsSections.single { it.id == "licencas" }.blocks.filterIsInstance<DocBlock.Labeled>().map { it.label }
        listOf("sherpa-onnx", "ONNX Runtime", "Whisper", "Parakeet TDT 0.6B v3").forEach {
            assertTrue("licença de $it ausente", it in labels)
        }
    }

    /** RPRV-02: o motor dos modelos de texto e cada um deles (RSN) também são atribuídos na Docs — o texto vem do catálogo, sem lista paralela. */
    @Test
    fun licensesAlsoNameTheReasoningEngineAndEveryTextModel() {
        val labels = docsSections.single { it.id == "licencas" }.blocks.filterIsInstance<DocBlock.Labeled>().map { it.label }
        assertTrue("licença do LiteRT-LM ausente", "LiteRT-LM" in labels)
        val catalog = com.keyflow.data.models.ModelCatalog
        // O que o release oferece: desde 2026-10-27 são os cinco modelos de texto, todos citados na Docs.
        catalog.TEXT_ALL.filter { it.validatedOnDevice }.forEach { spec ->
            assertTrue("licença de ${spec.modelName} ausente", labels.any { it.contains(spec.modelName) })
        }
        catalog.TEXT_ALL.filterNot { it.validatedOnDevice }.forEach { spec ->
            assertTrue("${spec.modelName} não está no release: a Docs não pode citá-lo", labels.none { it.contains(spec.modelName) })
        }
    }

    /** SKP-T1: o renderizador dos SVGs das skins importadas (AndroidSVG, Apache 2.0) é atribuído na Docs. */
    @Test
    fun licensesAlsoNameTheSvgRenderer() {
        val labels = docsSections.single { it.id == "licencas" }.blocks.filterIsInstance<DocBlock.Labeled>().map { it.label }
        assertTrue("licença do AndroidSVG ausente", "AndroidSVG" in labels)
    }

    @Test
    fun feedbackIsTheLastSectionAndTheOnlyOneWithActions() {
        assertEquals("feedback", ids.last())
        assertEquals(listOf("feedback"), docsSections.filter { it.feedbackActions }.map { it.id })
    }

    @Test
    fun privacyTabHasItsHelpSectionInDocs() {
        val privacy = docsSections.single { it.id == "privacidade" }
        assertEquals(privacy, docsSectionFor(ConfigTab.PRIVACIDADE))
        assertTrue("a ajuda sobre o destino do áudio deve continuar na Docs", privacy.blocks.isNotEmpty())
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

    /**
     * Visão e Voz estão sem entradas no catálogo desde 2026-10-24 (os pins eram inventados). Quando voltarem, cada modelo
     * novo tem de ser atribuído na Docs — o texto vem do catálogo, sem lista paralela, como no teste dos modelos de texto.
     */
    @Test
    fun licensesAlsoNameEveryVisionAndVoiceModelOfTheCatalog() {
        val labels = docsSections.single { it.id == "licencas" }.blocks.filterIsInstance<DocBlock.Labeled>().map { it.label }
        val catalog = com.keyflow.data.models.ModelCatalog
        // O que o release oferece; o OCR (PaddleOCR) só entra na Docs quando for validado no aparelho (VC-T6), como os modelos de texto.
        (catalog.VISION_ALL.filter { it.validatedOnDevice } + catalog.VOICE.filter { it.validatedOnDevice }).forEach { spec ->
            assertTrue("licença de ${spec.modelName} ausente", labels.any { it.contains(spec.modelName) })
        }
        catalog.VOICE.filterNot { it.validatedOnDevice }.forEach { spec ->
            assertTrue("${spec.modelName} ainda não está no release: a Docs não pode citá-lo", labels.none { it.contains(spec.modelName) })
        }
        catalog.VISION_ALL.filterNot { it.validatedOnDevice }.forEach { spec ->
            assertTrue("${spec.modelName} não está no release: a Docs não pode citá-lo", labels.none { it.contains(spec.modelName) })
        }
    }

    /** Raciocínio mantém ajuda; as abas ainda não entregues ficam sem "?" até terem conteúdo real. */
    @Test
    fun reasoningHasHelpAndDeferredTabsDoNot() {
        val rSection = docsSectionFor(ConfigTab.RACIOCINIO)
        assertTrue("Raciocínio sem seção", rSection != null)
        assertEquals("aba-raciocinio", rSection!!.id)
        assertTrue(rSection.blocks.isNotEmpty())

        listOf(ConfigTab.KEYFLOW, ConfigTab.VISAO, ConfigTab.FALA, ConfigTab.NOTIFICACAO, ConfigTab.DOCS, ConfigTab.COMPUTADOR).forEach {
            assertEquals("$it não deveria ter seção ainda", null, docsSectionFor(it))
        }
    }

    /** FLX-55: a aba Fluxos tem a seção própria, e ela não promete o que a aba não faz (notificação, grafo, Computador). */
    @Test
    fun fluxosTabHasItsSectionAndDoesNotPromiseWhatItLacks() {
        val secao = docsSectionFor(ConfigTab.FLUXOS)
        assertEquals("aba-fluxos", secao?.id)
        val texto = secao!!.blocks.joinToString(" ") { b ->
            when (b) {
                is DocBlock.Paragraph -> b.text
                is DocBlock.Labeled -> b.label + " " + b.text
                else -> ""
            }
        }.lowercase()
        listOf("ouvir e responder", "restaurar", "atalho", "substitui", "etapa").forEach { assertTrue("falta $it", it in texto) }
        listOf("notificação", "grafo", "computador").forEach { assertTrue("promete $it", it !in texto) }
    }

    @Test
    fun deferredCapabilitySectionsAreNotListedAsAvailableDocumentation() {
        assertTrue("Visão ainda aparece na Docs", "aba-visao" !in ids)
        assertTrue("Fala ainda aparece na Docs", "aba-voz" !in ids)
    }
}
