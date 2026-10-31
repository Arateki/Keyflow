package com.keyflow.computador

/** Base64 padrão (RFC 4648) em Kotlin puro: `java.util.Base64` só existe da API 26 e `android.util.Base64` não roda na JVM dos testes. */
object Base64Puro {
    private const val ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val inverso = IntArray(128) { -1 }.also { t -> ALFABETO.forEachIndexed { i, c -> t[c.code] = i } }

    fun codificar(dados: ByteArray, comPadding: Boolean = true): String {
        val sb = StringBuilder((dados.size + 2) / 3 * 4)
        var i = 0
        while (i < dados.size) {
            val b0 = dados[i].toInt() and 0xff
            val b1 = if (i + 1 < dados.size) dados[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < dados.size) dados[i + 2].toInt() and 0xff else -1
            sb.append(ALFABETO[b0 shr 2])
            sb.append(ALFABETO[((b0 and 3) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) sb.append(ALFABETO[((b1 and 15) shl 2) or (if (b2 < 0) 0 else b2 shr 6)]) else if (comPadding) sb.append('=')
            if (b2 >= 0) sb.append(ALFABETO[b2 and 63]) else if (comPadding) sb.append('=')
            i += 3
        }
        return sb.toString()
    }

    /** `null` para texto inválido (caractere fora do alfabeto, padding no meio, tamanho impossível). Aceita com ou sem padding. */
    fun decodificar(texto: String): ByteArray? {
        val t = texto.trimEnd('=')
        if (texto.length - t.length > 2 || t.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(t.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in t) {
            val v = if (c.code < 128) inverso[c.code] else -1
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((acc shr bits) and 0xff) }
        }
        return out.toByteArray()
    }
}
