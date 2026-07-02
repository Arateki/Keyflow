package com.keyflow.ime.keyboard.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Geometria por peso + hit-test (RND-01 lógica, LAY-01). Pura, sem `android.graphics` (JVM). */
class GeometryTest {

    private val row1 = Layouts.forLocale("en").layers.getValue(LayerId.LETTERS).rows[0] // 10 letras, peso 1 cada
    private val row3 = Layouts.forLocale("en").layers.getValue(LayerId.LETTERS).rows[2] // pesos mistos (⇧/⌫=1.5)

    @Test
    fun `equal weight row splits width evenly`() {
        val placed = Geometry.placeRow(row1, top = 0f, bottom = 100f, totalWidth = 1000f)

        assertThat(placed).hasSize(10)
        assertThat(placed[0].left).isEqualTo(0f)
        assertThat(placed[0].right).isEqualTo(100f)
        assertThat(placed[1].left).isEqualTo(100f)
        assertThat(placed.last().right).isEqualTo(1000f)
    }

    @Test
    fun `mixed weight row distributes proportionally to total weight`() {
        // pesos: ⇧=1.5, z x c v b n m=1 cada (7), ⌫=1.5 → total 10
        val placed = Geometry.placeRow(row3, top = 0f, bottom = 100f, totalWidth = 1000f)

        assertThat(placed[0].width).isEqualTo(150f) // ⇧: 1.5/10 * 1000
        assertThat(placed[1].width).isEqualTo(100f) // "z": 1/10 * 1000
        assertThat(placed.last().width).isEqualTo(150f) // ⌫: 1.5/10 * 1000
    }

    @Test
    fun `row keeps the given top and bottom for every key`() {
        val placed = Geometry.placeRow(row1, top = 200f, bottom = 300f, totalWidth = 1000f)
        assertThat(placed.all { it.top == 200f && it.bottom == 300f }).isTrue()
    }

    @Test
    fun `re-measuring with a different width recomputes bounds proportionally`() {
        val narrow = Geometry.placeRow(row1, top = 0f, bottom = 100f, totalWidth = 500f)
        assertThat(narrow[0].width).isEqualTo(50f)
        assertThat(narrow.last().right).isEqualTo(500f)
    }

    @Test
    fun `placeLayer stacks rows vertically by row height`() {
        val layer = Layouts.forLocale("en").layers.getValue(LayerId.LETTERS)
        val placed = Geometry.placeLayer(layer, totalWidth = 1000f, rowHeight = 100f)

        assertThat(placed).hasSize(4) // 4 linhas
        assertThat(placed[0].keys[0].top).isEqualTo(0f)
        assertThat(placed[1].keys[0].top).isEqualTo(100f)
        assertThat(placed[3].keys[0].bottom).isEqualTo(400f)
    }

    @Test
    fun `hitTest finds the key strictly inside its bounds`() {
        val placed = Geometry.placeRow(row1, top = 0f, bottom = 100f, totalWidth = 1000f)
        val hit = Geometry.hitTest(placed, x = 150f, y = 50f)
        assertThat(hit?.label).isEqualTo("w") // 2ª tecla (100..200)
    }

    @Test
    fun `hitTest at the left edge returns the first key`() {
        val placed = Geometry.placeRow(row1, top = 0f, bottom = 100f, totalWidth = 1000f)
        assertThat(Geometry.hitTest(placed, x = 0f, y = 50f)?.label).isEqualTo("q")
    }

    @Test
    fun `hitTest just before the right edge returns the last key`() {
        val placed = Geometry.placeRow(row1, top = 0f, bottom = 100f, totalWidth = 1000f)
        assertThat(Geometry.hitTest(placed, x = 999f, y = 50f)?.label).isEqualTo("p")
    }

    @Test
    fun `hitTest outside every bound returns null instead of crashing`() {
        val placed = Geometry.placeRow(row1, top = 0f, bottom = 100f, totalWidth = 1000f)
        assertThat(Geometry.hitTest(placed, x = 150f, y = 500f)).isNull()
        assertThat(Geometry.hitTest(placed, x = -10f, y = 50f)).isNull()
    }
}
