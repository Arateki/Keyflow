package com.keyflow.computador.app

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import org.connectbot.terminal.ModifierManager

/** Do código de tecla do Android para a [Tecla] que o app mapeia sozinho (`null` = deixa para o termlib). */
internal fun teclaDoAndroid(codigo: Int): Tecla? = when (codigo) {
    in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z -> Tecla.Caractere('a' + (codigo - AndroidKeyEvent.KEYCODE_A))
    AndroidKeyEvent.KEYCODE_SPACE -> Tecla.Caractere(' ')
    AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> Tecla.Caractere('[')
    AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> Tecla.Caractere(']')
    AndroidKeyEvent.KEYCODE_BACKSLASH -> Tecla.Caractere('\\')
    AndroidKeyEvent.KEYCODE_SLASH -> Tecla.Caractere('/')
    AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> Tecla.Enter
    AndroidKeyEvent.KEYCODE_TAB -> Tecla.Tab
    AndroidKeyEvent.KEYCODE_ESCAPE -> Tecla.Esc
    AndroidKeyEvent.KEYCODE_DEL -> Tecla.Backspace
    AndroidKeyEvent.KEYCODE_FORWARD_DEL -> Tecla.Delete
    AndroidKeyEvent.KEYCODE_INSERT -> Tecla.Insert
    AndroidKeyEvent.KEYCODE_DPAD_UP -> Tecla.Cima
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> Tecla.Baixo
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> Tecla.Esquerda
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> Tecla.Direita
    AndroidKeyEvent.KEYCODE_MOVE_HOME -> Tecla.Home
    AndroidKeyEvent.KEYCODE_MOVE_END -> Tecla.End
    AndroidKeyEvent.KEYCODE_PAGE_UP -> Tecla.PageUp
    AndroidKeyEvent.KEYCODE_PAGE_DOWN -> Tecla.PageDown
    in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 -> Tecla.F(codigo - AndroidKeyEvent.KEYCODE_F1 + 1)
    else -> null
}

/**
 * A entrada do terminal (CMP-26, 27): liga ao `Terminal` do termlib a interceptação de teclas e os modificadores pegajosos.
 *
 * O `KeyEvent` chega por **dois** caminhos conforme o teclado (a InputConnection e a view; a T1 mediu `aa`): [DeduplicadorDeTeclas] ignora o
 * segundo. As teclas que o app mapeia (Ctrl/Alt+letra, Enter, Tab, Esc, Backspace, setas, Home/End, PageUp/Down, F1–F12) viram bytes por
 * [MapaTecladoFisico] e saem por [LigacaoEmulador.enviar]; as demais (letras e símbolos soltos) seguem ao termlib, que cuida da composição.
 * O texto digitado/ditado pelo teclado de tela chega como `commitText` (UTF-8, sem Enter), pelo caminho normal do termlib.
 */
class EntradaDoTerminal(private val ligacao: LigacaoEmulador) {
    val pegajosos = ModificadoresPegajosos()
    private val dedup = DeduplicadorDeTeclas()

    /** O termlib consulta isto para as teclas de texto que ele mesmo trata (Ctrl ou Alt travados nas teclas extras valem para elas). */
    val modificadores: ModifierManager = object : ModifierManager {
        override fun isCtrlActive() = pegajosos.atuais().ctrl
        override fun isAltActive() = pegajosos.atuais().alt
        override fun isShiftActive() = false
        override fun clearTransients() = pegajosos.consumir()
    }

    val interceptar: (KeyEvent) -> Boolean = { evento -> aoTecla(evento.nativeKeyEvent) }

    internal fun aoTecla(k: AndroidKeyEvent): Boolean {
        if (dedup.duplicada(k.action, k.keyCode, k.downTime, k.eventTime, k.repeatCount)) return true
        val tecla = teclaDoAndroid(k.keyCode) ?: return false
        val mods = Modificadores(k.isCtrlPressed, k.isAltPressed, k.isShiftPressed) + pegajosos.atuais()
        val bytes = MapaTecladoFisico.bytes(tecla, mods, ligacao.modoAplicacaoDasSetas) ?: return false
        if (k.action == AndroidKeyEvent.ACTION_DOWN) { ligacao.enviar(bytes); pegajosos.consumir() }
        return true
    }

    /** Uma tecla extra tocada (já em bytes): sai pelo mesmo caminho, com Ctrl/Alt pegajosos aplicados. */
    fun teclaExtra(tecla: Tecla) {
        val bytes = MapaTecladoFisico.bytes(tecla, pegajosos.atuais(), ligacao.modoAplicacaoDasSetas) ?: return
        ligacao.enviar(bytes)
        pegajosos.consumir()
    }

    /** Um caractere das teclas extras (`|`, `~`, `/`, `-`): com Ctrl/Alt pegajoso vira Ctrl+/ etc.; sem, o caractere puro. */
    fun caractereExtra(c: Char) {
        val m = pegajosos.atuais()
        val bytes = MapaTecladoFisico.bytes(Tecla.Caractere(c), m) ?: c.toString().toByteArray(Charsets.UTF_8)
        ligacao.enviar(bytes)
        pegajosos.consumir()
    }
}
