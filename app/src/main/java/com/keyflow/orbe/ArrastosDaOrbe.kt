package com.keyflow.orbe

import com.keyflow.core.ButtonState

/** O que um arrasto faz na orbe flutuante em Alternar (design §3.1). */
enum class EfeitoDeArrasto { PAUSAR, RETOMAR, DESCARTAR, ABRIR_APP }

/**
 * A tabela dos arrastos da orbe flutuante em ALTERNAR, por estado, em forma de dados (NTF-48, D11). Ela existe para a regra "todo
 * arrasto com efeito ganha um botão na notificação" ser verificável: `BotoesDaNotificacaoTest` falha se um efeito (menos
 * [EfeitoDeArrasto.ABRIR_APP]) não produz botão, e `ArrastosDaOrbeConformidadeTest` (instrumentado) arrasta a `OrbButtonView` real
 * e compara com esta tabela.
 *
 * Fonte no código da orbe: a trava de arrasto só existe em RECORDING/PAUSED/SENDING; ↑ alterna pausa/retoma; o lado cancela; ↓ abre o
 * app; em SENDING "a única direção válida é a lateral" (`OrbButtonView`).
 */
object ArrastosDaOrbe {
    fun comEfeito(estado: ButtonState): Map<Interacao, EfeitoDeArrasto> = when (estado) {
        ButtonState.RECORDING -> mapOf(
            Interacao.ARRASTAR_CIMA to EfeitoDeArrasto.PAUSAR,
            Interacao.ARRASTAR_LADO to EfeitoDeArrasto.DESCARTAR,
            Interacao.ARRASTAR_BAIXO to EfeitoDeArrasto.ABRIR_APP,
        )
        ButtonState.PAUSED -> mapOf(
            Interacao.ARRASTAR_CIMA to EfeitoDeArrasto.RETOMAR,
            Interacao.ARRASTAR_LADO to EfeitoDeArrasto.DESCARTAR,
            Interacao.ARRASTAR_BAIXO to EfeitoDeArrasto.ABRIR_APP,
        )
        ButtonState.SENDING -> mapOf(Interacao.ARRASTAR_LADO to EfeitoDeArrasto.DESCARTAR)
        ButtonState.IDLE_READY,
        ButtonState.IDLE_NO_FIELD,
        ButtonState.CANCELLING,
        ButtonState.ERROR,
        ButtonState.PENDING_PASTE,
        -> emptyMap()
    }
}
