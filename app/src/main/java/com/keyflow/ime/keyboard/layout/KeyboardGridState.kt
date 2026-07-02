package com.keyflow.ime.keyboard.layout

/**
 * Estado puro da grade: camada ativa (letras/símbolos) e shift (LAY-03/06). Extraído do
 * `KeyboardGridView` para ser testável sem Canvas/device — a View só desenha e dirige toques pra cá.
 *
 * Shift: 1º toque = maiúscula de disparo único (a próxima letra); 2º toque dentro de
 * [doubleTapWindowMs] = caps-lock (trava até tocar shift de novo). [clock] injetável para o duplo-toque
 * ser testável sem relógio real.
 */
class KeyboardGridState(
    private val layout: KeyboardLayout,
    private val doubleTapWindowMs: Long = 300L,
    private val clock: () -> Long = { System.currentTimeMillis() },
    initialLayer: LayerId = LayerId.LETTERS,
) {
    // NUM-01: Layouts.numberLayout() só declara a camada NUMBER — sem isto, o estado nasceria em
    // LETTERS e `currentLayer` explodiria no primeiro acesso (layout.layers.getValue(LETTERS)).
    var currentLayerId: LayerId = initialLayer
        private set

    private var oneShotShift = false
    var capsLocked = false
        private set

    private var lastShiftTapAt = 0L

    val shiftActive: Boolean get() = oneShotShift || capsLocked

    val currentLayer: KeyboardLayer get() = layout.layers.getValue(currentLayerId)

    /**
     * Resolve o toque numa tecla. Devolve a [KeyAction] a emitir para fora (commitText/composing), ou
     * `null` quando o toque só mudou estado interno (troca de camada, shift).
     */
    fun onKeyTapped(action: KeyAction): KeyAction? = when (action) {
        is KeyAction.Layer -> {
            currentLayerId = action.target
            null
        }
        KeyAction.Shift -> {
            onShiftTapped()
            null
        }
        is KeyAction.Char -> {
            val emitted = if (shiftActive) action.copy(text = action.text.uppercase()) else action
            if (!capsLocked) oneShotShift = false
            emitted
        }
        KeyAction.Backspace, KeyAction.Enter, KeyAction.Space, KeyAction.Language -> action
    }

    /**
     * Aplica o mesmo shift do `onKeyTapped(Char)` (maiúscula + reset do disparo único) a uma variante já
     * resolvida pelo popup de acentos (KC-T5) — evita repassar pelo hit-test para algo que já é literal.
     */
    fun consumeShiftFor(text: String): String {
        val result = if (shiftActive) text.uppercase() else text
        if (!capsLocked) oneShotShift = false
        return result
    }

    private fun onShiftTapped() {
        val now = clock()
        when {
            capsLocked -> {
                capsLocked = false
                oneShotShift = false
            }
            oneShotShift && now - lastShiftTapAt <= doubleTapWindowMs -> {
                capsLocked = true
            }
            oneShotShift -> {
                // 2º toque, mas fora da janela: cancela o disparo único (toggle off), não reinicia outro.
                oneShotShift = false
            }
            else -> {
                oneShotShift = true
            }
        }
        lastShiftTapAt = now
    }
}
