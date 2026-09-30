package com.keyflow.reasoning.local

/**
 * O modelo de texto como o processo `:reasoning` o vê: caminho absoluto e limites já resolvidos. Igualdade e
 * [modelId] decidem se o que está carregado serve; trocar de modelo descarrega o anterior (RRUN-03).
 */
data class LlmModel(
    val modelId: String,
    val modelPath: String,
    val engineType: String,
    val idleUnloadMs: Long,
    val ramEstimateMb: Int,
) {
    fun toParcel(): LocalLlmConfig = LocalLlmConfig().also {
        it.modelId = modelId
        it.modelPath = modelPath
        it.engineType = engineType
        it.idleUnloadMs = idleUnloadMs
        it.ramEstimateMb = ramEstimateMb
    }

    companion object {
        fun fromParcel(config: LocalLlmConfig): LlmModel = LlmModel(
            modelId = config.modelId,
            modelPath = config.modelPath,
            engineType = config.engineType,
            idleUnloadMs = config.idleUnloadMs,
            ramEstimateMb = config.ramEstimateMb,
        )
    }
}

/** Tipos de erro que atravessam o AIDL (`IReasoningCallback.onError(kind, …)`). Códigos estáveis. */
enum class ReasoningErrorKind(val code: Int) {
    /** O arquivo do modelo não está no disco (apagado, instalação removida). */
    MODEL_MISSING(1),
    /** Sem RAM para carregar (guarda prévia) ou `OutOfMemoryError` no caminho (RRUN-04). */
    OOM(2),
    /** O motor recusou o modelo ou falhou na geração. */
    ENGINE(3),
    ;

    companion object {
        fun fromCode(code: Int): ReasoningErrorKind = entries.firstOrNull { it.code == code } ?: ENGINE
    }
}

/** Falha com tipo — o núcleo a converte em `onError(kind, message)`. */
class ReasoningFailure(val kind: ReasoningErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)
