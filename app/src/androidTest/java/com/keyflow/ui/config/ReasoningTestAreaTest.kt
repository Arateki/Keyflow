package com.keyflow.ui.config

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import com.keyflow.reasoning.ReasoningRequest
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.reasoning.UnavailableReason
import com.keyflow.ui.theme.KeyFlowTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** RTS-01..04: a área do botão Testar na tela, com motores falsos que o teste controla evento a evento. */
@RunWith(AndroidJUnit4::class)
class ReasoningTestAreaTest {

    @get:Rule
    val rule = createComposeRule()

    /** O teste manda os eventos; o fluxo termina no `Done`/`Error`. `cancelled` = alguém parou a coleta. */
    private class ControlledEngine : ReasoningEngine {
        val events = Channel<ReasoningEvent>(Channel.UNLIMITED)
        @Volatile var cancelled = false
        @Volatile var requests = 0
        override fun generate(request: ReasoningRequest): Flow<ReasoningEvent> =
            events.receiveAsFlow()
                .transformWhile { emit(it); it !is ReasoningEvent.Done && it !is ReasoningEvent.Error }
                .onCompletion { if (it != null) cancelled = true }
                .also { requests++ }
    }

    private fun show(resolution: ReasoningResolution) {
        rule.setContent { KeyFlowTheme(dark = false) { ReasoningTestArea(resolution) } }
    }

    private fun waitForText(tag: String, text: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }?.contains(text) == true
            }
        }
    }

    @Test
    fun withoutAModelTheButtonIsDisabledAndTheReasonIsWrittenNextToIt() {
        show(ReasoningResolution.Unavailable(UnavailableReason.NO_LOCAL_MODEL))

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(REASONING_TEST_REASON_TAG).assertTextEquals(ReasoningTestMessages.unavailable(UnavailableReason.NO_LOCAL_MODEL))
    }

    @Test
    fun aReadyEngineEnablesTheButtonAndShowsNoReason() {
        show(ReasoningResolution.Ready(ControlledEngine()))

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertIsEnabled()
        rule.onAllNodesWithTag(REASONING_TEST_REASON_TAG).assertCountEquals(0)
    }

    @Test
    fun theAnswerArrivesInAFlowThenTheTimesAppearAndTheButtonComesBack() {
        val engine = ControlledEngine()
        show(ReasoningResolution.Ready(engine))

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertTextEquals("Testar").performClick()
        engine.events.trySend(ReasoningEvent.Delta("Olá"))
        waitForText(REASONING_TEST_RESPONSE_TAG, "Olá")
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertTextEquals("Parar")

        engine.events.trySend(ReasoningEvent.Delta(", mundo"))
        engine.events.trySend(ReasoningEvent.Done)
        waitForText(REASONING_TEST_RESPONSE_TAG, "Olá, mundo")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(REASONING_TEST_TIMES_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(REASONING_TEST_TIMES_TAG).assertTextContains("Primeiro trecho:", substring = true).assertTextContains("total:", substring = true)
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertTextEquals("Testar")
    }

    /** Parar no meio interrompe o motor de verdade (RRUN-06) e a resposta parcial fica na tela. */
    @Test
    fun stopCancelsTheEngineAndKeepsThePartialAnswer() {
        val engine = ControlledEngine()
        show(ReasoningResolution.Ready(engine))

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performClick()
        engine.events.trySend(ReasoningEvent.Delta("parcial"))
        waitForText(REASONING_TEST_RESPONSE_TAG, "parcial")

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertTextEquals("Parar").performClick()

        rule.waitUntil(5_000) { engine.cancelled }
        assertTrue(engine.cancelled)
        rule.onNodeWithTag(REASONING_TEST_RESPONSE_TAG).assertTextContains("parcial")
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertTextEquals("Testar")
    }

    @Test
    fun anErrorShowsAReadableMessageAndKeepsWhatAlreadyArrived() {
        val engine = ControlledEngine()
        show(ReasoningResolution.Ready(engine))

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performClick()
        engine.events.trySend(ReasoningEvent.Delta("xy"))
        engine.events.trySend(ReasoningEvent.Error(ReasoningError.RATE_LIMIT, "429"))

        waitForText(REASONING_TEST_ERROR_TAG, ReasoningTestMessages.error(ReasoningError.RATE_LIMIT))
        rule.onNodeWithTag(REASONING_TEST_RESPONSE_TAG).assertTextContains("xy")
        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).assertTextEquals("Testar")
    }

    /** Do clique ao texto, sem falsos: a área, o motor local real, o AIDL e o processo `:reasoning` com o eco de debug. */
    @Test
    fun withTheRealLocalEngineAndTheDebugEchoTheAnswerStreamsIn() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val connection = com.keyflow.reasoning.local.ReasoningConnection(context, lingerMs = 60_000)
        val echo = com.keyflow.reasoning.local.LlmModel(com.keyflow.reasoning.local.ECHO_MODEL_ID, "", "LITERT_LM", 0, 0)
        val engine = com.keyflow.reasoning.local.LocalReasoningEngine(echo.modelId, { echo }, connection)
        try {
            show(ReasoningResolution.Ready(engine))

            rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performClick()

            // O eco devolve o pedido: a frase de teste inteira tem de chegar.
            waitForText(REASONING_TEST_RESPONSE_TAG, "Diga olá em uma frase curta.")
            waitForText(REASONING_TEST_BUTTON_TAG, "Testar")
        } finally {
            connection.close()
        }
    }

    @Test
    fun testingTwiceInARowRunsTheEngineTwice() {
        val engine = ControlledEngine()
        show(ReasoningResolution.Ready(engine))

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performClick()
        engine.events.trySend(ReasoningEvent.Done)
        rule.waitUntil(5_000) { engine.requests == 1 }
        waitForText(REASONING_TEST_BUTTON_TAG, "Testar")

        rule.onNodeWithTag(REASONING_TEST_BUTTON_TAG).performClick()
        rule.waitUntil(5_000) { engine.requests == 2 }
    }
}
