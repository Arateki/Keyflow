package com.keyflow.computador.chaves

import com.keyflow.computador.TesteSsh
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportadorDeChaveTest {
    private val importador = ImportadorDeChave()

    private fun importar(nome: String, frase: String? = null) =
        importador.importar(TesteSsh.chave(nome).toCharArray(), frase?.toCharArray())

    private fun ok(nome: String, frase: String? = null) = (importar(nome, frase) as Resultado.Ok).valor

    private fun erro(nome: String, frase: String? = null) = (importar(nome, frase) as Resultado.Falha).erro

    /** A pública gerada bate com a do `ssh-keygen -y` (o `.pub` de referência). */
    private fun confereComSshKeygen(nome: String, tipo: TipoDeChave, frase: String? = null) {
        val chave = ok(nome, frase)
        assertEquals(tipo, chave.tipo)
        val (alg, blob) = TesteSsh.publica(nome)
        assertArrayEquals("blob da pública de $nome", blob, chave.publica.blob)
        assertEquals(alg, chave.publica.tipoDoBlob)
        val esperado = TesteSsh.chave("$nome.pub").trim().split(" ").take(2).joinToString(" ")
        assertEquals(esperado, FormatadorChavePublica.linha(chave.publica, "x").substringBeforeLast(" "))
    }

    @Test fun ed25519OpenSsh() = confereComSshKeygen("ed25519", TipoDeChave.ED25519)
    @Test fun ed25519ComFrase() = confereComSshKeygen("ed25519-frase", TipoDeChave.ED25519, TesteSsh.FRASE)
    @Test fun ed25519Pkcs8() = confereComSshKeygen("ed25519-pkcs8", TipoDeChave.ED25519)
    @Test fun ecdsaP256() = confereComSshKeygen("ecdsa-p256", TipoDeChave.ECDSA_P256)
    @Test fun ecdsaP256ComFrase() = confereComSshKeygen("ecdsa-p256-frase", TipoDeChave.ECDSA_P256, TesteSsh.FRASE)
    @Test fun ecdsaP256Sec1Pem() = confereComSshKeygen("ecdsa-p256-pem", TipoDeChave.ECDSA_P256)
    @Test fun ecdsaP384() = confereComSshKeygen("ecdsa-p384", TipoDeChave.ECDSA_P384)
    @Test fun rsa3072() = confereComSshKeygen("rsa3072", TipoDeChave.RSA)
    @Test fun rsa3072ComFrase() = confereComSshKeygen("rsa3072-frase", TipoDeChave.RSA, TesteSsh.FRASE)
    @Test fun rsa2048Pkcs1Pem() = confereComSshKeygen("rsa2048-pem", TipoDeChave.RSA)
    @Test fun rsa3072Pkcs1PemComFrase() = confereComSshKeygen("rsa3072-pem-frase", TipoDeChave.RSA, TesteSsh.FRASE)

    @Test fun rotuloEImpressaoBatemComOSshKeygen() {
        assertEquals("RSA 3072", ok("rsa3072").rotulo)
        assertEquals("SHA256:JplRazxRAtruXcAiIxVPfXhV2lHkREWz5eAk54b2ILE", ok("rsa3072").impressao.texto)
        assertEquals("ECDSA 256", ok("ecdsa-p256").rotulo)
    }

    @Test fun passphraseErradaTemErroEspecifico() {
        for (nome in listOf("ed25519-frase", "ecdsa-p256-frase", "rsa3072-frase", "rsa3072-pem-frase")) assertEquals(nome, ErroChave.PASSPHRASE, erro(nome, "errada-123"))
    }

    @Test fun faltaDePassphraseEOutroErro() {
        for (nome in listOf("ed25519-frase", "rsa3072-pem-frase")) {
            assertEquals(ErroChave.PASSPHRASE_NECESSARIA, erro(nome))
            assertEquals(ErroChave.PASSPHRASE_NECESSARIA, erro(nome, ""))
        }
    }

    @Test fun passphraseSobrandoEmChaveSemSenhaEIgnorada() = confereComSshKeygen("ed25519", TipoDeChave.ED25519, "qualquer")

    @Test fun dsaERecusada() {
        assertEquals(ErroChave.DSA_RECUSADA, erro("dsa"))
        assertEquals(ErroChave.DSA_RECUSADA, erro("dsa-pem"))
    }

    @Test fun rsa1024ERecusada() = assertEquals(ErroChave.RSA_FRACA, erro("rsa1024"))

    @Test fun lixoEFormatoDesconhecido() {
        for (t in listOf("", "   ", "isto não é chave", "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----", "-----BEGIN PRIVATE KEY-----\n!!!\n-----END PRIVATE KEY-----"))
            assertEquals(t, ErroChave.FORMATO, (importador.importar(t.toCharArray(), null) as Resultado.Falha).erro)
    }

    @Test fun chaveTruncadaNaoLanca() {
        val pem = TesteSsh.chave("ed25519")
        for (n in listOf(60, 120, 200, pem.length - 40)) importador.importar(pem.take(n).toCharArray(), null)
    }

    @Test fun textoEPassphraseSaoZeradosAoFim() {
        val pem = TesteSsh.chave("ed25519-frase").toCharArray()
        val frase = TesteSsh.FRASE.toCharArray()
        importador.importar(pem, frase)
        assertTrue(pem.all { it == '\u0000' })
        assertTrue(frase.all { it == '\u0000' })
        val pem2 = TesteSsh.chave("rsa1024").toCharArray()
        importador.importar(pem2, null) // erro: também zera
        assertTrue(pem2.all { it == '\u0000' })
    }

    @Test fun importadorNaoGuardaNadaEmCampo() {
        // Sem estado: nenhuma propriedade de instância que pudesse reter o texto colado ou a passphrase.
        assertEquals(emptyList<String>(), ImportadorDeChave::class.java.declaredFields.map { it.name }.filter { it != "\$stable" })
        val chave = ok("ed25519-frase", TesteSsh.FRASE)
        val vazou = generateSequence(chave.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .any { f -> f.isAccessible = true; f.get(chave).toString().contains(TesteSsh.FRASE) }
        assertTrue(!vazou && !chave.toString().contains(TesteSsh.FRASE))
    }

    @Test fun materialDoEd25519ESementeDe32Bytes() {
        val m = ok("ed25519").material
        assertEquals(FormatoDoMaterial.SEMENTE_ED25519, m.formato)
        assertEquals(32, m.bytes.size)
        assertEquals("MaterialPrivado(SEMENTE_ED25519, ***)", m.toString())
    }

    @Test fun materialDosOutrosTiposEOpenSshSemSenha() {
        val m = ok("ecdsa-p256").material
        assertEquals(FormatoDoMaterial.OPENSSH_SEM_SENHA, m.formato)
        assertTrue(String(m.bytes).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertEquals(Base64.getEncoder().encodeToString(TesteSsh.publica("ecdsa-p256").second), Base64.getEncoder().encodeToString(ok("ecdsa-p256").publica.blob))
    }
}
