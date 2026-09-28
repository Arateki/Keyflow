package com.keyflow.ime.bars

import com.keyflow.core.ButtonState
import com.keyflow.core.shortcuts.BarButton
import com.keyflow.ui.icons.KfIconSpec
import com.keyflow.ui.icons.KfIcons

/**
 * Qual ícone próprio cada botão da barra desenha — fonte única para a `BarView` (teclado) e para os
 * chips do editor de barras no app, que precisam sair iguais (a prévia é o teclado real). Antes eram
 * emoji e caracteres de fonte (🎙 ⚙ ⌃ ↓ ⏎ 🕹), que variavam de aparelho para aparelho.
 *
 * Os RÓTULOS em texto continuam existindo ([MicVisuals.visuals], `ShortcutKey.label`): são o nome
 * acessível do botão e o que os testes procuram — só o desenho troca.
 */
object BarIcons {

    /** Ícone do botão, ou `null` quando ele é um rótulo em texto (teclas avulsas como "esc", a faixa). */
    fun iconFor(button: BarButton, expandedMode: Boolean): KfIconSpec? = when (button) {
        BarButton.Mic -> KfIcons.Mic
        BarButton.ConfigBtn -> KfIcons.Adjust
        BarButton.ToggleExpand -> if (expandedMode) KfIcons.ChevronDown else KfIcons.ChevronUp
        BarButton.Paste -> KfIcons.Paste
        BarButton.Joystick -> KfIcons.Joystick
        is BarButton.GridTrigger -> KfIcons.Grid
        is BarButton.SingleKey -> if (button.key.label == ENTER_LABEL) KfIcons.Return else null
        BarButton.Candidates -> null
    }

    /**
     * Desenho do mic por estado: o ícone e, quando há, o texto que vai ao lado dele (cronômetro,
     * "cancelado", "colar"). Espelha [MicVisuals.visuals], trocando o glifo de fonte pelo ícone.
     */
    fun micGlyph(state: ButtonState, elapsedSeconds: Int): MicGlyph = when (state) {
        ButtonState.IDLE_READY, ButtonState.IDLE_NO_FIELD -> MicGlyph(KfIcons.Mic, null)
        ButtonState.RECORDING -> MicGlyph(KfIcons.RecordDot, MicVisuals.formatTime(elapsedSeconds))
        ButtonState.PAUSED -> MicGlyph(KfIcons.Pause, MicVisuals.formatTime(elapsedSeconds))
        ButtonState.SENDING -> MicGlyph(KfIcons.Sending, null)
        ButtonState.ERROR -> MicGlyph(KfIcons.Close, null)
        ButtonState.CANCELLING -> MicGlyph(KfIcons.Trash, "cancelado")
        ButtonState.PENDING_PASTE -> MicGlyph(KfIcons.Paste, "colar")
    }

    data class MicGlyph(val icon: KfIconSpec, val text: String?)

    /** Rótulo que o seletor de teclas físicas dá ao Enter (`PhysicalKeyMap`). */
    const val ENTER_LABEL = "⏎"
}
