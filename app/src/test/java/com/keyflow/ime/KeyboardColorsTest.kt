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
        // Aero: a mesma tinta translúcida da lâmina (mais densa quando o aparelho não desfoca); Glitch: o preto do fundo.
        for (blur in listOf(true, false)) {
            assertThat(KeyboardBackdrop.AERO.navigationBarColor(blur) ushr 24).isGreaterThan(0)
            assertThat(KeyboardBackdrop.AERO.navigationBarColor(blur) ushr 24).isLessThan(0xFF)
        }
        assertThat(KeyboardBackdrop.AERO.navigationBarColor(false) ushr 24)
            .isGreaterThan(KeyboardBackdrop.AERO.navigationBarColor(true) ushr 24)
        assertThat(KeyboardBackdrop.GLITCH.navigationBarColor(true)).isEqualTo(KeyboardBackdrop.GLITCH.navigationBarColor(false))
        assertThat(KeyboardBackdrop.GLITCH.navigationBarColor(true) ushr 24).isEqualTo(0xFF)
    }

    @Test
    fun `glitch is a discreet neutral dark, the same in both themes, with no blue fill`() {
        val colors = KeyboardColors.of(KeyboardSkin.GLITCH, dark = false)
        assertThat(KeyboardColors.of(KeyboardSkin.GLITCH, dark = true)).isEqualTo(colors)
        assertThat(colors.backdrop).isEqualTo(KeyboardBackdrop.GLITCH)
        assertThat(colors.hasSurface).isTrue()
        // A bandeja é transparente: o fundo (preto neutro) vem do backdrop.
        assertThat(colors.tray ushr 24).isEqualTo(0)
        // Faces opacas, cinza escuro e NEUTRO (r = g = b: nada de azul), e a função só um degrau mais clara.
        for (face in listOf(colors.key, colors.modifier)) {
            assertThat(face ushr 24).isEqualTo(0xFF)
            assertThat((face shr 16) and 0xFF).isEqualTo(face and 0xFF)
            assertThat((face shr 8) and 0xFF).isEqualTo(face and 0xFF)
            assertThat(face and 0xFF).isAtMost(0x30)
        }
        assertThat(colors.modifier and 0xFF).isGreaterThan(colors.key and 0xFF)
        // Letra clara mas não branca berrante; sem halo de vidro: o rótulo ganha as camadas de cor no lugar.
        assertThat(colors.ink and 0xFF).isLessThan(0xFF)
        assertThat(colors.popup ushr 24).isEqualTo(0xFF)
        assertThat(colors.inkShadow).isEqualTo(0)
    }

    @Test
    fun `glitch swaps the shared accents for deep cyan and deep red, never neon`() {
        val glitch = KeyboardColors.of(KeyboardSkin.GLITCH, dark = false)
        val accents = listOf(0xFF4E9683.toInt(), 0xFFC6873F.toInt(), 0xFFD9694B.toInt())
        // Nenhum verde nem âmbar sobrevive: só um ciano fundo e um vermelho fundo (fundos de botão, escuros).
        val tones = accents.map { glitch.tone(it) }
        assertThat(tones.toSet()).containsExactly(0xFF0F3E44.toInt(), 0xFF7A1029.toInt())
        for (tone in tones) {
            val brightest = maxOf((tone shr 16) and 0xFF, (tone shr 8) and 0xFF, tone and 0xFF)
            assertThat(brightest).isAtMost(0x80)
        }
        assertThat(glitch.tone(0xFF123456.toInt())).isEqualTo(0xFF123456.toInt())
        // As outras skins não mexem em nada.
        for (skin in listOf(KeyboardSkin.SYSTEM, KeyboardSkin.ATELIE, KeyboardSkin.AERO)) {
            val colors = KeyboardColors.of(skin, dark = false)
            for (color in accents) assertThat(colors.tone(color)).isEqualTo(color)
        }
    }

    @Test
    fun `rose gold is one warm opaque palette, the same in both themes, with wine letters`() {
        val colors = KeyboardColors.of(KeyboardSkin.ROSE_GOLD, dark = false)
        assertThat(KeyboardColors.of(KeyboardSkin.ROSE_GOLD, dark = true)).isEqualTo(colors)
        assertThat(colors.backdrop).isEqualTo(KeyboardBackdrop.ROSE_GOLD)
        assertThat(colors.hasSurface).isTrue()
        assertThat(colors.tray ushr 24).isEqualTo(0)
        // Faces opacas e quentes (r > g > b): blush nas letras e um rosé mais fundo nas de função.
        for (face in listOf(colors.key, colors.modifier)) {
            assertThat(face ushr 24).isEqualTo(0xFF)
            assertThat((face shr 16) and 0xFF).isGreaterThan((face shr 8) and 0xFF)
            assertThat((face shr 8) and 0xFF).isAtLeast(face and 0xFF)
        }
        assertThat(colors.modifier and 0xFF).isLessThan(colors.key and 0xFF)
        // Letra vinho (escura, avermelhada) e balão opaco; sem halo de vidro.
        assertThat((colors.ink shr 16) and 0xFF).isAtMost(0x70)
        assertThat((colors.ink shr 16) and 0xFF).isAtLeast((colors.ink and 0xFF) + 0x10)
        assertThat(colors.popup ushr 24).isEqualTo(0xFF)
        assertThat(colors.inkShadow).isEqualTo(0)
    }

    @Test
    fun `rose gold swaps the shared accents for dusty rose and raspberry - no green, no amber`() {
        val rose = KeyboardColors.of(KeyboardSkin.ROSE_GOLD, dark = false)
        val teal = 0xFF4E9683.toInt()
        val amber = 0xFFC6873F.toInt()
        val terracotta = 0xFFD9694B.toInt()
        for (color in listOf(teal, amber, terracotta)) {
            val tone = rose.tone(color)
            assertThat(tone).isNotEqualTo(color)
            // Nunca esverdeado: o vermelho manda.
            assertThat((tone shr 16) and 0xFF).isGreaterThan((tone shr 8) and 0xFF)
        }
        assertThat(rose.tone(0xFF123456.toInt())).isEqualTo(0xFF123456.toInt())
    }

    @Test
    fun `aero id round-trips and unknown ids fall back to the default`() {
        assertThat(KeyboardSkin.of("aero")).isEqualTo(KeyboardSkin.AERO)
        assertThat(KeyboardSkin.of("glitch")).isEqualTo(KeyboardSkin.GLITCH)
        assertThat(KeyboardSkin.of("rosegold")).isEqualTo(KeyboardSkin.ROSE_GOLD)
        assertThat(KeyboardSkin.of("vaporwave")).isEqualTo(KeyboardSkin.DEFAULT)
    }

    @Test
    fun `only the system keyboard skin is free — the rest is paid but still unlocked for testing`() {
        // Decisão do Yan (2026-09-29). O bloqueio de verdade vem com a monetização (UNL-04); até lá `paid` só
        // registra a decisão e nada esconde as skins.
        assertThat(KeyboardSkin.entries.filter { !it.paid }).containsExactly(KeyboardSkin.SYSTEM)
        assertThat(KeyboardSkin.AERO.paid).isTrue()
        assertThat(KeyboardSkin.GLITCH.paid).isTrue()
        assertThat(KeyboardSkin.ROSE_GOLD.paid).isTrue()
        assertThat(KeyboardSkin.DEFAULT.paid).isFalse()
    }
}
