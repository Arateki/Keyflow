package com.keyflow.computador

import java.io.File
import java.util.Base64

/** Auxiliares dos testes: chaves FALSAS de `src/test/resources/computador/chaves` e a saída de referência do `ssh-keygen`. */
object TesteSsh {
    const val FRASE = "frase-falsa-123"

    fun chave(nome: String): String = File("src/test/resources/computador/chaves/$nome").readText()

    /** A linha do `.pub` de referência: (algoritmo, blob). */
    fun publica(nome: String): Pair<String, ByteArray> {
        val (alg, b64) = chave("$nome.pub").trim().split(" ")
        return alg to Base64.getDecoder().decode(b64)
    }

    fun servidor(nome: String) = publica(nome).let { (a, b) -> ChaveDoServidor(a, b) }
}
