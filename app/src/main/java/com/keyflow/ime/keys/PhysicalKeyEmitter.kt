package com.keyflow.ime.keys

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.keyflow.core.shortcuts.ShortcutKey

/**
 * Evento planejado de uma emissão física (puro): é o que os unit tests verificam sem precisar construir
 * `KeyEvent` de verdade (os stubs do android.jar lançam em JVM). A conversão para `KeyEvent` acontece só
 * em [PhysicalKeyEmitter.emit], coberta pelos instrumentados.
 */
data class PlannedKeyEvent(val action: Int, val keyCode: Int, val metaState: Int)

/**
 * Camada única de emissão de teclas físicas (EMT-02..06): tudo que o KeyFlow "digita" como teclado físico
 * (chords Ctrl/Alt/Shift/Meta, sequências prefixo→tecla, Enter/Esc, setas da alavanca) passa por aqui.
 *
 * A ordem dos eventos reproduz um teclado físico real — modificadores DOWN (metaState acumulando), tecla
 * DOWN e UP com o metaState completo, modificadores UP em ordem inversa (metaState decrescendo) — que é o
 * que apps de terminal (Termux/Termius/JuiceSSH) esperam. Todos os eventos de uma emissão compartilham o
 * mesmo `downTime` e usam `deviceId = VIRTUAL_KEYBOARD`.
 */
class PhysicalKeyEmitter(private val clock: () -> Long = SystemClock::uptimeMillis) {

    /** Emite [key] como chord único (EMT-02). Sem `InputConnection` é no-op seguro (EMT-06). */
    fun emit(ic: InputConnection?, key: ShortcutKey): Boolean {
        ic ?: return false
        val t = clock()
        plan(key).forEach { e ->
            ic.sendKeyEvent(
                KeyEvent(t, t, e.action, e.keyCode, 0, e.metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0)
            )
        }
        return true
    }

    /** Prefixo como chord completo e DEPOIS a tecla, em emissões separadas (EMT-03 — caso tmux). */
    fun emitSequence(ic: InputConnection?, prefix: ShortcutKey, key: ShortcutKey): Boolean {
        ic ?: return false
        emit(ic, prefix)
        return emit(ic, key)
    }

    companion object {
        /**
         * Plano puro da emissão de [key] (a lógica inteira do chord, unit-testável). Modificadores na
         * ordem Ctrl→Alt→Shift→Meta no DOWN e inversa no UP; o UP de cada modificador já não carrega o
         * próprio meta (estado APÓS o evento, como hardware real).
         */
        fun plan(key: ShortcutKey): List<PlannedKeyEvent> {
            val mods = buildList {
                if (key.ctrl) add(KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
                if (key.alt) add(KeyEvent.KEYCODE_ALT_LEFT to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
                if (key.shift) add(KeyEvent.KEYCODE_SHIFT_LEFT to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
                if (key.meta) add(KeyEvent.KEYCODE_META_LEFT to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON))
            }
            return buildList {
                var acc = 0
                mods.forEach { (code, meta) ->
                    acc = acc or meta
                    add(PlannedKeyEvent(KeyEvent.ACTION_DOWN, code, acc))
                }
                add(PlannedKeyEvent(KeyEvent.ACTION_DOWN, key.keyCode, acc))
                add(PlannedKeyEvent(KeyEvent.ACTION_UP, key.keyCode, acc))
                mods.asReversed().forEach { (code, meta) ->
                    acc = acc and meta.inv()
                    add(PlannedKeyEvent(KeyEvent.ACTION_UP, code, acc))
                }
            }
        }

        /**
         * Fusão do modo CHORD (EMT-04): os modificadores do [prefix] entram na [key] e o resultado é um
         * chord único ("Ctrl+" + grade `x` → Ctrl+X). O keyCode do prefixo é ignorado de propósito — no
         * modo CHORD o prefixo só contribui modificadores.
         */
        fun chordOf(prefix: ShortcutKey, key: ShortcutKey): ShortcutKey = key.copy(
            ctrl = prefix.ctrl || key.ctrl,
            alt = prefix.alt || key.alt,
            shift = prefix.shift || key.shift,
            meta = prefix.meta || key.meta,
        )
    }
}
