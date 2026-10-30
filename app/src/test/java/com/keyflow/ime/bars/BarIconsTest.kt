package com.keyflow.ime.bars

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.core.shortcuts.ShortcutKey
import com.keyflow.ui.icons.KfIcons
import org.junit.Test

/** Mapa botão → ícone, fonte única da barra do teclado e do editor do app. */
class BarIconsTest {

    @Test
    fun `fixed buttons draw their own icons`() {
        assertThat(BarIcons.iconFor(BarButton.Mic, expandedMode = false)).isEqualTo(KfIcons.Mic)
        assertThat(BarIcons.iconFor(BarButton.ConfigBtn, expandedMode = false)).isEqualTo(KfIcons.Adjust)
        assertThat(BarIcons.iconFor(BarButton.Paste, expandedMode = false)).isEqualTo(KfIcons.Paste)
        assertThat(BarIcons.iconFor(BarButton.Joystick, expandedMode = false)).isEqualTo(KfIcons.Joystick)
        assertThat(BarIcons.iconFor(BarButton.GridTrigger("g1"), expandedMode = false)).isEqualTo(KfIcons.Grid)
        assertThat(BarIcons.iconFor(BarButton.RunFlow("a1b2c3"), expandedMode = false)).isEqualTo(KfIcons.TabFluxos)
        assertThat(BarIcons.iconFor(BarButton.RunFlow("a1b2c3"), expandedMode = true)).isEqualTo(KfIcons.TabFluxos)
    }

    @Test
    fun `toggle points up when collapsed and down when expanded`() {
        assertThat(BarIcons.iconFor(BarButton.ToggleExpand, expandedMode = false)).isEqualTo(KfIcons.ChevronUp)
        assertThat(BarIcons.iconFor(BarButton.ToggleExpand, expandedMode = true)).isEqualTo(KfIcons.ChevronDown)
    }

    @Test
    fun `enter key becomes the return icon while other single keys stay text`() {
        val enter = BarButton.SingleKey(ShortcutKey(66, label = BarIcons.ENTER_LABEL))
        val esc = BarButton.SingleKey(ShortcutKey(111, label = "esc"))
        assertThat(BarIcons.iconFor(enter, expandedMode = false)).isEqualTo(KfIcons.Return)
        assertThat(BarIcons.iconFor(esc, expandedMode = false)).isNull()
        assertThat(BarIcons.iconFor(BarButton.Candidates, expandedMode = true)).isNull()
    }

    @Test
    fun `mic glyph follows the state and keeps the timer text`() {
        assertThat(BarIcons.micGlyph(ButtonState.IDLE_READY, 0)).isEqualTo(BarIcons.MicGlyph(KfIcons.Mic, null))
        assertThat(BarIcons.micGlyph(ButtonState.RECORDING, 75))
            .isEqualTo(BarIcons.MicGlyph(KfIcons.RecordDot, MicVisuals.formatTime(75)))
        assertThat(BarIcons.micGlyph(ButtonState.PAUSED, 8).text).isEqualTo("0:08")
        assertThat(BarIcons.micGlyph(ButtonState.CANCELLING, 0)).isEqualTo(BarIcons.MicGlyph(KfIcons.Trash, "cancelado"))
        assertThat(BarIcons.micGlyph(ButtonState.PENDING_PASTE, 0)).isEqualTo(BarIcons.MicGlyph(KfIcons.Paste, "colar"))
    }

    @Test
    fun `every mic state has a glyph`() {
        ButtonState.entries.forEach { state -> assertThat(BarIcons.micGlyph(state, 0).icon).isNotNull() }
    }
}
