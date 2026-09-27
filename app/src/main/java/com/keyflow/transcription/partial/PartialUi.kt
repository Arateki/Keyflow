package com.keyflow.transcription.partial

import com.keyflow.core.ButtonState

/** O que aparece junto à orbe para o parcial (PAR-04/05). */
enum class PartialUiMode { HIDDEN, BADGE, VIEWER, INPUT }

/**
 * Regras puras da UI do parcial. [opened] é o que o usuário abriu (visualizar/editar); o resultado é o
 * que de fato fica na tela, dado o estado da orbe e da sessão.
 */
object PartialUi {

    /**
     * - Sem sessão → nada.
     * - Visualização/edição abertas ficam abertas enquanto se grava e pausa (o texto novo aparece junto,
     *   PAR-05) — só com o 2º toggle.
     * - Enviando/cancelando → nada: a entrega precisa do foco de volta no campo de origem, e o banner de
     *   cancelar ocupa a vez.
     * - Pausada com conteúdo → o indicador (PAR-04).
     */
    fun resolve(opened: PartialUiMode, button: ButtonState, snapshot: PartialSnapshot?, viewEdit: Boolean): PartialUiMode {
        if (snapshot == null) return PartialUiMode.HIDDEN
        if (button == ButtonState.SENDING || button == ButtonState.CANCELLING) return PartialUiMode.HIDDEN
        if (viewEdit && (opened == PartialUiMode.VIEWER || opened == PartialUiMode.INPUT)) return opened
        return if (button == ButtonState.PAUSED && hasContent(snapshot)) PartialUiMode.BADGE else PartialUiMode.HIDDEN
    }

    /** Estados em que o que o usuário abriu se fecha sozinho (volta ao indicador depois). */
    fun closesOpened(button: ButtonState): Boolean = button == ButtonState.SENDING || button == ButtonState.CANCELLING

    fun hasContent(snapshot: PartialSnapshot): Boolean =
        snapshot.text.isNotBlank() || snapshot.transcribing || snapshot.failed > 0

    /** Toque no indicador: com o 2º toggle, abre a visualização; sem ele, o indicador só informa. */
    fun onBadgeTap(viewEdit: Boolean): PartialUiMode = if (viewEdit) PartialUiMode.VIEWER else PartialUiMode.HIDDEN

    /** Texto do indicador: discreto, diz quanto já tem e se algo está pendente. */
    fun badgeLabel(snapshot: PartialSnapshot): String {
        val words = snapshot.text.split(Regex("\\s+")).count { it.isNotBlank() }
        val parts = mutableListOf("parcial")
        when {
            words > 0 -> parts += if (words == 1) "1 palavra" else "$words palavras"
            snapshot.transcribing -> parts += "transcrevendo…"
        }
        if (words > 0 && snapshot.transcribing) parts += "…"
        if (snapshot.failed > 0) parts += if (snapshot.failed == 1) "1 trecho a refazer" else "${snapshot.failed} trechos a refazer"
        return parts.joinToString(" · ")
    }
}
