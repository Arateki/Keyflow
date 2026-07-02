package com.keyflow.core.shortcuts

import android.view.KeyEvent

/**
 * Mapa keycode → rótulo do teclado físico simulado (picker/células/lente — CFG-05). Só constantes de
 * `KeyEvent` (compile-time, inlined) — puro e testável em JVM.
 */
object PhysicalKeyMap {

    private val labels: Map<Int, String> = buildMap {
        ('a'..'z').forEachIndexed { i, c -> put(KeyEvent.KEYCODE_A + i, c.toString()) }
        ('0'..'9').forEachIndexed { i, c -> put(KeyEvent.KEYCODE_0 + i, c.toString()) }
        (1..12).forEach { put(KeyEvent.KEYCODE_F1 + it - 1, "F$it") }
        put(KeyEvent.KEYCODE_ESCAPE, "Esc")
        put(KeyEvent.KEYCODE_TAB, "Tab")
        put(KeyEvent.KEYCODE_ENTER, "⏎")
        put(KeyEvent.KEYCODE_SPACE, "Espaço")
        put(KeyEvent.KEYCODE_DEL, "⌫")
        put(KeyEvent.KEYCODE_FORWARD_DEL, "Del")
        put(KeyEvent.KEYCODE_MOVE_HOME, "Home")
        put(KeyEvent.KEYCODE_MOVE_END, "End")
        put(KeyEvent.KEYCODE_PAGE_UP, "PgUp")
        put(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn")
        put(KeyEvent.KEYCODE_INSERT, "Ins")
        put(KeyEvent.KEYCODE_DPAD_UP, "↑")
        put(KeyEvent.KEYCODE_DPAD_DOWN, "↓")
        put(KeyEvent.KEYCODE_DPAD_LEFT, "←")
        put(KeyEvent.KEYCODE_DPAD_RIGHT, "→")
        put(KeyEvent.KEYCODE_MINUS, "-")
        put(KeyEvent.KEYCODE_EQUALS, "=")
        put(KeyEvent.KEYCODE_LEFT_BRACKET, "[")
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, "]")
        put(KeyEvent.KEYCODE_BACKSLASH, "\\")
        put(KeyEvent.KEYCODE_SEMICOLON, ";")
        put(KeyEvent.KEYCODE_APOSTROPHE, "'")
        put(KeyEvent.KEYCODE_SLASH, "/")
        put(KeyEvent.KEYCODE_COMMA, ",")
        put(KeyEvent.KEYCODE_PERIOD, ".")
        put(KeyEvent.KEYCODE_GRAVE, "`")
    }

    /** Rótulo base da tecla, ou null se o keycode não está no mapa do picker. */
    fun baseLabel(keyCode: Int): String? = labels[keyCode]

    /** Rótulo completo com modificadores ("Ctrl+Shift+b"); keycode fora do mapa vira `#<code>`. */
    fun label(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        if (meta) append("Meta+")
        append(baseLabel(keyCode) ?: "#$keyCode")
    }

    /** Constrói o [ShortcutKey] já com o rótulo montado — é o que o picker devolve. */
    fun key(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ): ShortcutKey = ShortcutKey(
        keyCode = keyCode,
        ctrl = ctrl,
        alt = alt,
        shift = shift,
        meta = meta,
        label = label(keyCode, ctrl, alt, shift, meta),
    )
}
