package com.keyflow.reasoning.local

import com.keyflow.reasoning.ReasoningRequest

/** Id do modelo falso que só existe em build de debug: o teste de IPC roda sem modelo de GB (RSN-T5/T6). */
const val ECHO_MODEL_ID = "debug-echo"

/**
 * Motor de eco (só debug): devolve as palavras do pedido, uma a cada [ECHO_DELAY_MS], como se fossem trechos. Sem arquivo
 * de modelo. Serve para exercitar o AIDL, o fluxo, o cancelamento e a morte do processo sem carregar um LLM de verdade.
 * A concatenação dos trechos é o texto original (as palavras depois da 1ª chegam com um espaço na frente).
 */
internal class EchoBackend : LlmBackend {
    override val requiresModelFile: Boolean = false

    @Volatile private var cancelled = false

    override fun load(model: LlmModel) = Unit

    override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) {
        cancelled = false
        request.user.split(" ").filter { it.isNotEmpty() }.forEachIndexed { index, word ->
            if (cancelled) return
            onDelta(if (index == 0) word else " $word")
            Thread.sleep(ECHO_DELAY_MS)
        }
    }

    override fun cancel() {
        cancelled = true
    }

    override fun unload() = Unit

    companion object {
        const val ECHO_DELAY_MS = 30L
    }
}
