package com.keyflow.bancada.pontuacao

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PontuadoresVisaoTest {
    @Test
    fun `OCR mede erro de caracteres e reconhece texto vazio`() {
        val c = caso(esperado = "abc")
        assertThat(PontuadorOcr.pontuar(c, "abc").nota).isEqualTo(1.0)
        assertThat(PontuadorOcr.pontuar(c, "abx").nota).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(PontuadorOcr.pontuar(c, "").nota).isEqualTo(0.0)
    }

    @Test
    fun `objetos aceita sinonimos e penaliza omissoes e falsos positivos`() {
        val c = caso(esperado = lista("gato", "copo"), campos = arrayOf(
            "sinonimos" to obj("""{"gato":["cat","felino doméstico"],"copo":["glass"]}"""),
        ))
        assertThat(PontuadorObjetos.pontuar(c, "cat, glass").nota).isEqualTo(1.0)
        assertThat(PontuadorObjetos.pontuar(c, "um gato e um copo").nota).isEqualTo(1.0)
        assertThat(PontuadorObjetos.pontuar(c, "a cat and a glass").nota).isEqualTo(1.0)
        assertThat(PontuadorObjetos.pontuar(c, "um felino doméstico e um copo").nota).isEqualTo(1.0)
        assertThat(PontuadorObjetos.pontuar(c, "cat").nota).isWithin(1e-9).of(2.0 / 3.0)
        assertThat(PontuadorObjetos.pontuar(c, "cat, glass, carro").nota).isWithin(1e-9).of(0.8)
        assertThat(PontuadorObjetos.pontuar(c, "").nota).isEqualTo(0.0)
    }
}
