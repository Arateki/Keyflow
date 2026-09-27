package com.keyflow.data.models

import java.io.File
import java.security.MessageDigest

/** SHA256 em streaming (DLM-02): lê em blocos, nunca o arquivo inteiro na memória (modelos de até 640 MB). */
object Sha256 {

    private const val BUFFER_BYTES = 1 shl 20

    /** Hex minúsculo, como no catálogo. */
    fun hexOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
