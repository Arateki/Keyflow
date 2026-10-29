package com.keyflow.speech.frontend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Inteiro simples vira cardinal pt-BR; sigla com dígito é soletrada. O resto passa. */
class PortugueseSpeechNormalizerTest {

    @Test
    fun `simple integers become portuguese cardinals`() {
        assertThat(n("2")).isEqualTo("dois")
        assertThat(n("1")).isEqualTo("um")
        assertThat(n("10")).isEqualTo("dez")
        assertThat(n("14")).isEqualTo("quatorze")
        assertThat(n("16")).isEqualTo("dezesseis")
        assertThat(n("21")).isEqualTo("vinte e um")
        assertThat(n("100")).isEqualTo("cem")
        assertThat(n("101")).isEqualTo("cento e um")
        assertThat(n("1000")).isEqualTo("mil")
        assertThat(n("1100")).isEqualTo("mil e cem")
        assertThat(n("1101")).isEqualTo("mil cento e um")
        assertThat(n("2026")).isEqualTo("dois mil e vinte e seis")
        assertThat(n("2100")).isEqualTo("dois mil e cem")
        assertThat(n("2321")).isEqualTo("dois mil trezentos e vinte e um")
    }

    @Test
    fun `a leading zero or a long run is spelled digit by digit`() {
        assertThat(n("007")).isEqualTo("zero zero sete")
        assertThat(n("1000000")).isEqualTo("um zero zero zero zero zero zero")
    }

    @Test
    fun `a sigla with a digit is spelled and a known acronym stays`() {
        assertThat(n("A32")).isEqualTo("A três dois")
        assertThat(n("tenho 2 cães")).isEqualTo("tenho dois cães")
        assertThat(PortugueseSpeechNormalizer.normalize("ONU") { true }).isEqualTo("ONU")
    }

    private fun n(text: String) = PortugueseSpeechNormalizer.normalize(text)
}
