package com.keyflow.computador.chaves

import java.nio.ByteBuffer
import java.util.Base64

/** Escreve a chave pública como o `authorized_keys`: `ssh-ed25519 AAAA… keyflow@aparelho` (CMP-13). */
object FormatadorChavePublica {
    fun linha(publica: ChavePublicaSsh, aparelho: String): String = linha(publica.blob, aparelho)

    fun linha(blob: ByteArray, aparelho: String): String {
        val tipo = tipoDoBlob(blob) ?: error("blob de chave pública inválido")
        return "$tipo ${Base64.getEncoder().encodeToString(blob)} keyflow@${comentarioSeguro(aparelho)}"
    }

    /** O nome do aparelho vira comentário: sem espaço, sem controle e com tamanho limitado. */
    fun comentarioSeguro(aparelho: String): String =
        aparelho.trim().map { if (it.isWhitespace() || it.isISOControl()) '-' else it }.joinToString("").take(64).ifEmpty { "aparelho" }

    /** O primeiro campo `string` do blob é o nome do tipo. `null` se o blob está truncado. */
    fun tipoDoBlob(blob: ByteArray): String? = try {
        val b = ByteBuffer.wrap(blob)
        val n = b.int
        if (n < 1 || n > b.remaining()) null else ByteArray(n).also { b.get(it) }.toString(Charsets.US_ASCII)
    } catch (e: java.nio.BufferUnderflowException) {
        null
    }

    // Construtores do formato de fio (RFC 4251 §5).
    internal fun campo(bytes: ByteArray): ByteArray = ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()
    internal fun campo(texto: String): ByteArray = campo(texto.toByteArray(Charsets.US_ASCII))

    /** `mpint` de um inteiro positivo: bytes mínimos, com 0x00 na frente se o bit alto estiver ligado. */
    internal fun mpint(positivo: java.math.BigInteger): ByteArray = campo(positivo.toByteArray())

    fun blobEd25519(publica32: ByteArray): ByteArray {
        require(publica32.size == 32) { "chave Ed25519 pública tem 32 bytes" }
        return campo("ssh-ed25519") + campo(publica32)
    }

    /** [ponto] é o ponto não comprimido (0x04 || X || Y), 65 bytes para P-256. */
    fun blobEcdsaP256(ponto: ByteArray): ByteArray {
        require(ponto.size == 65 && ponto[0] == 4.toByte()) { "ponto P-256 não comprimido tem 65 bytes" }
        return campo("ecdsa-sha2-nistp256") + campo("nistp256") + campo(ponto)
    }
}
