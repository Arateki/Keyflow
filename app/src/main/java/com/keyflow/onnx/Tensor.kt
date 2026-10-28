package com.keyflow.onnx

/**
 * Tensor que atravessa a fronteira do runtime ONNX: só `Float` e `Long`, o que o OCR (e, adiante, a síntese de fala) precisa.
 * Dados em arrays do Kotlin, em ordem de linha (C); o runtime copia de/para a memória dele.
 */
sealed class Tensor {
    abstract val shape: LongArray

    /** Número de elementos que a [shape] pede. */
    val elementCount: Long get() = shape.fold(1L) { acc, d -> acc * d }

    protected fun requireMatches(size: Int) {
        require(shape.all { it >= 0 }) { "forma com dimensão negativa: ${shape.joinToString()}" }
        require(elementCount == size.toLong()) { "forma ${shape.joinToString("x")} pede $elementCount elementos, há $size" }
    }
}

class FloatTensor(override val shape: LongArray, val data: FloatArray) : Tensor() {
    init { requireMatches(data.size) }
}

class LongTensor(override val shape: LongArray, val data: LongArray) : Tensor() {
    init { requireMatches(data.size) }
}

/** Falha do runtime ONNX (modelo inválido, forma incompatível, sem memória nativa, runtime ausente). */
class OnnxException(message: String, cause: Throwable? = null) : Exception(message, cause)
