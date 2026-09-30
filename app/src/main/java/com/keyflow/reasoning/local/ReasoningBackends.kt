package com.keyflow.reasoning.local

import com.keyflow.BuildConfig
import com.keyflow.data.models.EngineType
import com.keyflow.reasoning.ReasoningRequest

/**
 * Escolhe o [LlmBackend] de um modelo (RRUN-08). O motor de eco só existe em debug e só para [ECHO_MODEL_ID]; os modelos
 * `LITERT_LM` rodam no [LiteRtBackend]; qualquer outro tipo falha com uma mensagem clara em vez de fingir que roda.
 * [cacheDir] é um diretório gravável do app: o LiteRT-LM o usa para acelerar a 2ª carga do mesmo modelo.
 */
internal object ReasoningBackends {
    fun create(model: LlmModel, cacheDir: String?): LlmBackend = when {
        BuildConfig.DEBUG && model.modelId == ECHO_MODEL_ID -> EchoBackend()
        model.engineType == EngineType.LITERT_LM.name -> LiteRtBackend(cacheDir)
        else -> NotIntegratedBackend(model.engineType)
    }
}

private class NotIntegratedBackend(private val engineType: String) : LlmBackend {
    override fun load(model: LlmModel) =
        throw ReasoningFailure(ReasoningErrorKind.ENGINE, "o motor $engineType ainda não está integrado a este build")

    override fun generate(request: ReasoningRequest, onDelta: (String) -> Unit) = Unit
    override fun cancel() = Unit
    override fun unload() = Unit
}
