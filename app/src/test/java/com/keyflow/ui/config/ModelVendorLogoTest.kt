package com.keyflow.ui.config

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.ModelVendor
import com.keyflow.ui.theme.DarkPalette
import com.keyflow.ui.theme.LightPalette
import org.junit.Test

/** O logo do fabricante é colorido nos dois temas e nunca some no fundo do card. */
class ModelVendorLogoTest {

    private val light = LightPalette
    private val dark = DarkPalette

    @Test
    fun `every vendor has a logo with its own drawing`() {
        val names = ModelVendor.entries.map { it.logo().icon.name }
        assertThat(names).containsNoDuplicates()
        ModelVendor.entries.forEach { assertThat(it.displayName).isNotEmpty() }
    }

    @Test
    fun `a brand color that already contrasts is kept`() {
        val blue = Color(0xFF4285F4)
        assertThat(legibleOn(blue, Color.White, Color.Black)).isEqualTo(blue)
    }

    @Test
    fun `every logo contrasts with the card in both themes`() {
        for (palette in listOf(light, dark)) {
            ModelVendor.entries.forEach { vendor ->
                val tint = legibleOn(vendor.logo().brand, palette.bg, palette.fg)
                assertThat(contrastRatio(tint, palette.bg)).isAtLeast(LOGO_MIN_CONTRAST - 0.05)
            }
        }
    }

    @Test
    fun `a black brand follows the text color on the dark theme`() {
        val tint = legibleOn(Color.Black, dark.bg, dark.fg)
        assertThat(contrastRatio(tint, dark.bg)).isAtLeast(LOGO_MIN_CONTRAST - 0.05)
        assertThat(tint.red).isGreaterThan(0.3f)
    }

    @Test
    fun `a pale brand darkens only as much as needed on the light theme`() {
        val yellow = Color(0xFFFFD21E)
        val tint = legibleOn(yellow, light.bg, light.fg)
        assertThat(contrastRatio(tint, light.bg)).isAtLeast(LOGO_MIN_CONTRAST - 0.05)
        assertThat(tint).isNotEqualTo(light.fg)
    }
}
