package com.keyflow.computador.chaves

import com.keyflow.computador.TesteSsh
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChavePublicaTest {
    @Test fun ed25519BateComOSshKeygen() {
        val (alg, blob) = TesteSsh.publica("ed25519")
        val linha = FormatadorChavePublica.linha(blob, "Galaxy A32")
        assertEquals(TesteSsh.chave("ed25519.pub").trim().split(" ").take(2).joinToString(" ") + " keyflow@Galaxy-A32", linha)
        assertEquals("ssh-ed25519", alg)
    }

    @Test fun blobEd25519ConstruidoPorNosBateComOSshKeygen() {
        val (_, blob) = TesteSsh.publica("ed25519")
        // Os últimos 32 bytes são a chave; remontar o blob dá o mesmo.
        assertArrayEquals(blob, FormatadorChavePublica.blobEd25519(blob.copyOfRange(blob.size - 32, blob.size)))
    }

    @Test fun blobP256ConstruidoPorNosBateComOSshKeygen() {
        val (alg, blob) = TesteSsh.publica("ecdsa-p256")
        assertEquals("ecdsa-sha2-nistp256", alg)
        assertArrayEquals(blob, FormatadorChavePublica.blobEcdsaP256(blob.copyOfRange(blob.size - 65, blob.size)))
        assertEquals("ecdsa-sha2-nistp256", FormatadorChavePublica.linha(blob, "x").substringBefore(' '))
    }

    @Test fun comentarioNaoTemEspacoNemControle() {
        val (_, blob) = TesteSsh.publica("ed25519")
        val c = FormatadorChavePublica.linha(blob, "Meu  Tablet\n; rm -rf").substringAfterLast(' ')
        assertEquals("keyflow@Meu--Tablet-;-rm--rf", c)
        assertEquals("keyflow@aparelho", FormatadorChavePublica.linha(blob, "   ").substringAfterLast(' '))
        assertEquals(64 + "keyflow@".length, FormatadorChavePublica.linha(blob, "x".repeat(200)).substringAfterLast(' ').length)
    }

    @Test fun blobTruncadoNaoLanca() {
        assertNull(FormatadorChavePublica.tipoDoBlob(byteArrayOf(0, 0)))
        assertNull(FormatadorChavePublica.tipoDoBlob(byteArrayOf(0, 0, 0, 50, 1)))
    }

    @Test fun rsaOferecidaComoRsaSha2MasLinhaDizSshRsa() {
        val chave = (ImportadorDeChave().importar(TesteSsh.chave("rsa3072").toCharArray(), null) as Resultado.Ok).valor
        assertEquals("rsa-sha2-256", chave.publica.algoritmo)
        assertEquals("ssh-rsa", FormatadorChavePublica.linha(chave.publica, "x").substringBefore(' '))
    }
}
