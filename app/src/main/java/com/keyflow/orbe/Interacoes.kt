package com.keyflow.orbe

import com.keyflow.i18n.trKey

/**
 * Onde há uma orbe (D9 da feature Fluxos): a flutuante, a do teclado e a da notificação. Um gesto da orbe é sempre "em algum
 * lugar" — `Orbe(FLUTUANTE, 2)` e `Orbe(NOTIFICACAO, 2)` são gatilhos diferentes. Compartilhado com a feature `notification` e
 * com `keyboard-orb`.
 */
enum class LugarDaOrbe { FLUTUANTE, TECLADO, NOTIFICACAO }

/** Os sete tipos de interação da orbe (KBO-12, NTF-08). */
enum class Interacao(val toques: Int? = null) {
    TOQUE_1(toques = 1),
    TOQUE_2(toques = 2),
    TOQUE_3(toques = 3),
    SEGURAR,
    ARRASTAR_CIMA,
    ARRASTAR_LADO,
    ARRASTAR_BAIXO,
    ;

    companion object {
        fun deToques(toques: Int): Interacao? = when (toques) {
            1 -> TOQUE_1
            2 -> TOQUE_2
            3 -> TOQUE_3
            else -> null
        }
    }
}

/**
 * Quais interações cada lugar suporta (KBO-12, design §7):
 * - [LugarDaOrbe.FLUTUANTE]: as sete;
 * - [LugarDaOrbe.TECLADO]: as sete;
 * - [LugarDaOrbe.NOTIFICACAO]: os três toques (`TOQUE_1..3`).
 */
object SuporteDeInteracoes {
    private val TODAS: Set<Interacao> = Interacao.entries.toSet()
    private val SO_TOQUES: Set<Interacao> = setOf(Interacao.TOQUE_1, Interacao.TOQUE_2, Interacao.TOQUE_3)

    fun suportadas(lugar: LugarDaOrbe): Set<Interacao> = when (lugar) {
        LugarDaOrbe.FLUTUANTE -> TODAS
        LugarDaOrbe.TECLADO -> TODAS
        LugarDaOrbe.NOTIFICACAO -> SO_TOQUES
    }

    fun suporta(lugar: LugarDaOrbe, interacao: Interacao): Boolean =
        interacao in suportadas(lugar)

    fun naoSuportadas(lugar: LugarDaOrbe): List<Interacao> =
        Interacao.entries.filterNot { suporta(lugar, it) }

    /**
     * Por que o [lugar] não tem a [interacao] (chave de `tr()`), ou `null` se tem. A tela lista as não suportadas com este
     * texto (NTF-12).
     */
    fun motivo(lugar: LugarDaOrbe, interacao: Interacao): String? = when {
        suporta(lugar, interacao) -> null
        lugar == LugarDaOrbe.NOTIFICACAO -> trKey("Uma notificação só recebe toques")
        else -> trKey("Este lugar da orbe não tem esta interação")
    }
}
