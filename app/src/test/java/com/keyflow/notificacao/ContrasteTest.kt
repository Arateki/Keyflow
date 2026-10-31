package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** NTF-T9: o contraste mínimo dos glifos dos botões sobre o fundo da skin. */
class ContrasteTest {
    private val atelieEscuro = 0xFF2B2720.toInt()
    private val atelieClaro = 0xFFF1E7C9.toInt()

    @Test
    fun `razao conhecida - preto no branco e 21 e igual a ela mesma e 1`() {
        assertThat(Contraste.razao(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isWithin(0.01).of(21.0)
        assertThat(Contraste.razao(atelieEscuro, atelieEscuro)).isWithin(0.001).of(1.0)
    }

    @Test
    fun `tinta igual ao fundo (o Atelie escuro) vira uma cor que se le`() {
        val t = Contraste.legivel(atelieEscuro, atelieEscuro)
        assertThat(Contraste.razao(t, atelieEscuro)).isAtLeast(Contraste.MINIMO)
        val c = Contraste.legivel(atelieClaro, atelieClaro)
        assertThat(Contraste.razao(c, atelieClaro)).isAtLeast(Contraste.MINIMO)
    }

    @Test
    fun `uma tinta que ja contrasta fica como esta`() {
        val tinta = 0xFFF1E7C9.toInt()
        assertThat(Contraste.legivel(tinta, atelieEscuro)).isEqualTo(tinta)
    }

    @Test
    fun `qualquer par resulta em contraste minimo`() {
        val cores = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF7A7A7A.toInt(), 0xFF2B2720.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        for (t in cores) for (f in cores) assertThat(Contraste.razao(Contraste.legivel(t, f), f)).isAtLeast(Contraste.MINIMO)
    }
}
