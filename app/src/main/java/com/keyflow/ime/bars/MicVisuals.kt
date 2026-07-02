package com.keyflow.ime.bars

import com.keyflow.core.ButtonState

/**
 * Visual do botão de mic por estado (rótulo + cor), espelhando o mapeamento da barra atual
 * (`KeyFlowKeyboardView.micVisuals`) para a `BarView` renderizar o mic em Canvas com paridade (SYNC-01).
 * Constantes fixas entre claro/escuro (acentos Ateliê).
 */
object MicVisuals {
    const val ACCENT_TEAL = 0xFF4E9683.toInt()
    const val ACCENT_RECORD = 0xFFC6873F.toInt()
    const val ACCENT_TERRACOTTA = 0xFFD9694B.toInt()
    const val MIC_READY = 0xFF4E9683.toInt()
    const val MIC_NO_FIELD = 0xFF9C9072.toInt()

    fun visuals(state: ButtonState, elapsedSeconds: Int): Pair<String, Int> = when (state) {
        ButtonState.IDLE_READY -> "🎙" to MIC_READY
        ButtonState.IDLE_NO_FIELD -> "🎙" to MIC_NO_FIELD
        ButtonState.RECORDING -> "● ${formatTime(elapsedSeconds)}" to ACCENT_RECORD
        ButtonState.PAUSED -> "⏸ ${formatTime(elapsedSeconds)}" to ACCENT_RECORD
        ButtonState.SENDING -> "···" to ACCENT_TEAL
        ButtonState.ERROR -> "✖" to ACCENT_TERRACOTTA
        ButtonState.CANCELLING -> "🗑 cancelado" to ACCENT_TERRACOTTA
        ButtonState.PENDING_PASTE -> "📋 colar" to ACCENT_TERRACOTTA
    }

    fun formatTime(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
