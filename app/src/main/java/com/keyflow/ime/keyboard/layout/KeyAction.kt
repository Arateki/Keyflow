package com.keyflow.ime.keyboard.layout

/** Camada ativa da grade: letras (QWERTY), símbolos (`?123`) ou numérica (campo TYPE_CLASS_NUMBER/PHONE). */
enum class LayerId { LETTERS, SYMBOLS, NUMBER }

/**
 * Ação de uma tecla da grade Canvas (LAY-02). Sealed e extensível: customizações futuras (macro) entram
 * como um caso novo, sem mexer no rendering (EXT-02).
 */
sealed interface KeyAction {
    // [digitHint] é só um rótulo pequeno pra desenhar no canto da tecla (estilo Gboard), indicando o
    // dígito acessível por long-press — não afeta o toque nem precisa bater com o 1º item de [variants].
    data class Char(val text: String, val variants: List<String> = emptyList(), val digitHint: String? = null) :
        KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Shift : KeyAction
    data class Layer(val target: LayerId) : KeyAction
    data object Language : KeyAction
}
