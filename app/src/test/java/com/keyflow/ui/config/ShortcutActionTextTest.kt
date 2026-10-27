package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.OrbShortcutAction
import org.junit.Test

/** Rótulo e explicação de cada ação de atalho (lista da aba Sobrepor, folha de escolha e roteiro do tour). */
class ShortcutActionTextTest {

    @Test
    fun `every action has a label and a description`() {
        for (action in OrbShortcutAction.entries) {
            assertThat(shortcutActionLabel(action)).isNotEmpty()
            assertThat(shortcutActionSub(action)).isNotEmpty()
        }
    }

    @Test
    fun `labels are distinct so the list never shows two equal rows`() {
        val labels = OrbShortcutAction.entries.map { shortcutActionLabel(it) }
        assertThat(labels).containsNoDuplicates()
    }

    @Test
    fun `VISION_MODE is named after the screen capture`() {
        assertThat(shortcutActionLabel(OrbShortcutAction.VISION_MODE)).contains("Visão")
        assertThat(shortcutActionSub(OrbShortcutAction.VISION_MODE)).contains("gravação")
    }
}
