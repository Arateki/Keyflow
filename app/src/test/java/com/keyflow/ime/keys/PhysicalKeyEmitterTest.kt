package com.keyflow.ime.keys

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.keyflow.core.shortcuts.ShortcutKey
import org.junit.Test

/**
 * Plano de emissão física (SB-T2 / EMT-02..04/06). Verifica o `plan` puro (sequências exatas de
 * ação/keycode/metaState); a conversão para `KeyEvent` real fica nos instrumentados. As constantes de
 * `KeyEvent` são compile-time (inlined) — seguras em JVM.
 */
class PhysicalKeyEmitterTest {

    private val ctrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    private val shiftMeta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
    private val altMeta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON

    @Test
    fun `chord with ctrl wraps key between modifier down and up`() {
        val plan = PhysicalKeyEmitter.plan(ShortcutKey(KeyEvent.KEYCODE_B, ctrl = true, label = "Ctrl+B"))
        assertThat(plan).containsExactly(
            PlannedKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrlMeta),
            PlannedKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, ctrlMeta),
            PlannedKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, ctrlMeta),
            PlannedKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
        ).inOrder()
    }

    @Test
    fun `multiple modifiers accumulate on down and release in reverse`() {
        val plan = PhysicalKeyEmitter.plan(
            ShortcutKey(KeyEvent.KEYCODE_X, ctrl = true, shift = true, label = "Ctrl+Shift+X")
        )
        assertThat(plan).containsExactly(
            PlannedKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrlMeta),
            PlannedKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, ctrlMeta or shiftMeta),
            PlannedKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X, ctrlMeta or shiftMeta),
            PlannedKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_X, ctrlMeta or shiftMeta),
            PlannedKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, ctrlMeta),
            PlannedKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0),
        ).inOrder()
    }

    @Test
    fun `key without modifiers is a bare down-up pair`() {
        val plan = PhysicalKeyEmitter.plan(ShortcutKey(KeyEvent.KEYCODE_ESCAPE, label = "esc"))
        assertThat(plan).containsExactly(
            PlannedKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0),
            PlannedKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0),
        ).inOrder()
    }

    @Test
    fun `chordOf merges prefix modifiers into the key and ignores prefix keycode`() {
        val prefix = ShortcutKey(KeyEvent.KEYCODE_B, ctrl = true, alt = true, label = "Ctrl+Alt+B")
        val key = ShortcutKey(KeyEvent.KEYCODE_H, shift = true, label = "Shift+H")

        val chord = PhysicalKeyEmitter.chordOf(prefix, key)

        assertThat(chord.keyCode).isEqualTo(KeyEvent.KEYCODE_H)
        assertThat(chord.ctrl).isTrue()
        assertThat(chord.alt).isTrue()
        assertThat(chord.shift).isTrue()
        assertThat(chord.meta).isFalse()
        // O plano do chord fundido abre com Ctrl→Alt→Shift e fecha em ordem inversa.
        val plan = PhysicalKeyEmitter.plan(chord)
        assertThat(plan.first())
            .isEqualTo(PlannedKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrlMeta))
        assertThat(plan[1].keyCode).isEqualTo(KeyEvent.KEYCODE_ALT_LEFT)
        assertThat(plan[1].metaState).isEqualTo(ctrlMeta or altMeta)
        assertThat(plan.last().metaState).isEqualTo(0)
    }

    @Test
    fun `emit without input connection is a safe no-op`() {
        val emitter = PhysicalKeyEmitter(clock = { 0L })
        assertThat(emitter.emit(null, ShortcutKey(KeyEvent.KEYCODE_TAB, label = "Tab"))).isFalse()
        assertThat(
            emitter.emitSequence(
                null,
                ShortcutKey(KeyEvent.KEYCODE_B, ctrl = true, label = "Ctrl+B"),
                ShortcutKey(KeyEvent.KEYCODE_C, label = "c"),
            )
        ).isFalse()
    }
}
