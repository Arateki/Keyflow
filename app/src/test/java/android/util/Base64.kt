package android.util

/**
 * Base64 real só para os testes de JVM: o `android.jar` de teste devolve `null`, e o cbssh escolhe este
 * `android.util.Base64` por reflexão. Só a superfície que ele usa (CMP-T4).
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2

    @JvmStatic fun encodeToString(entrada: ByteArray, flags: Int): String {
        var codificador = if (flags and NO_WRAP != 0) java.util.Base64.getEncoder() else java.util.Base64.getMimeEncoder(76, byteArrayOf(10))
        if (flags and NO_PADDING != 0) codificador = codificador.withoutPadding()
        return codificador.encodeToString(entrada)
    }

    @JvmStatic fun decode(entrada: String, @Suppress("UNUSED_PARAMETER") flags: Int): ByteArray =
        java.util.Base64.getMimeDecoder().decode(entrada)
}
