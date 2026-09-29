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
                assertThat(colors.hasSurface).isFalse()
                assertThat(colors.backdrop).isNull()
                assertThat(colors.inkShadow).isEqualTo(0)
                assertThat(colors.popup).isEqualTo(colors.key)
                assertThat(colors.tray ushr 24).isEqualTo(0xFF)
            }
        }
    }

    @Test
    fun `aero puts a translucent glass sheet behind glass keys`() {
        val colors = KeyboardColors.of(KeyboardSkin.AERO, dark = false)
        assertThat(colors.backdrop).isEqualTo(KeyboardBackdrop.AERO)
        assertThat(colors.hasSurface).isTrue()
        // Fundo das barras transparente: quem pinta é a lâmina de vidro atrás delas.
        assertThat(colors.tray ushr 24).isEqualTo(0)
        // Vidro deixa o que está atrás passar; balões e letras não.
        assertThat(colors.key ushr 24).isLessThan(0xFF)
        assertThat(colors.modifier ushr 24).isLessThan(0xFF)
        assertThat(colors.popup ushr 24).isEqualTo(0xFF)
        assertThat(colors.ink ushr 24).isEqualTo(0xFF)
        assertThat(colors.inkShadow).isNotEqualTo(0)
    }

    @Test
    fun `aero looks the same in the light and the dark theme`() {
        // Pedido do Yan (2026-09-29): o vidro não muda de cor com o tema.
        assertThat(KeyboardColors.of(KeyboardSkin.AERO, dark = true)).isEqualTo(KeyboardColors.of(KeyboardSkin.AERO, dark = false))
    }

    @Test
    fun `aero glass is clear and its letters are black, heavier and haloed`() {
        val colors = KeyboardColors.of(KeyboardSkin.AERO, dark = false)
        // Vidro claro e quase transparente: o véu das teclas é bem menor que meio opaco.
        assertThat(colors.key ushr 24).isLessThan(0x80)
        assertThat(colors.modifier ushr 24).isLessThan(0x80)
        // Letra preta, mais grossa que o normal, com halo branco.
        assertThat(colors.ink and 0x00FFFFFF).isEqualTo(0)
        assertThat(colors.labelWeight).isNotEqualTo(LabelWeight.NORMAL)
        assertThat(colors.inkShadow and 0x00FFFFFF).isEqualTo(0x00FFFFFF)
    }

    @Test
    fun `navigation bar strip under the keyboard follows its skin - translucent glass, or the glitch black`() {
        // Aero: a mesma tinta translúcida da lâmina; Glitch: preto opaco, como o fundo dele.
        assertThat(KeyboardBackdrop.AERO.navigationBarColor ushr 24).isGreaterThan(0)
        assertThat(KeyboardBackdrop.AERO.navigationBarColor ushr 24).isLessThan(0xFF)
        assertThat(KeyboardBackdrop.GLITCH.navigationBarColor).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `glitch is black, white, red and blue on a backdrop of its own, the same in both themes`() {
        val colors = KeyboardColors.of(KeyboardSkin.GLITCH, dark = false)
        assertThat(KeyboardColors.of(KeyboardSkin.GLITCH, dark = true)).isEqualTo(colors)
        assertThat(colors.backdrop).isEqualTo(KeyboardBackdrop.GLITCH)
        assertThat(colors.hasSurface).isTrue()
        // Faces opacas e escuras, letra branca; o fundo (preto) vem do backdrop, então a bandeja é transparente.
        assertThat(colors.tray ushr 24).isEqualTo(0)
        assertThat(colors.key ushr 24).isEqualTo(0xFF)
        assertThat(colors.key and 0x00FFFFFF).isAtMost(0x101010)
        assertThat(colors.ink).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(colors.popup ushr 24).isEqualTo(0xFF)
        // Sem halo de vidro: o rótulo ganha as camadas de cor no lugar.
        assertThat(colors.inkShadow).isEqualTo(0)
        assertThat(colors.labelWeight).isEqualTo(LabelWeight.BOLD)
    }

    @Test
    fun `glitch swaps the shared accents for its own red and blue and leaves the others alone`() {
        val glitch = KeyboardColors.of(KeyboardSkin.GLITCH, dark = false)
        val accents = listOf(0xFF4E9683.toInt(), 0xFFC6873F.toInt(), 0xFFD9694B.toInt())
        // Nenhum verde nem âmbar sobrevive: só vermelho e azul.
        assertThat(accents.map { glitch.tone(it) }.toSet()).containsExactly(0xFF2250FF.toInt(), 0xFFFE2C55.toInt())
        assertThat(glitch.tone(0xFF123456.toInt())).isEqualTo(0xFF123456.toInt())
        // As outras skins não mexem em nada.
        for (skin in listOf(KeyboardSkin.SYSTEM, KeyboardSkin.ATELIE, KeyboardSkin.AERO)) {
            val colors = KeyboardColors.of(skin, dark = false)
            for (color in accents) assertThat(colors.tone(color)).isEqualTo(color)
        }
    }

    @Test
    fun `aero id round-trips and unknown ids fall back to the default`() {
        assertThat(KeyboardSkin.of("aero")).isEqualTo(KeyboardSkin.AERO)
        assertThat(KeyboardSkin.of("glitch")).isEqualTo(KeyboardSkin.GLITCH)
        assertThat(KeyboardSkin.of("vaporwave")).isEqualTo(KeyboardSkin.DEFAULT)
    }

    @Test
    fun `only the system keyboard skin is free — the rest is paid but still unlocked for testing`() {
        // Decisão do Yan (2026-09-29). O bloqueio de verdade vem com a monetização (UNL-04); até lá `paid` só
        // registra a decisão e nada esconde as skins.
        assertThat(KeyboardSkin.entries.filter { !it.paid }).containsExactly(KeyboardSkin.SYSTEM)
        assertThat(KeyboardSkin.AERO.paid).isTrue()
        assertThat(KeyboardSkin.GLITCH.paid).isTrue()
        assertThat(KeyboardSkin.DEFAULT.paid).isFalse()
    }
}
