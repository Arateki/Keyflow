package com.keyflow.computador.ssh

import kotlinx.coroutines.delay

/**
 * Detecta servidor mudo (CMP-22). O cbssh só envia `SSH_MSG_IGNORE` como keepalive, que não prova que o servidor responde; então a cada
 * [intervaloMs] o vigia pede uma sondagem de verdade. [sondar] devolve `true` (respondeu), `false` (falhou ou não respondeu) ou `null`
 * (o servidor não suporta ping: não há como medir, e o vigia não derruba nada). [maxFalhas] falhas seguidas = perdida.
 */
class Vigia(private val intervaloMs: Long = 30_000, private val maxFalhas: Int = 3, private val sondar: suspend () -> Boolean?) {
    /** Roda até declarar a conexão perdida (retorna) ou ser cancelado. */
    suspend fun vigiar() {
        var falhas = 0
        while (true) {
            delay(intervaloMs)
            when (sondar()) {
                true -> falhas = 0
                false -> if (++falhas >= maxFalhas) return
                null -> Unit
            }
        }
    }
}
