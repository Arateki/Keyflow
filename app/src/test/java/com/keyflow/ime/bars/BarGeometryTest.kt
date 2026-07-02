package com.keyflow.ime.bars

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.BarsConfig
import org.junit.Test

/** Geometria/overflow das barras (SB-T5 / BAR-01/05 — parte pura). */
class BarGeometryTest {

    @Test
    fun `few buttons stretch to fill the whole width`() {
        val layout = BarGeometry.layout(
            listOf(BarButton.Mic, BarButton.ToggleExpand), // pesos 1.5 + 1.0 = 2.5
            viewWidthPx = 500f,
            minUnitPx = 100f,
        )
        assertThat(layout.scrollable).isFalse()
        assertThat(layout.contentWidth).isEqualTo(500f)
        assertThat(layout.buttons[0].width).isEqualTo(300f) // 1.5 * (500/2.5)
        assertThat(layout.buttons[1].left).isEqualTo(300f)
        assertThat(layout.buttons[1].width).isEqualTo(200f)
    }

    @Test
    fun `crowded bar overflows at the minimum unit and scrolls`() {
        val buttons = List(10) { BarButton.ToggleExpand } // peso 1 cada
        val layout = BarGeometry.layout(buttons, viewWidthPx = 500f, minUnitPx = 100f)
        assertThat(layout.scrollable).isTrue()
        assertThat(layout.contentWidth).isEqualTo(1000f) // 10 × mínimo
        assertThat(layout.maxScroll).isEqualTo(500f)
        assertThat(layout.buttons.last().right).isEqualTo(1000f)
    }

    @Test
    fun `buttonAt resolves by content coordinate`() {
        val layout = BarGeometry.layout(
            listOf(BarButton.Mic, BarButton.ConfigBtn),
            viewWidthPx = 480f, // pesos 1.5+0.9=2.4 → unit 200
            minUnitPx = 44f,
        )
        assertThat(layout.buttonAt(0f)!!.button).isEqualTo(BarButton.Mic)
        assertThat(layout.buttonAt(299f)!!.button).isEqualTo(BarButton.Mic)
        assertThat(layout.buttonAt(300f)!!.button).isEqualTo(BarButton.ConfigBtn)
        assertThat(layout.buttonAt(480f)).isNull()
    }

    @Test
    fun `default expanded bar keeps current strip proportions`() {
        val buttons = BarsConfig.DEFAULT.barsFor(expandedMode = true).single().buttons
        // mic 1.5 / ⚙ 0.9 / candidates 4 / ↓ 1 — os mesmos pesos da faixa atual (buildTopStrip).
        assertThat(buttons.map(BarGeometry::weightFor)).containsExactly(1.5f, 0.9f, 4f, 1f).inOrder()
    }

    @Test
    fun `draggable buttons capture the gesture, tap buttons allow scroll`() {
        assertThat(BarGeometry.isDraggable(BarButton.Joystick)).isTrue()
        assertThat(BarGeometry.isDraggable(BarButton.GridTrigger(gridId = "g"))).isTrue()
        assertThat(BarGeometry.isDraggable(BarButton.Mic)).isTrue()
        assertThat(BarGeometry.isDraggable(BarButton.Paste)).isFalse()
        assertThat(BarGeometry.isDraggable(BarButton.ConfigBtn)).isFalse()
    }

    @Test
    fun `empty input yields empty layout`() {
        assertThat(BarGeometry.layout(emptyList(), 500f, 44f)).isEqualTo(BarGeometry.BarLayout.EMPTY)
        assertThat(BarGeometry.layout(listOf(BarButton.Mic), 0f, 44f)).isEqualTo(BarGeometry.BarLayout.EMPTY)
    }
}
