package com.keyflow.ui.terminal

import androidx.compose.ui.input.key.KeyEvent
import org.connectbot.terminal.ModifierManager

/** O que a tela liga ao `Terminal` do termlib para a entrada: interceptação de teclas e Ctrl/Alt pegajosos (a CMP-T14 preenche). */
class EntradaParaTerminal(
    val interceptar: ((KeyEvent) -> Boolean)? = null,
    val modificadores: ModifierManager? = null,
) {
    companion object {
        val Padrao = EntradaParaTerminal()
    }
}
