package com.keyflow.core.shortcuts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Modelo das barras (SB-T1): fábrica com paridade, espelhamento, fallback e detecção de órfãos. */
class BarsConfigTest {

    @Test
    fun `factory default reproduces current bars`() {
        // Retraído atual: mic, ⚙, colar, esc, ⏎, ⌃ (BAR-02 — paridade com buildCollapsed()).
        val collapsed = BarsConfig.DEFAULT.barsFor(expandedMode = false).single().buttons
        assertThat(collapsed).hasSize(6)
        assertThat(collapsed[0]).isEqualTo(BarButton.Mic)
        assertThat(collapsed[1]).isEqualTo(BarButton.ConfigBtn)
        assertThat(collapsed[2]).isEqualTo(BarButton.Paste)
        assertThat((collapsed[3] as BarButton.SingleKey).key.keyCode).isEqualTo(BarsConfig.KEYCODE_ESCAPE)
        assertThat((collapsed[4] as BarButton.SingleKey).key.keyCode).isEqualTo(BarsConfig.KEYCODE_ENTER)
        assertThat(collapsed[5]).isEqualTo(BarButton.ToggleExpand)

        // Expandido atual: mic, ⚙, candidates, ↓ (BAR-02 — paridade com buildTopStrip()).
        val expanded = BarsConfig.DEFAULT.barsFor(expandedMode = true).single().buttons
        assertThat(expanded)
            .containsExactly(BarButton.Mic, BarButton.ConfigBtn, BarButton.Candidates, BarButton.ToggleExpand)
            .inOrder()
    }

    @Test
    fun `mirrored uses collapsed list for both modes`() {
        val bar = BarDef(listOf(BarButton.Mic, BarButton.Joystick))
        val config = BarsConfig(mirrored = true, collapsed = listOf(bar), expanded = emptyList())
        assertThat(config.barsFor(expandedMode = false)).containsExactly(bar)
        assertThat(config.barsFor(expandedMode = true)).containsExactly(bar)
    }

    @Test
    fun `mode without bars falls back to factory bar`() {
        val config = BarsConfig(
            mirrored = false,
            collapsed = listOf(BarDef(listOf(BarButton.Mic))),
            expanded = emptyList(),
        )
        assertThat(config.barsFor(expandedMode = true)).isEqualTo(BarsConfig.DEFAULT.expanded)
    }

    @Test
    fun `referencedGridIds collects triggers from both modes`() {
        val config = BarsConfig(
            mirrored = false,
            collapsed = listOf(BarDef(listOf(BarButton.GridTrigger(gridId = "tmux"), BarButton.Mic))),
            expanded = listOf(BarDef(listOf(BarButton.GridTrigger(gridId = "vim")))),
        )
        assertThat(config.referencedGridIds()).containsExactly("tmux", "vim")
    }

    @Test
    fun `cellAt maps row and col to flat index`() {
        val key = ShortcutKey(keyCode = 61, label = "Tab")
        val grid = ShortcutGrid("g", "g", rows = 2, cols = 3, cells = mapOf(4 to GridCell(key)))
        assertThat(grid.cellAt(1, 1)!!.key).isEqualTo(key)
        assertThat(grid.cellAt(0, 0)).isNull()
    }
}
