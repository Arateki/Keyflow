package com.keyflow.reasoning.local

/**
 * O aparelho consegue rodar um modelo de texto local? O LiteRT-LM só traz bibliotecas nativas para [SUPPORTED_ABIS] (verificado
 * no AAR 0.16.1); num aparelho ARM de 32 bits a carga cai em `UnsatisfiedLinkError`. Por isso a aba Raciocínio nem oferece o
 * download ali — em vez de deixar baixar gigabytes de um modelo que nunca vai rodar.
 */
object LocalLlmSupport {
    val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

    /** [deviceAbis] é `Build.SUPPORTED_ABIS`. */
    fun isSupported(deviceAbis: Array<String>): Boolean = deviceAbis.any { it in SUPPORTED_ABIS }
}
