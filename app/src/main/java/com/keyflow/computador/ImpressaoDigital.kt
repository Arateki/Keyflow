package com.keyflow.computador

import java.security.MessageDigest

/** Uma chave pública de servidor: nome do algoritmo SSH (`ssh-ed25519`…) e o blob no formato de fio, como no `known_hosts`. */
class ChaveDoServidor(val algoritmo: String, val blob: ByteArray) {
    val impressao: ImpressaoDigital get() = ImpressaoDigital.de(blob)

    override fun equals(other: Any?) = other is ChaveDoServidor && algoritmo == other.algoritmo && blob.contentEquals(other.blob)
    override fun hashCode() = 31 * algoritmo.hashCode() + blob.contentHashCode()
    override fun toString() = "ChaveDoServidor($algoritmo, ${impressao.texto})"
}

/** Impressão digital como o OpenSSH mostra: `SHA256:` + base64 sem padding do SHA-256 do blob (`ssh-keygen -lf`). */
class ImpressaoDigital private constructor(val sha256: ByteArray) {
    val texto: String get() = "SHA256:" + Base64Puro.codificar(sha256, comPadding = false)

    /**
     * A "arte aleatória" (`ssh-keygen -lv`, algoritmo do bispo bêbado de 17×9) para o usuário comparar de relance.
     * [rotulo] é o cabeçalho, por exemplo `ED25519 256`.
     */
    fun arte(rotulo: String): String {
        val larg = 17
        val alt = 9
        val simbolos = " .o+=*BOX@%&#/^SE"
        val campo = Array(alt) { IntArray(larg) }
        var x = larg / 2
        var y = alt / 2
        for (b in sha256) {
            var v = b.toInt() and 0xff
            repeat(4) {
                x += if (v and 1 != 0) 1 else -1
                y += if (v and 2 != 0) 1 else -1
                x = x.coerceIn(0, larg - 1)
                y = y.coerceIn(0, alt - 1)
                if (campo[y][x] < simbolos.length - 3) campo[y][x]++
                v = v shr 2
            }
        }
        campo[alt / 2][larg / 2] = simbolos.length - 2 // S
        campo[y][x] = simbolos.length - 1 // E
        val sb = StringBuilder()
        sb.append(moldura("[$rotulo]", larg)).append('\n')
        for (linha in campo) {
            sb.append('|')
            for (c in linha) sb.append(simbolos[c])
            sb.append("|\n")
        }
        sb.append(moldura("[SHA256]", larg))
        return sb.toString()
    }

    private fun moldura(titulo: String, larg: Int): String {
        val t = titulo.take(larg)
        val esq = (larg - t.length) / 2
        return "+" + "-".repeat(esq) + t + "-".repeat(larg - t.length - esq) + "+"
    }

    override fun equals(other: Any?) = other is ImpressaoDigital && sha256.contentEquals(other.sha256)
    override fun hashCode() = sha256.contentHashCode()
    override fun toString() = texto

    companion object {
        fun de(blob: ByteArray) = ImpressaoDigital(MessageDigest.getInstance("SHA-256").digest(blob))

        /** Lê de volta o texto `SHA256:…` (metadados de chave). `null` se não for uma impressão válida. */
        fun deTexto(texto: String): ImpressaoDigital? {
            if (!texto.startsWith("SHA256:")) return null
            val bytes = Base64Puro.decodificar(texto.removePrefix("SHA256:")) ?: return null
            return if (bytes.size == 32) ImpressaoDigital(bytes) else null
        }
    }
}
