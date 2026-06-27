package com.keyflow.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Mapeamento de tema Ateliê: claro/escuro e acentos fixos (FND-03, FND-04). */
class PaletteTest {

    @Test
    fun `paletteFor false resolves to the light palette tokens`() {
        val p = paletteFor(dark = false)
        assertThat(p).isSameInstanceAs(LightPalette)
        assertThat(p.isDark).isFalse()
        assertThat(p.bg).isEqualTo(Color(0xFFF1E7C9))
        assertThat(p.fg).isEqualTo(Color(0xFF2B2720))
    }

    @Test
    fun `paletteFor true resolves to the dark palette tokens`() {
        val p = paletteFor(dark = true)
        assertThat(p).isSameInstanceAs(DarkPalette)
        assertThat(p.isDark).isTrue()
        // No escuro, bg/fg invertem em relação ao claro (sem papel-sobre-papel).
        assertThat(p.bg).isEqualTo(Color(0xFF2B2720))
        assertThat(p.fg).isEqualTo(Color(0xFFF1E7C9))
    }

    @Test
    fun `accents are fixed across light and dark`() {
        assertThat(DarkPalette.teal).isEqualTo(LightPalette.teal)
        assertThat(DarkPalette.tealLight).isEqualTo(LightPalette.tealLight)
        assertThat(DarkPalette.terracotta).isEqualTo(LightPalette.terracotta)
        assertThat(DarkPalette.record).isEqualTo(LightPalette.record)
        assertThat(DarkPalette.recordSoft).isEqualTo(LightPalette.recordSoft)
        // Sanidade: acentos batem com context.md.
        assertThat(LightPalette.teal).isEqualTo(Color(0xFF4E9683))
        assertThat(LightPalette.terracotta).isEqualTo(Color(0xFFD9694B))
    }

    @Test
    fun `light and dark surfaces actually differ`() {
        assertThat(DarkPalette.bg).isNotEqualTo(LightPalette.bg)
        assertThat(DarkPalette.card).isNotEqualTo(LightPalette.card)
        assertThat(DarkPalette.line).isNotEqualTo(LightPalette.line)
    }
}
