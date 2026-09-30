package com.keyflow.auth.oauth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Base64 "URL-safe" sem preenchimento (RFC 4648 §5) — o formato do PKCE e dos tokens. Feito à mão: `java.util.Base64` só existe
 * a partir da API 26 (o `minSdk` é 24) e `android.util.Base64` não roda nos testes unitários da JVM.
 */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) out.append(ALPHABET[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) out.append(ALPHABET[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val clean = text.trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "caractere fora do base64url" }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}

/** PKCE (RFC 7636, método S256) e os valores aleatórios de cada tentativa de login (AUTH-08). */
object Pkce {
    private val random = SecureRandom()

    /** 64 bytes aleatórios em base64url = 86 caracteres, dentro dos 43–128 do RFC, só com caracteres não reservados. */
    fun newVerifier(): String = randomToken(64)

    /** `base64url(SHA-256(verifier))`, sem preenchimento. */
    fun challengeS256(verifier: String): String =
        Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Valor aleatório imprevisível para `state` e `nonce` (32 bytes por padrão). */
    fun randomToken(bytes: Int = 32): String = Base64Url.encode(ByteArray(bytes).also(random::nextBytes))
}
