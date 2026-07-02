package com.keyflow.core.shortcuts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Codec JSON das barras/grades (SB-T1): roundtrip, tolerância a falha e forward-compat. */
class ShortcutCodecTest {

    private val ctrlB = ShortcutKey(keyCode = 30, ctrl = true, label = "Ctrl+B")
    private val tab = ShortcutKey(keyCode = 61, label = "Tab")

    private val customConfig = BarsConfig(
        mirrored = true,
        collapsed = listOf(
            BarDef(
                listOf(
                    BarButton.Mic,
                    BarButton.SingleKey(tab),
                    BarButton.Joystick,
                    BarButton.GridTrigger(
                        gridId = "tmux",
                        prefix = ctrlB,
                        mode = GridTriggerMode.SEQUENCE,
                        label = "tmx",
                    ),
                    BarButton.ToggleExpand,
                )
            ),
            BarDef(listOf(BarButton.Paste, BarButton.Candidates, BarButton.ConfigBtn)),
        ),
        expanded = emptyList(),
    )

    private val grid = ShortcutGrid(
        id = "tmux",
        name = "tmux",
        rows = 2,
        cols = 3,
        cells = mapOf(
            0 to GridCell(ShortcutKey(keyCode = 31, label = "c"), label = "nova janela"),
            5 to GridCell(ctrlB),
        ),
    )

    // ── barras ───────────────────────────────────────────────────────────────

    @Test
    fun `bars roundtrip preserves everything`() {
        val decoded = ShortcutCodec.decodeBars(ShortcutCodec.encodeBars(customConfig))
        assertThat(decoded).isEqualTo(customConfig)
    }

    @Test
    fun `default config roundtrips`() {
        val decoded = ShortcutCodec.decodeBars(ShortcutCodec.encodeBars(BarsConfig.DEFAULT))
        assertThat(decoded).isEqualTo(BarsConfig.DEFAULT)
    }

    @Test
    fun `null blank or malformed bars fall back to DEFAULT`() {
        assertThat(ShortcutCodec.decodeBars(null)).isEqualTo(BarsConfig.DEFAULT)
        assertThat(ShortcutCodec.decodeBars("")).isEqualTo(BarsConfig.DEFAULT)
        assertThat(ShortcutCodec.decodeBars("{ nem json")).isEqualTo(BarsConfig.DEFAULT)
        assertThat(ShortcutCodec.decodeBars("[1,2,3]")).isEqualTo(BarsConfig.DEFAULT)
    }

    @Test
    fun `unknown button type is skipped, known ones survive`() {
        val json = """
            {"mirrored":false,
             "collapsed":[{"buttons":[{"type":"mic"},{"type":"teleport"},{"type":"paste"}]}],
             "expanded":[]}
        """.trimIndent()
        val decoded = ShortcutCodec.decodeBars(json)
        assertThat(decoded.collapsed.single().buttons)
            .containsExactly(BarButton.Mic, BarButton.Paste)
            .inOrder()
    }

    @Test
    fun `grid trigger without gridId is dropped, without mode falls back to OPEN_ONLY`() {
        val json = """
            {"mirrored":false,
             "collapsed":[{"buttons":[{"type":"grid"},{"type":"grid","gridId":"g1"}]}],
             "expanded":[]}
        """.trimIndent()
        val buttons = ShortcutCodec.decodeBars(json).collapsed.single().buttons
        val trigger = buttons.single() as BarButton.GridTrigger
        assertThat(trigger.gridId).isEqualTo("g1")
        assertThat(trigger.mode).isEqualTo(GridTriggerMode.OPEN_ONLY)
        assertThat(trigger.prefix).isNull()
    }

    // ── grades ───────────────────────────────────────────────────────────────

    @Test
    fun `grids roundtrip preserves cells and labels`() {
        val decoded = ShortcutCodec.decodeGrids(ShortcutCodec.encodeGrids(listOf(grid)))
        assertThat(decoded).containsExactly(grid)
    }

    @Test
    fun `malformed grids fall back to empty`() {
        assertThat(ShortcutCodec.decodeGrids(null)).isEmpty()
        assertThat(ShortcutCodec.decodeGrids("")).isEmpty()
        assertThat(ShortcutCodec.decodeGrids("{}")).isEmpty()
        assertThat(ShortcutCodec.decodeGrids("nem json")).isEmpty()
    }

    @Test
    fun `grid with invalid dimensions or cell out of range is sanitized`() {
        val json = """
            [{"id":"","name":"sem id","rows":2,"cols":2,"cells":{}},
             {"id":"ok","name":"ok","rows":1,"cols":2,
              "cells":{"0":{"key":{"keyCode":61,"label":"Tab"}},
                        "9":{"key":{"keyCode":62,"label":"Space"}},
                        "x":{"key":{"keyCode":63,"label":"?"}}}}]
        """.trimIndent()
        val decoded = ShortcutCodec.decodeGrids(json)
        val only = decoded.single()
        assertThat(only.id).isEqualTo("ok")
        // célula 9 está fora de rows*cols=2 e "x" não é índice — só a 0 sobrevive.
        assertThat(only.cells.keys).containsExactly(0)
        assertThat(only.cellAt(0, 0)!!.displayLabel).isEqualTo("Tab")
    }
}
