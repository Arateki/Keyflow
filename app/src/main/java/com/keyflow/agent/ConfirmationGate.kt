package com.keyflow.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [Confirmation] que a tela implementa (AUI-02): o [ToolExecutor] chama [request], que publica o pedido em [pending] e SUSPENDE até o
 * usuário responder no painel ([respond]) — ou até a espera ser cancelada (o usuário tocou em Cancelar, limpou a conversa, saiu da
 * aba). Puro: a UI só observa [pending] e chama [respond].
 *
 * Falha fechado: sem resposta não há permissão. Um segundo pedido que chegue com um já aberto (o executor serializa as perguntas, então
 * só aconteceria com dois executores dividindo o mesmo painel) é NEGADO em vez de atropelar o primeiro, e uma resposta com o `callId`
 * de outro pedido (um toque atrasado no painel que já fechou) é ignorada — nunca responde o pedido seguinte.
 */
class ConfirmationGate : Confirmation {
    private val open = MutableStateFlow<ConfirmationRequest?>(null)

    /** O pedido que espera resposta agora (um só), ou `null`. */
    val pending: StateFlow<ConfirmationRequest?> = open.asStateFlow()

    private var answer: CompletableDeferred<ConfirmationDecision>? = null

    /** Quem conduz a conversa marca o cartão da chamada assim que o pedido nasce (o resumo e a prévia do app). Chamado na thread que pediu. */
    @Volatile
    var onRequested: ((ConfirmationRequest) -> Unit)? = null

    override suspend fun request(request: ConfirmationRequest): ConfirmationDecision {
        val deferred = CompletableDeferred<ConfirmationDecision>()
        synchronized(this) {
            if (answer != null) return ConfirmationDecision.DENY
            answer = deferred
            open.value = request
        }
        try {
            onRequested?.invoke(request)
            return deferred.await()
        } finally {
            synchronized(this) {
                if (answer === deferred) {
                    answer = null
                    open.value = null
                }
            }
        }
    }

    /** O usuário respondeu ao pedido [callId]. Devolve se havia esse pedido aberto (senão a resposta não vale nada). */
    fun respond(callId: String, decision: ConfirmationDecision): Boolean {
        val deferred = synchronized(this) {
            val current = open.value
            if (current == null || current.callId != callId) return false
            answer
        }
        return deferred?.complete(decision) == true
    }
}
