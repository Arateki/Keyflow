package com.keyflow.ime.keyboard.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tira de variantes do long-press (RND-03, estilo Samsung): posição dos chips e escolha pelo dedo. */
class AccentStripTest {

    @Test
    fun `with room to the right the first variant sits over the key and the rest follow`() {
        assertThat(AccentStrip.place(anchorX = 50f, count = 3, chipWidth = 100f, viewWidth = 1000f))
            .containsExactly(0f, 100f, 200f).inOrder()
    }

    @Test
    fun `near the right edge the strip grows to the left, first variant still over the key`() {
        assertThat(AccentStrip.place(anchorX = 950f, count = 3, chipWidth = 100f, viewWidth = 1000f))
            .containsExactly(900f, 800f, 700f).inOrder()
    }

    @Test
    fun `a strip wider than the keyboard in both directions is kept from the left edge`() {
        assertThat(AccentStrip.place(anchorX = 500f, count = 12, chipWidth = 100f, viewWidth = 1000f).first())
            .isEqualTo(0f)
    }

    @Test
    fun `the finger where it went down picks the first variant`() {
        val lefts = AccentStrip.place(anchorX = 50f, count = 3, chipWidth = 100f, viewWidth = 1000f)
        assertThat(AccentStrip.indexAt(50f, lefts, chipWidth = 100f)).isEqualTo(0)
    }

    @Test
    fun `moving sideways one chip picks the next variant without entering the strip`() {
        val lefts = AccentStrip.place(anchorX = 50f, count = 3, chipWidth = 100f, viewWidth = 1000f)
        assertThat(AccentStrip.indexAt(160f, lefts, chipWidth = 100f)).isEqualTo(1)
    }

    @Test
    fun `going past either end stays on the end chip`() {
        val lefts = AccentStrip.place(anchorX = 50f, count = 3, chipWidth = 100f, viewWidth = 1000f)
        assertThat(AccentStrip.indexAt(900f, lefts, chipWidth = 100f)).isEqualTo(2)
        assertThat(AccentStrip.indexAt(-300f, lefts, chipWidth = 100f)).isEqualTo(0)
    }

    @Test
    fun `on a strip that grows left, moving left picks the next variant`() {
        val lefts = AccentStrip.place(anchorX = 950f, count = 3, chipWidth = 100f, viewWidth = 1000f)
        assertThat(AccentStrip.indexAt(840f, lefts, chipWidth = 100f)).isEqualTo(1)
    }
}
