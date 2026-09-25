package com.keyflow.transcription.partial

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ButtonState
import org.junit.Test

/** NM-T17: quando o indicador/visualização/edição aparecem, e o texto do indicador. */
class PartialUiTest {

    private val some = PartialSnapshot(text = "olá mundo", transcribing = false, failed = 0)
    private val empty = PartialSnapshot(text = "", transcribing = false, failed = 0)

    @Test
    fun `badge only while paused with content`() {
        assertThat(PartialUi.resolve(PartialUiMode.HIDDEN, ButtonState.PAUSED, some, viewEdit = false)).isEqualTo(PartialUiMode.BADGE)
        assertThat(PartialUi.resolve(PartialUiMode.HIDDEN, ButtonState.RECORDING, some, viewEdit = false)).isEqualTo(PartialUiMode.HIDDEN)
        assertThat(PartialUi.resolve(PartialUiMode.HIDDEN, ButtonState.PAUSED, empty, viewEdit = false)).isEqualTo(PartialUiMode.HIDDEN)
        assertThat(PartialUi.resolve(PartialUiMode.HIDDEN, ButtonState.PAUSED, null, viewEdit = true)).isEqualTo(PartialUiMode.HIDDEN)
    }

    @Test
    fun `a pending or failed segment counts as content`() {
        assertThat(PartialUi.hasContent(empty.copy(transcribing = true))).isTrue()
        assertThat(PartialUi.hasContent(empty.copy(failed = 1))).isTrue()
    }

    @Test
    fun `viewer and input stay open while recording and pausing, only with view-edit on`() {
        listOf(ButtonState.RECORDING, ButtonState.PAUSED).forEach { state ->
            assertThat(PartialUi.resolve(PartialUiMode.INPUT, state, some, viewEdit = true)).isEqualTo(PartialUiMode.INPUT)
            assertThat(PartialUi.resolve(PartialUiMode.VIEWER, state, some, viewEdit = true)).isEqualTo(PartialUiMode.VIEWER)
        }
        assertThat(PartialUi.resolve(PartialUiMode.INPUT, ButtonState.PAUSED, some, viewEdit = false)).isEqualTo(PartialUiMode.BADGE)
    }

    @Test
    fun `sending and cancelling hide everything and close what was open`() {
        listOf(ButtonState.SENDING, ButtonState.CANCELLING).forEach { state ->
            assertThat(PartialUi.resolve(PartialUiMode.INPUT, state, some, viewEdit = true)).isEqualTo(PartialUiMode.HIDDEN)
            assertThat(PartialUi.closesOpened(state)).isTrue()
        }
        assertThat(PartialUi.closesOpened(ButtonState.PAUSED)).isFalse()
    }

    @Test
    fun `tapping the badge opens the viewer only with view-edit on`() {
        assertThat(PartialUi.onBadgeTap(viewEdit = true)).isEqualTo(PartialUiMode.VIEWER)
        assertThat(PartialUi.onBadgeTap(viewEdit = false)).isEqualTo(PartialUiMode.HIDDEN)
    }

    @Test
    fun `badge labels`() {
        assertThat(PartialUi.badgeLabel(some)).isEqualTo("parcial · 2 palavras")
        assertThat(PartialUi.badgeLabel(empty.copy(text = "oi"))).isEqualTo("parcial · 1 palavra")
        assertThat(PartialUi.badgeLabel(empty.copy(transcribing = true))).isEqualTo("parcial · transcrevendo…")
        assertThat(PartialUi.badgeLabel(some.copy(transcribing = true))).isEqualTo("parcial · 2 palavras · …")
        assertThat(PartialUi.badgeLabel(empty.copy(failed = 2))).isEqualTo("parcial · 2 trechos a refazer")
    }
}
