package com.keyflow.transcription.local

import com.keyflow.data.models.EngineType
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelSpec
import java.io.File

/**
 * O modelo como o engine o vê: caminhos absolutos e idioma já resolvidos. Igualdade decide se o que está
 * carregado serve — trocar de modelo **ou de idioma** recarrega (NAT-09).
 */
data class EngineModel(
    val modelId: String,
    val engineType: EngineType,
    val encoder: String,
    val decoder: String,
    val joiner: String?,
    val tokens: String,
    val language: String?,
    val bytes: Long,
) {
    val files: List<String> get() = listOfNotNull(encoder, decoder, joiner, tokens)

    fun toParcel(): LocalModelConfig = LocalModelConfig().also {
        it.modelId = modelId
        it.engineType = engineType.name
        it.encoderPath = encoder
        it.decoderPath = decoder
        it.joinerPath = joiner
        it.tokensPath = tokens
        it.language = language
        it.modelBytes = bytes
    }

    companion object {
        /** Monta a partir do catálogo e do diretório instalado; idioma só vale se o modelo aceita dica. */
        fun from(spec: ModelSpec, dir: File, language: String?): EngineModel {
            fun path(role: FileRole) = spec.file(role)?.let { File(dir, it.fileName).absolutePath }
            return EngineModel(
                modelId = spec.id,
                engineType = spec.engineType,
                encoder = requireNotNull(path(FileRole.ENCODER)) { "${spec.id} sem encoder" },
                decoder = requireNotNull(path(FileRole.DECODER)) { "${spec.id} sem decoder" },
                joiner = path(FileRole.JOINER),
                tokens = requireNotNull(path(FileRole.TOKENS)) { "${spec.id} sem tokens" },
                language = language?.takeIf { spec.acceptsLanguageHint && it.isNotBlank() },
                bytes = spec.totalBytes,
            )
        }

        fun fromParcel(config: LocalModelConfig): EngineModel = EngineModel(
            modelId = config.modelId,
            engineType = EngineType.valueOf(config.engineType),
            encoder = config.encoderPath,
            decoder = config.decoderPath,
            joiner = config.joinerPath,
            tokens = config.tokensPath,
            language = config.language,
            bytes = config.modelBytes,
        )
    }
}

/** Tipos de erro que atravessam o AIDL (`IEngineCallback.onError(kind, …)`). */
enum class EngineErrorKind(val code: Int) {
    /** Algum arquivo do modelo não está no disco (apagado, instalação removida). */
    MODEL_MISSING(1),
    /** O áudio gravado não decodificou. */
    DECODE(2),
    /** Sem RAM para carregar (guarda prévia) ou `OutOfMemoryError` no caminho (NAT-07). */
    OOM(3),
    /** O sherpa-onnx recusou a config ou falhou na inferência. */
    ENGINE(4),
    ;

    companion object {
        fun fromCode(code: Int): EngineErrorKind = entries.firstOrNull { it.code == code } ?: ENGINE
    }
}

class EngineFailure(val kind: EngineErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Guarda de RAM antes de carregar (NAT-07): o ONNX Runtime ocupa mais que o arquivo (pesos + buffers de
 * ativação). ×1,5 do tamanho em disco, contra a memória disponível do sistema.
 */
object MemoryGuard {
    fun requiredBytes(modelBytes: Long): Long = modelBytes * 3 / 2

    fun enoughFor(modelBytes: Long, availableBytes: Long): Boolean = availableBytes >= requiredBytes(modelBytes)
}
