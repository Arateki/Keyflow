package com.keyflow.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit da decisão pura de composição (Q3, 2026-07-02 — bug do Termius): campos `TYPE_NULL` (terminal,
 * ex.: Termux/Termius) não renderizam texto "compondo" (`setComposingText`), só texto já comitado — a
 * letra ficava invisível até um delimitador finalizar a composição (o dado chegava, só não aparecia).
 * `shouldCompose` decide entre compor (corretor) ou comitar direto por letra.
 */
class KeyFlowKeyboardServiceTest {

    @Test
    fun `letters compose when canvas grid is on and field supports composing`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(useCanvasGrid = true, rawInputField = false, char = "o"))
            .isTrue()
    }

    @Test
    fun `letters commit directly in raw input fields even with canvas grid on`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(useCanvasGrid = true, rawInputField = true, char = "o"))
            .isFalse()
    }

    @Test
    fun `letters commit directly when canvas grid is off regardless of field`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(useCanvasGrid = false, rawInputField = false, char = "o"))
            .isFalse()
        assertThat(KeyFlowKeyboardService.shouldCompose(useCanvasGrid = false, rawInputField = true, char = "o"))
            .isFalse()
    }

    @Test
    fun `non-letters never compose regardless of field`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(useCanvasGrid = true, rawInputField = false, char = "1"))
            .isFalse()
        assertThat(KeyFlowKeyboardService.shouldCompose(useCanvasGrid = true, rawInputField = false, char = ","))
            .isFalse()
    }

    @Test
    fun `multi-character strings never compose`() {
        assertThat(KeyFlowKeyboardService.shouldCompose(useCanvasGrid = true, rawInputField = false, char = "ab"))
            .isFalse()
    }
}
