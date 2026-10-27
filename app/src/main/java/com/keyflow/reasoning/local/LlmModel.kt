package com.keyflow.reasoning.local

import com.keyflow.core.ComputeBackend

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
    /**
     * Identidade da versão do arquivo (SHA-256 do catálogo). Dois modelos com o mesmo [modelId] e [version] diferentes são
     * arquivos diferentes: o motor residente não serve (a atualização do catálogo troca o arquivo sob o mesmo id).
     */
    val version: String = "",
    /** Onde o modelo roda. Faz parte da identidade do motor residente: trocar de CPU para GPU descarrega e recarrega. */
    val backend: ComputeBackend = ComputeBackend.CPU,
) {
    fun toParcel(): LocalLlmConfig = LocalLlmConfig().also {
        it.modelId = modelId
        it.modelPath = modelPath
        it.engineType = engineType
        it.idleUnloadMs = idleUnloadMs
        it.ramEstimateMb = ramEstimateMb
        it.version = version
        it.backend = backend.name
    }

    companion object {
        fun fromParcel(config: LocalLlmConfig): LlmModel = LlmModel(
            modelId = config.modelId,
            modelPath = config.modelPath,
            engineType = config.engineType,
            idleUnloadMs = config.idleUnloadMs,
            ramEstimateMb = config.ramEstimateMb,
            version = config.version.orEmpty(),
            backend = ComputeBackend.fromName(config.backend),
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
