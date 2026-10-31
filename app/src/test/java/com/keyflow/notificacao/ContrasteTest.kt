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
        for (alfa in 0..255) for (t in cores) for (f in cores) {
            val legivel = Contraste.legivel((alfa shl 24) or (t and 0xFFFFFF), f)
            assertThat(legivel ushr 24).isEqualTo(255)
            assertThat(Contraste.razao(legivel, f)).isAtLeast(Contraste.MINIMO)
        }
    }

    @Test
    fun `tinta escura transparente sobre creme vira fallback opaco`() {
        assertThat(Contraste.legivel(0x001B1B1B, atelieClaro)).isEqualTo(0xFF1B1B1B.toInt())
    }

    @Test
    fun `tinta clara transparente sobre escuro vira fallback opaco`() {
        assertThat(Contraste.legivel(0x00FFFFFF, atelieEscuro)).isEqualTo(0xFFFFFFFF.toInt())
    }

    @Test
    fun `tinta semitransparente legível preserva a composição e fica opaca`() {
        assertThat(Contraste.legivel(0x80000000.toInt(), 0xFFFFFFFF.toInt())).isEqualTo(0xFF7F7F7F.toInt())
        assertThat(Contraste.legivel(0x80FFFFFF.toInt(), 0xFF000000.toInt())).isEqualTo(0xFF808080.toInt())
    }

    @Test
    fun `tinta de baixa opacidade cai no fallback mesmo com RGB contrastante`() {
        assertThat(Contraste.legivel(0x10000000, 0xFFFFFFFF.toInt())).isEqualTo(0xFF1B1B1B.toInt())
    }
}
