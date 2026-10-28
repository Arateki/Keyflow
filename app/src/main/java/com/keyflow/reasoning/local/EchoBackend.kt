package com.keyflow.reasoning.local

import com.keyflow.core.ComputeBackend
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

    /**
     * Só a CPU carrega: o eco finge um aparelho cuja GPU recusa o modelo. É o que permite provar, no emulador e sem modelo de GB, a
     * volta para a CPU de ponta a ponta (núcleo, AIDL, preferência gravada, aviso no card).
     */
    override fun load(model: LlmModel) {
        if (model.backend != ComputeBackend.CPU) {
            throw ReasoningFailure(ReasoningErrorKind.ENGINE, "a ${model.backend} simulada do eco não carrega o modelo")
        }
    }

    override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) {
        request.user.split(" ").filter { it.isNotEmpty() }.forEachIndexed { index, word ->
            if (cancelled) return
            onDelta(if (index == 0) word else " $word")
            Thread.sleep(ECHO_DELAY_MS)
        }
    }

    override fun prepareGeneration() { cancelled = false }

    override fun cancel() {
        cancelled = true
    }

    override fun unload() = Unit

    companion object {
        const val ECHO_DELAY_MS = 30L
    }
}
