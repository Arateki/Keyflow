package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test

/** O painel de confirmação sem tela (AUI-02): um pedido por vez, resposta só ao pedido certo, e cancelar fecha a pergunta. */
class ConfirmationGateTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private fun request(id: String = "c1", always: Boolean = true) =
        ConfirmationRequest(id, "skin_install", ToolRisk.WRITE, "Instalar a skin Neon", null, always, ToolOrigin.CHAT)

    private fun ask(gate: ConfirmationGate, request: ConfirmationRequest, into: MutableList<ConfirmationDecision>): Job =
        scope.launch { into += gate.request(request) }

    @Test
    fun `a request is published, waits, and the matching answer resolves it and closes the panel`() {
        val gate = ConfirmationGate()
        val answers = ArrayList<ConfirmationDecision>()
        ask(gate, request(), answers)
        assertThat(gate.pending.value?.summary).isEqualTo("Instalar a skin Neon")
        assertThat(answers).isEmpty()

        assertThat(gate.respond("c1", ConfirmationDecision.ALLOW_ALWAYS)).isTrue()
        assertThat(answers).containsExactly(ConfirmationDecision.ALLOW_ALWAYS)
        assertThat(gate.pending.value).isNull()
    }

    @Test
    fun `an answer for another call is ignored and the question stays open`() {
        val gate = ConfirmationGate()
        val answers = ArrayList<ConfirmationDecision>()
        ask(gate, request("c1"), answers)
        assertThat(gate.respond("c2", ConfirmationDecision.ALLOW)).isFalse()
        assertThat(answers).isEmpty()
        assertThat(gate.pending.value).isNotNull()
    }

    @Test
    fun `a late answer after the panel closed answers nothing, not the next request`() {
        val gate = ConfirmationGate()
        val answers = ArrayList<ConfirmationDecision>()
        ask(gate, request("c1"), answers)
        gate.respond("c1", ConfirmationDecision.DENY)
        ask(gate, request("c2"), answers)
        // O toque atrasado no painel de c1 não responde c2.
        assertThat(gate.respond("c1", ConfirmationDecision.ALLOW)).isFalse()
        assertThat(answers).containsExactly(ConfirmationDecision.DENY)
        assertThat(gate.pending.value?.callId).isEqualTo("c2")
    }

    @Test
    fun `cancelling the waiter closes the panel`() {
        val gate = ConfirmationGate()
        val answers = ArrayList<ConfirmationDecision>()
        val waiting = ask(gate, request(), answers)
        waiting.cancel()
        assertThat(gate.pending.value).isNull()
        assertThat(answers).isEmpty()
        assertThat(gate.respond("c1", ConfirmationDecision.ALLOW)).isFalse()
    }

    @Test
    fun `a second request while one is open is denied instead of trampling the first`() {
        val gate = ConfirmationGate()
        val first = ArrayList<ConfirmationDecision>()
        val second = ArrayList<ConfirmationDecision>()
        ask(gate, request("c1"), first)
        ask(gate, request("c2"), second)
        assertThat(second).containsExactly(ConfirmationDecision.DENY)
        assertThat(gate.pending.value?.callId).isEqualTo("c1")
        gate.respond("c1", ConfirmationDecision.ALLOW)
        assertThat(first).containsExactly(ConfirmationDecision.ALLOW)
    }

    @Test
    fun `the listener hears the request as soon as it opens`() {
        val gate = ConfirmationGate()
        val heard = ArrayList<String>()
        gate.onRequested = { heard += it.callId }
        ask(gate, request("c9"), ArrayList())
        assertThat(heard).containsExactly("c9")
    }

    @Test
    fun `nobody asked means nothing to answer`() {
        assertThat(ConfirmationGate().respond("c1", ConfirmationDecision.ALLOW)).isFalse()
    }
}
