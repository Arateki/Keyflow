package com.keyflow.ime.keyboard.layout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Estado puro da grade (camada ativa + shift) extraído do `KeyboardGridView` (RND/LAY) para ser testável
 * sem Canvas/device — a View (KC-T3) só desenha e dirige toques pra cá.
 */
class KeyboardGridStateTest {

    private fun state() = KeyboardGridState(Layouts.forLocale("en"))

    @Test
    fun `starts on the letters layer without shift`() {
        val state = state()
        assertThat(state.currentLayerId).isEqualTo(LayerId.LETTERS)
        assertThat(state.shiftActive).isFalse()
    }

    @Test
    fun `starts on the given initial layer when the layout does not have LETTERS`() {
        // NUM-01: Layouts.numberLayout() só tem a camada NUMBER — o estado precisa nascer nela, senão
        // currentLayer (layout.layers.getValue(LETTERS)) explode.
        val state = KeyboardGridState(Layouts.numberLayout(), initialLayer = LayerId.NUMBER)
        assertThat(state.currentLayerId).isEqualTo(LayerId.NUMBER)
        assertThat(state.currentLayer.rows).hasSize(4)
    }

    @Test
    fun `tapping a layer key switches the current layer and emits nothing`() {
        val state = state()
        val emitted = state.onKeyTapped(KeyAction.Layer(LayerId.SYMBOLS))

        assertThat(emitted).isNull()
        assertThat(state.currentLayerId).isEqualTo(LayerId.SYMBOLS)
    }

    @Test
    fun `tapping ABC from symbols returns to letters`() {
        val state = state()
        state.onKeyTapped(KeyAction.Layer(LayerId.SYMBOLS))

        state.onKeyTapped(KeyAction.Layer(LayerId.LETTERS))

        assertThat(state.currentLayerId).isEqualTo(LayerId.LETTERS)
    }

    @Test
    fun `tapping shift toggles it on and emits nothing`() {
        val state = state()
        val emitted = state.onKeyTapped(KeyAction.Shift)

        assertThat(emitted).isNull()
        assertThat(state.shiftActive).isTrue()
    }

    @Test
    fun `char while shift is active is emitted uppercase and shift resets`() {
        val state = state()
        state.onKeyTapped(KeyAction.Shift)

        val emitted = state.onKeyTapped(KeyAction.Char("a", listOf("á", "à")))

        assertThat(emitted).isEqualTo(KeyAction.Char("A", listOf("á", "à")))
        assertThat(state.shiftActive).isFalse()
    }

    @Test
    fun `single shift tap is one-shot uppercase, cancelled by a slow second tap`() {
        var now = 0L
        val state = KeyboardGridState(Layouts.forLocale("en"), doubleTapWindowMs = 300L, clock = { now })

        state.onKeyTapped(KeyAction.Shift)
        assertThat(state.capsLocked).isFalse()
        assertThat(state.shiftActive).isTrue()

        now += 1000 // bem depois da janela de duplo-toque
        state.onKeyTapped(KeyAction.Shift)
        assertThat(state.shiftActive).isFalse()
    }

    @Test
    fun `double tap within the window locks caps`() {
        var now = 0L
        val state = KeyboardGridState(Layouts.forLocale("en"), doubleTapWindowMs = 300L, clock = { now })

        state.onKeyTapped(KeyAction.Shift)
        now += 150 // dentro da janela
        state.onKeyTapped(KeyAction.Shift)

        assertThat(state.capsLocked).isTrue()
        assertThat(state.shiftActive).isTrue()
    }

    @Test
    fun `caps lock keeps uppercasing across multiple letters`() {
        var now = 0L
        val state = KeyboardGridState(Layouts.forLocale("en"), doubleTapWindowMs = 300L, clock = { now })
        state.onKeyTapped(KeyAction.Shift)
        now += 150
        state.onKeyTapped(KeyAction.Shift) // trava caps

        assertThat(state.onKeyTapped(KeyAction.Char("a"))).isEqualTo(KeyAction.Char("A"))
        assertThat(state.onKeyTapped(KeyAction.Char("b"))).isEqualTo(KeyAction.Char("B"))
        assertThat(state.capsLocked).isTrue()
    }

    @Test
    fun `consumeShiftFor uppercases a resolved accent variant when shift is active and resets one-shot`() {
        val state = state()
        state.onKeyTapped(KeyAction.Shift)

        val result = state.consumeShiftFor("á")

        assertThat(result).isEqualTo("Á")
        assertThat(state.shiftActive).isFalse()
    }

    @Test
    fun `consumeShiftFor leaves the variant untouched without shift`() {
        val state = state()
        assertThat(state.consumeShiftFor("á")).isEqualTo("á")
    }

    @Test
    fun `consumeShiftFor keeps uppercasing under caps lock`() {
        var now = 0L
        val state = KeyboardGridState(Layouts.forLocale("en"), doubleTapWindowMs = 300L, clock = { now })
        state.onKeyTapped(KeyAction.Shift)
        now += 150
        state.onKeyTapped(KeyAction.Shift) // trava caps

        assertThat(state.consumeShiftFor("ñ")).isEqualTo("Ñ")
        assertThat(state.capsLocked).isTrue()
    }

    @Test
    fun `tapping shift again while locked unlocks it`() {
        var now = 0L
        val state = KeyboardGridState(Layouts.forLocale("en"), doubleTapWindowMs = 300L, clock = { now })
        state.onKeyTapped(KeyAction.Shift)
        now += 150
        state.onKeyTapped(KeyAction.Shift) // trava caps
        now += 1000

        state.onKeyTapped(KeyAction.Shift) // destrava

        assertThat(state.capsLocked).isFalse()
        assertThat(state.shiftActive).isFalse()
    }

    @Test
    fun `char without shift is emitted unchanged`() {
        val state = state()
        val emitted = state.onKeyTapped(KeyAction.Char("a", listOf("á")))
        assertThat(emitted).isEqualTo(KeyAction.Char("a", listOf("á")))
    }

    @Test
    fun `backspace enter and space pass through without touching layer or shift`() {
        val state = state()
        state.onKeyTapped(KeyAction.Shift)

        assertThat(state.onKeyTapped(KeyAction.Backspace)).isEqualTo(KeyAction.Backspace)
        assertThat(state.onKeyTapped(KeyAction.Enter)).isEqualTo(KeyAction.Enter)
        assertThat(state.onKeyTapped(KeyAction.Space)).isEqualTo(KeyAction.Space)
        // shift não é consumido por essas teclas (só por Char) — continua ativo.
        assertThat(state.shiftActive).isTrue()
    }
}
