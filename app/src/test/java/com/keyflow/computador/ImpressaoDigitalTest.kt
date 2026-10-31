package com.keyflow.computador

import org.junit.Assert.assertEquals
import org.junit.Test

/** Vetores gerados com o `ssh-keygen -lf`/`-lvf` do OpenSSH 9.2 sobre chaves falsas do teste. */
class ImpressaoDigitalTest {
    @Test fun ed25519BateComOSshKeygen() =
        assertEquals("SHA256:MIkL9HeXdvsn5K5gk/LLEBqe1g+glboOZOU9o8wkcqg", ImpressaoDigital.de(vetorEd25519Blob()).texto)

    @Test fun artePadraoBateComOSshKeygen() {
        val esperado = """
            +--[ED25519 256]--+
            |  .              |
            | . . . .   .     |
            |  . + = . + .    |
            | . + + = o . .   |
            |o = + O S   . .  |
            |.= = * O . . +   |
            |E . * = = =   + .|
            |   . o   O o . o |
            |   .o     =....  |
            +----[SHA256]-----+
        """.trimIndent()
        assertEquals(esperado, ImpressaoDigital.de(vetorEd25519Blob()).arte("ED25519 256"))
    }

    @Test fun textoSemPaddingBase64() {
        // 32 bytes em base64 teriam um "=" no fim: a impressão do OpenSSH não tem.
        assert(!ImpressaoDigital.de(byteArrayOf(1, 2, 3)).texto.endsWith("="))
    }

    private fun vetorEd25519Blob(): ByteArray =
        java.util.Base64.getDecoder().decode("AAAAC3NzaC1lZDI1NTE5AAAAIFAFEx8CsWZqAMLFNvILkPP0zn36avMMMlEzsf3NYi4T")
}
