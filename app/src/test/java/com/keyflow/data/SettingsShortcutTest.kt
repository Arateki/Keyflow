package com.keyflow.data

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.OrbShortcutAction
import com.keyflow.core.ShortcutOverflow
import org.junit.Test

/** Atalhos por rajada de toques (SHORTCUT-01..03). Lógica pura, sem Context. */
class SettingsShortcutTest {

    private val settings = Settings(
        shortcut1Tap = OrbShortcutAction.NONE,
        shortcut2Tap = OrbShortcutAction.TOGGLE_KEYBOARD,
        shortcut3Tap = OrbShortcutAction.SWITCH_IME,
    )

    @Test
    fun `shortcutForTaps maps each count to its configured action`() {
        assertThat(settings.shortcutForTaps(1)).isEqualTo(OrbShortcutAction.NONE)
        assertThat(settings.shortcutForTaps(2)).isEqualTo(OrbShortcutAction.TOGGLE_KEYBOARD)
        assertThat(settings.shortcutForTaps(3)).isEqualTo(OrbShortcutAction.SWITCH_IME)
    }

    @Test
    fun `shortcutForTaps returns NONE for counts outside 1 to 3`() {
        assertThat(settings.shortcutForTaps(0)).isEqualTo(OrbShortcutAction.NONE)
        assertThat(settings.shortcutForTaps(4)).isEqualTo(OrbShortcutAction.NONE)
    }

    @Test
    fun `resolveShortcut returns NONE for less than 1 tap`() {
        assertThat(settings.resolveShortcut(0)).isEqualTo(OrbShortcutAction.NONE)
    }

    @Test
    fun `resolveShortcut delegates to shortcutForTaps within 1 to 3`() {
        assertThat(settings.resolveShortcut(1)).isEqualTo(OrbShortcutAction.NONE)
        assertThat(settings.resolveShortcut(2)).isEqualTo(OrbShortcutAction.TOGGLE_KEYBOARD)
        assertThat(settings.resolveShortcut(3)).isEqualTo(OrbShortcutAction.SWITCH_IME)
    }

    @Test
    fun `resolveShortcut overflow with NEAREST_DOWN falls back to the 3-tap action`() {
        val s = settings.copy(shortcutOverflow = ShortcutOverflow.NEAREST_DOWN)
        assertThat(s.resolveShortcut(4)).isEqualTo(OrbShortcutAction.SWITCH_IME)
        assertThat(s.resolveShortcut(10)).isEqualTo(OrbShortcutAction.SWITCH_IME)
    }

    @Test
    fun `resolveShortcut overflow with NEAREST_DOWN falls back to 2-tap when 3-tap is NONE`() {
        val s = settings.copy(
            shortcutOverflow = ShortcutOverflow.NEAREST_DOWN,
            shortcut3Tap = OrbShortcutAction.NONE,
        )
        assertThat(s.resolveShortcut(4)).isEqualTo(OrbShortcutAction.TOGGLE_KEYBOARD)
    }

    @Test
    fun `resolveShortcut overflow with NEAREST_DOWN is NONE when 2 and 3 taps are both NONE`() {
        val s = settings.copy(
            shortcutOverflow = ShortcutOverflow.NEAREST_DOWN,
            shortcut2Tap = OrbShortcutAction.NONE,
            shortcut3Tap = OrbShortcutAction.NONE,
        )
        assertThat(s.resolveShortcut(5)).isEqualTo(OrbShortcutAction.NONE)
    }

    @Test
    fun `resolveShortcut overflow with CANCEL is always NONE`() {
        val s = settings.copy(shortcutOverflow = ShortcutOverflow.CANCEL)
        assertThat(s.resolveShortcut(4)).isEqualTo(OrbShortcutAction.NONE)
    }

    // ── VISION_MODE (MODE-01) ─────────────────────────────────────────────────

    @Test
    fun `VISION_MODE can be mapped to any burst of 1 to 3 taps`() {
        for (taps in 1..3) {
            val s = Settings(shortcut1Tap = OrbShortcutAction.NONE, shortcut2Tap = OrbShortcutAction.NONE, shortcut3Tap = OrbShortcutAction.NONE)
            val mapped = when (taps) {
                1 -> s.copy(shortcut1Tap = OrbShortcutAction.VISION_MODE)
                2 -> s.copy(shortcut2Tap = OrbShortcutAction.VISION_MODE)
                else -> s.copy(shortcut3Tap = OrbShortcutAction.VISION_MODE)
            }
            assertThat(mapped.shortcutForTaps(taps)).isEqualTo(OrbShortcutAction.VISION_MODE)
            assertThat(mapped.resolveShortcut(taps)).isEqualTo(OrbShortcutAction.VISION_MODE)
        }
    }

    @Test
    fun `overflow NEAREST_DOWN falls back to VISION_MODE when it is the 3-tap action`() {
        val s = settings.copy(shortcut3Tap = OrbShortcutAction.VISION_MODE, shortcutOverflow = ShortcutOverflow.NEAREST_DOWN)
        assertThat(s.resolveShortcut(4)).isEqualTo(OrbShortcutAction.VISION_MODE)
    }

    @Test
    fun `overflow CANCEL never triggers VISION_MODE`() {
        val s = settings.copy(shortcut3Tap = OrbShortcutAction.VISION_MODE, shortcutOverflow = ShortcutOverflow.CANCEL)
        assertThat(s.resolveShortcut(4)).isEqualTo(OrbShortcutAction.NONE)
    }

    /** A Visão não traduz (decisão do Yan, 2026-10-27): o campo do idioma de destino saiu das Settings. */
    @Test
    fun `settings carry no translation target for vision`() {
        assertThat(Settings::class.java.declaredFields.map { it.name }.filter { it.contains("ranslat") }).isEmpty()
    }
}
