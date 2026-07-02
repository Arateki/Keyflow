package com.keyflow.ime.keyboard

/**
 * Rastreia a palavra em composição a partir do fluxo de teclas (letras/backspace/separador), decidindo
 * quando pedir sugestões e quando encerrar a composição (SPL-01/02). Puro — sem `InputConnection`/
 * `SpellCheckerSession` — o `KeyFlowKeyboardService` só traduz o que isto devolve em
 * `setComposingText`/`finishComposingText`.
 */
class ComposingWordTracker {

    var currentWord: String = ""
        private set

    /** Letra digitada: acrescenta e devolve a palavra atualizada (para compor + pedir sugestões dela). */
    fun onLetter(letter: String): String {
        currentWord += letter
        return currentWord
    }

    /**
     * Backspace dentro da composição: devolve a palavra restante (pode ficar `""`, que ainda é
     * composição ativa — precisa `setComposingText("")`, não um delete normal), ou `null` quando não
     * havia composição alguma (o caller cai no delete-antes-do-cursor de sempre).
     */
    fun onBackspace(): String? {
        if (currentWord.isEmpty()) return null
        currentWord = currentWord.dropLast(1)
        return currentWord
    }

    /** Separador (espaço/enter/pontuação): encerra a composição atual. */
    fun onSeparator() {
        currentWord = ""
    }

    fun reset() {
        currentWord = ""
    }
}
