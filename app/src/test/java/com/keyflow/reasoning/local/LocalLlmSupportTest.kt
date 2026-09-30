package com.keyflow.reasoning.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Review, Important 7: o LiteRT-LM só publica bibliotecas nativas para arm64-v8a e x86_64. Um aparelho ARM de 32 bits baixaria
 * 614 MB (ou 2,6 GB) de um modelo que nunca roda e só descobriria no primeiro Testar.
 */
class LocalLlmSupportTest {

    @Test
    fun `a 64 bit arm device is supported`() {
        assertThat(LocalLlmSupport.isSupported(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))).isTrue()
    }

    @Test
    fun `an x86_64 device is supported`() {
        assertThat(LocalLlmSupport.isSupported(arrayOf("x86_64", "x86"))).isTrue()
    }

    @Test
    fun `a 32 bit only device is not supported`() {
        assertThat(LocalLlmSupport.isSupported(arrayOf("armeabi-v7a", "armeabi"))).isFalse()
        assertThat(LocalLlmSupport.isSupported(arrayOf("x86"))).isFalse()
        assertThat(LocalLlmSupport.isSupported(emptyArray())).isFalse()
    }

    /** O que o build empacota é o que o LiteRT publica: as duas listas não podem divergir sem alguém notar. */
    @Test
    fun `the supported abis are exactly the ones the library ships`() {
        assertThat(LocalLlmSupport.SUPPORTED_ABIS).containsExactly("arm64-v8a", "x86_64")
    }
}
