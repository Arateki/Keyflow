package com.keyflow.fluxos

import java.security.SecureRandom

/** Ids curtos e aleatórios. Injetáveis onde importam ao teste (`RestauradorFluxos`, a criação de fluxos na tela). */
object Ids {
    private val aleatorio = SecureRandom()

    /** 8 caracteres hexadecimais: o id de um fluxo criado ou recriado (o semeado usa `s-<builtinId>`). */
    fun fluxo(): String = hex(4)

    private fun hex(bytes: Int): String {
        val b = ByteArray(bytes).also { aleatorio.nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }
}
