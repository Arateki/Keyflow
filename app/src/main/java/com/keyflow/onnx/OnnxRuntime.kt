package com.keyflow.onnx

import ai.onnxruntime.OrtEnvironment

/**
 * A ONNX Runtime que o app tem: a `libonnxruntime.so` do AAR do sherpa-onnx (uma só por ABI) mais a ponte JNI do
 * `onnxruntime-android` — ver `prepareOrtJava` no `app/build.gradle.kts` e o STATE.md (VC-T2b). Quem usa o runtime pergunta
 * [available] antes: a carga das bibliotecas nativas falha (ABI sem a `.so`, JNI de outra série, etiqueta de versão
 * recusada pelo linker) e isso tem de virar "sem OCR", não crash.
 *
 * Os processos `:vision` e `:speech` carregam a JNI; o `:inference` (sherpa) nunca toca neste objeto.
 */
object OnnxRuntime {

    /** Resultado da sondagem: a versão da ORT que respondeu, ou o motivo de não haver. */
    internal class Probe(val version: String?, val failure: String?)

    @Volatile private var cached: Probe? = null

    /** A ORT carregou e respondeu com uma versão. Falso, sem exceção, se a nativa não carregou ou a versão veio vazia. */
    val available: Boolean get() = probe().version != null

    /** Versão da ORT nativa (ex.: "1.28.2"), ou `null` se indisponível. */
    val version: String? get() = probe().version

    /** Por que [available] é falso (para o log e para o aviso); `null` quando disponível. */
    val unavailableReason: String? get() = probe().failure

    internal fun environment(): OrtEnvironment {
        if (!available) throw OnnxException("ONNX Runtime indisponível: ${unavailableReason}")
        return OrtEnvironment.getEnvironment()
    }

    private fun probe(): Probe = cached ?: synchronized(this) {
        cached ?: probeWith { OrtEnvironment.getEnvironment().version }.also { cached = it }
    }

    /**
     * A sondagem em si, com o carregador injetável (teste na JVM sem a nativa). Tudo que sobe do carregador vira [Probe] de falha:
     * `UnsatisfiedLinkError` e `NoClassDefFoundError` (a nativa não carregou) são `Error`, não `Exception`.
     */
    internal fun probeWith(loadVersion: () -> String?): Probe = try {
        val version = loadVersion()?.trim().orEmpty()
        if (version.isEmpty()) Probe(null, "a ONNX Runtime respondeu sem versão") else Probe(version, null)
    } catch (e: Throwable) {
        Probe(null, "${e.javaClass.simpleName}: ${e.message}".take(MAX_REASON))
    }

    /** Esquece a sondagem (testes). */
    internal fun resetForTest() {
        cached = null
    }

    private const val MAX_REASON = 300
}
