package com.keyflow.reasoning.local

import com.keyflow.BuildConfig
import com.keyflow.reasoning.ReasoningRequest

/**
 * Escolhe o [LlmBackend] de um modelo. O motor de eco só existe em debug e só para [ECHO_MODEL_ID]; o motor real
 * (LiteRT-LM) entra na RSN-T7 — até lá, qualquer outro modelo falha com uma mensagem clara em vez de fingir que roda.
 */
internal object ReasoningBackends {
    fun create(model: LlmModel): LlmBackend =
        if (BuildConfig.DEBUG && model.modelId == ECHO_MODEL_ID) EchoBackend() else NotIntegratedBackend(model.engineType)
}

private class NotIntegratedBackend(private val engineType: String) : LlmBackend {
    override fun load(model: LlmModel) =
        throw ReasoningFailure(ReasoningErrorKind.ENGINE, "o motor $engineType ainda não está integrado a este build")

    override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) = Unit
    override fun cancel() = Unit
    override fun unload() = Unit
}
