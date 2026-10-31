package com.keyflow.notificacao

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** NTF-T7: as contas do orçamento de bitmaps (NTF-27). */
class OrcamentoDeBitmapsTest {
    @Test
    fun `lado e o menor entre 192 e 40 vezes a densidade`() {
        assertThat(OrcamentoDeBitmaps.ladoPx(1f)).isEqualTo(40)
        assertThat(OrcamentoDeBitmaps.ladoPx(2f)).isEqualTo(80)
        assertThat(OrcamentoDeBitmaps.ladoPx(2.625f)).isEqualTo(105)
        assertThat(OrcamentoDeBitmaps.ladoPx(3f)).isEqualTo(120)
        assertThat(OrcamentoDeBitmaps.ladoPx(4f)).isEqualTo(160)
        assertThat(OrcamentoDeBitmaps.ladoPx(6f)).isEqualTo(192)
        assertThat(OrcamentoDeBitmaps.ladoPx(0f)).isEqualTo(1)
    }

    @Test
    fun `a soma de conteudo mais publica cabe em 1 MB e no alvo de 256 KB de 1x a 4x`() {
        for (d in listOf(1f, 1.5f, 2f, 2.625f, 3f, 3.5f, 4f)) {
            val lado = OrcamentoDeBitmaps.ladoPx(d)
            val soma = OrcamentoDeBitmaps.somaDaNotificacao(lado)
            assertThat(soma).isAtMost(OrcamentoDeBitmaps.TETO_DA_SOMA_BYTES.toLong())
            assertThat(soma).isAtMost(OrcamentoDeBitmaps.ALVO_DA_SOMA_BYTES.toLong())
            assertThat(OrcamentoDeBitmaps.cabe(listOf(OrcamentoDeBitmaps.bytes(lado), OrcamentoDeBitmaps.bytes(lado)))).isTrue()
        }
    }

    @Test
    fun `passar de 1 MB nao cabe`() {
        assertThat(OrcamentoDeBitmaps.cabe(listOf(600L * 1024, 600L * 1024))).isFalse()
        assertThat(OrcamentoDeBitmaps.cabe(listOf(512L * 1024, 512L * 1024))).isTrue()
        assertThat(OrcamentoDeBitmaps.bytes(192)).isEqualTo(192L * 192 * 4)
    }
}
