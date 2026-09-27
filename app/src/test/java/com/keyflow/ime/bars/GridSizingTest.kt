package com.keyflow.ime.bars

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Encolhimento de célula pra grade caber na tela (SB-T10, achado do Yan: grade 6×10 estourava a janela do IME em paisagem). */
class GridSizingTest {

    @Test
    fun `keeps the ideal size when the grid comfortably fits`() {
        val (w, h) = GridSizing.fittedCellPx(
            rows = 2,
            cols = 3,
            idealCellWidthPx = 100f,
            idealCellHeightPx = 80f,
            screenWidthPx = 1000f,
            screenHeightPx = 2000f,
            reservedHeightPx = 100f,
        )
        assertThat(w).isEqualTo(100f)
        assertThat(h).isEqualTo(80f)
    }

    @Test
    fun `shrinks height only when rows would overflow the height budget`() {
        // Orçamento de altura = 1000 - 900 reservado = 100; 9 linhas cabem em 100/9 ≈ 11,1px.
        val (w, h) = GridSizing.fittedCellPx(
            rows = 9,
            cols = 3,
            idealCellWidthPx = 100f,
            idealCellHeightPx = 80f,
            screenWidthPx = 1000f,
            screenHeightPx = 1000f,
            reservedHeightPx = 900f,
        )
        assertThat(w).isEqualTo(100f) // largura não vaza, mantém o ideal
        assertThat(h).isWithin(0.01f).of(100f / 9f)
    }

    @Test
    fun `shrinks width only when cols would overflow the screen width`() {
        val (w, h) = GridSizing.fittedCellPx(
            rows = 2,
            cols = 9,
            idealCellWidthPx = 100f,
            idealCellHeightPx = 80f,
            screenWidthPx = 500f,
            screenHeightPx = 2000f,
            reservedHeightPx = 100f,
        )
        assertThat(w).isWithin(0.01f).of(500f / 9f)
        assertThat(h).isEqualTo(80f)
    }

    @Test
    fun `never returns a negative height even when reserved space exceeds the screen`() {
        val (_, h) = GridSizing.fittedCellPx(
            rows = 9,
            cols = 9,
            idealCellWidthPx = 100f,
            idealCellHeightPx = 80f,
            screenWidthPx = 500f,
            screenHeightPx = 100f,
            reservedHeightPx = 1000f,
        )
        assertThat(h).isAtLeast(0f)
    }
}
