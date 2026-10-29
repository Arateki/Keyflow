package com.keyflow.speech.local

/** Códigos que atravessam o AIDL. A frase da tela é traduzida no processo principal, não aqui. */
object SpeechProtocol {
    const val MODEL_MISSING = 1
    const val ENGINE = 2
    const val OUT_OF_MEMORY = 3
    const val NOT_ENOUGH_RAM = 4

    /** Sem `speak` por este tempo, a sessão ONNX fecha (FLOC-04). */
    const val IDLE_UNLOAD_MS = 120_000L
}
