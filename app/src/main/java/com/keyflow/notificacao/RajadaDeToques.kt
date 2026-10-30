package com.keyflow.notificacao

/**
 * Conta os toques da orbe na notificação numa janela (NTF-09, design §5). O relógio é o do serviço (injetável, para a JVM): cada
 * [toque] reinicia a janela; a rajada **assenta** quando passou [janelaMs] desde o último toque, e [fechar] devolve o número de
 * toques e zera — então uma rajada fecha uma única vez.
 */
class RajadaDeToques(
    private val relogio: () -> Long,
    private val janelaMs: () -> Long,
) {
    var contagem: Int = 0
        private set
    private var ultimoMs: Long = 0L

    /** Registra um toque e reinicia a janela. Devolve a contagem até agora. */
    fun toque(): Int {
        contagem += 1
        ultimoMs = relogio()
        return contagem
    }

    /** Ms que faltam para a rajada assentar, ou `null` se não há toque pendente. */
    fun faltaMs(): Long? =
        if (contagem == 0) null else (janelaMs() - (relogio() - ultimoMs)).coerceAtLeast(0L)

    /** A janela desde o último toque já passou. */
    fun assentou(): Boolean = faltaMs() == 0L

    /** Devolve o número de toques da rajada e zera (0 se não havia nenhum): chamadas seguintes devolvem 0. */
    fun fechar(): Int {
        val n = contagem
        contagem = 0
        return n
    }
}
