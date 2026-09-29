package com.keyflow.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Esquemas de cor do teclado: o Aero (paisagem + vidro) sem mexer no que Sistema e Ateliê já pintam. */
class KeyboardColorsTest {

    private val flat = listOf(KeyboardSkin.SYSTEM, KeyboardSkin.ATELIE)

    @Test
    fun `flat skins keep a solid tray and popups equal to the key`() {
        for (skin in flat) {
            for (dark in listOf(false, true)) {
                val colors = KeyboardColors.of(skin, dark)
                assertThat(colors.isGlass).isFalse()
                assertThat(colors.backdrop).isNull()
                assertThat(colors.inkShadow).isEqualTo(0)
                assertThat(colors.popup).isEqualTo(colors.key)
                assertThat(colors.tray ushr 24).isEqualTo(0xFF)
            }
        }
    }

    @Test
    fun `aero puts a translucent glass sheet behind glass keys in both modes`() {
        val day = KeyboardColors.of(KeyboardSkin.AERO, dark = false)
        val night = KeyboardColors.of(KeyboardSkin.AERO, dark = true)
        assertThat(day.backdrop).isEqualTo(KeyboardBackdrop.AERO_DAY)
        assertThat(night.backdrop).isEqualTo(KeyboardBackdrop.AERO_NIGHT)
        for (colors in listOf(day, night)) {
            assertThat(colors.isGlass).isTrue()
            // Fundo das barras transparente: quem pinta é a lâmina de vidro atrás delas.
            assertThat(colors.tray ushr 24).isEqualTo(0)
            // Vidro deixa o que está atrás passar; balões e letras não.
            assertThat(colors.key ushr 24).isLessThan(0xFF)
            assertThat(colors.modifier ushr 24).isLessThan(0xFF)
            assertThat(colors.popup ushr 24).isEqualTo(0xFF)
            assertThat(colors.ink ushr 24).isEqualTo(0xFF)
            assertThat(colors.inkShadow).isNotEqualTo(0)
        }
    }

    @Test
    fun `navigation bar strip under the keyboard carries the glass tint, translucent like the sheet`() {
        for (backdrop in KeyboardBackdrop.entries) {
            assertThat(backdrop.navigationBarColor ushr 24).isGreaterThan(0)
            assertThat(backdrop.navigationBarColor ushr 24).isLessThan(0xFF)
        }
    }

    @Test
    fun `aero id round-trips and unknown ids fall back to the default`() {
        assertThat(KeyboardSkin.of("aero")).isEqualTo(KeyboardSkin.AERO)
        assertThat(KeyboardSkin.of("vaporwave")).isEqualTo(KeyboardSkin.DEFAULT)
    }
}
