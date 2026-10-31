package com.keyflow.computador.sessao

import com.keyflow.computador.ssh.CausaSsh

/**
 * A política de reconexão (CMP-22): espera 1·2·4·8·16·30 s (e 30 s dali em diante), no máximo [MAX_TENTATIVAS] tentativas ou [MAX_TOTAL_MS]
 * desde a primeira queda. Pura: quem mede o tempo é a sessão, com relógio injetável.
 */
object Reconexao {
    const val MAX_TENTATIVAS = 10
    const val MAX_TOTAL_MS = 10 * 60_000L
    private val ESPERAS_S = longArrayOf(1, 2, 4, 8, 16, 30)

    /** Espera antes da tentativa [tentativa] (1-based), ou `null` se acabaram as tentativas ou o tempo. [decorridoMs] conta desde a primeira queda. */
    fun espera(tentativa: Int, decorridoMs: Long): Long? {
        if (tentativa < 1 || tentativa > MAX_TENTATIVAS) return null
        val ms = ESPERAS_S[minOf(tentativa - 1, ESPERAS_S.size - 1)] * 1000
        return if (decorridoMs + ms > MAX_TOTAL_MS) null else ms
    }

    /** Só vale reconectar quando a queda foi da rede ou do servidor; recusa de credencial, de servidor ou de biometria não se repete sozinha. */
    fun reconectavel(causa: CausaSsh): Boolean = causa in setOf(CausaSsh.REDE, CausaSsh.TEMPO, CausaSsh.RECUSADA, CausaSsh.DESCONECTADO, CausaSsh.DNS)
}
