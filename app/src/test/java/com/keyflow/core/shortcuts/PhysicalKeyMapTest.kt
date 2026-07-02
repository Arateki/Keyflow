package com.keyflow.core.shortcuts

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Mapa keycode→rótulo do teclado físico simulado (SB-T4 / CFG-05 — parte pura). */
class PhysicalKeyMapTest {

    @Test
    fun `letters digits and function keys have labels`() {
        assertThat(PhysicalKeyMap.baseLabel(KeyEvent.KEYCODE_B)).isEqualTo("b")
        assertThat(PhysicalKeyMap.baseLabel(KeyEvent.KEYCODE_7)).isEqualTo("7")
        assertThat(PhysicalKeyMap.baseLabel(KeyEvent.KEYCODE_F12)).isEqualTo("F12")
        assertThat(PhysicalKeyMap.baseLabel(KeyEvent.KEYCODE_ESCAPE)).isEqualTo("Esc")
        assertThat(PhysicalKeyMap.baseLabel(KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo("←")
    }

    @Test
    fun `label prepends modifiers in fixed order`() {
        assertThat(PhysicalKeyMap.label(KeyEvent.KEYCODE_B, ctrl = true)).isEqualTo("Ctrl+b")
        assertThat(PhysicalKeyMap.label(KeyEvent.KEYCODE_X, ctrl = true, shift = true, alt = true))
            .isEqualTo("Ctrl+Alt+Shift+x")
    }

    @Test
    fun `unknown keycode falls back to numeric label`() {
        assertThat(PhysicalKeyMap.label(999)).isEqualTo("#999")
    }

    @Test
    fun `key builds ShortcutKey with assembled label`() {
        val key = PhysicalKeyMap.key(KeyEvent.KEYCODE_B, ctrl = true)
        assertThat(key).isEqualTo(ShortcutKey(KeyEvent.KEYCODE_B, ctrl = true, label = "Ctrl+b"))
    }
}
