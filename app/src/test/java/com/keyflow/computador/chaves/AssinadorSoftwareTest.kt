package com.keyflow.computador.chaves

import com.google.crypto.tink.subtle.Ed25519Verify
import com.keyflow.computador.TesteSsh
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.RSAPublicKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssinadorSoftwareTest {
    private val dados = "dados da autenticação".toByteArray()

    private fun campos(blob: ByteArray): List<ByteArray> {
        val b = ByteBuffer.wrap(blob)
        val out = mutableListOf<ByteArray>()
        while (b.hasRemaining()) out += ByteArray(b.int).also { b.get(it) }
        return out
    }

    private fun importada(nome: String) = (ImportadorDeChave().importar(TesteSsh.chave(nome).toCharArray(), null) as Resultado.Ok).valor

    private fun assinarCom(c: ChaveImportada): ByteArray = runBlocking {
        // Cada chamada devolve uma cópia: o assinador zera o que recebe, como o envelope fará.
        AssinadorSoftware(c.publica) { MaterialPrivado(c.material.formato, c.material.bytes.copyOf()) }.assinar(dados)!!
    }

    @Test fun ed25519VerificaComAPublicaImportada() {
        val c = importada("ed25519")
        val (alg, sig) = campos(assinarCom(c)).let { String(it[0]) to it[1] }
        assertEquals("ssh-ed25519", alg)
        Ed25519Verify(campos(c.publica.blob)[1]).verify(sig, dados) // lança se inválida
    }

    @Test fun ed25519GeradaEmSoftwareAssinaEVerifica() {
        val g = ChavesSoftware.gerarEd25519()
        assertEquals("ssh-ed25519", g.publica.tipoDoBlob)
        val sig = runBlocking { AssinadorSoftware(g.publica) { MaterialPrivado(g.material.formato, g.material.bytes.copyOf()) }.assinar(dados)!! }
        Ed25519Verify(campos(g.publica.blob)[1]).verify(campos(sig)[1], dados)
    }

    @Test fun ecdsaP256VerificaComAPublica() {
        val c = importada("ecdsa-p256")
        val (alg, sigBlob) = campos(assinarCom(c)).let { String(it[0]) to it[1] }
        assertEquals("ecdsa-sha2-nistp256", alg)
        val ponto = campos(c.publica.blob)[2]
        val x = BigInteger(1, ponto.copyOfRange(1, 33))
        val y = BigInteger(1, ponto.copyOfRange(33, 65))
        val params = (java.security.KeyPairGenerator.getInstance("EC").also { it.initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as java.security.interfaces.ECPublicKey).params
        val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        val rs = campos(sigBlob)
        val der = derDeRs(BigInteger(rs[0]), BigInteger(rs[1]))
        val v = Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(dados) }
        assertTrue(v.verify(der))
    }

    @Test fun rsaVerificaComAPublicaEUsaRsaSha2() {
        val c = importada("rsa3072")
        val campos = campos(assinarCom(c))
        assertEquals("rsa-sha2-256", String(campos[0]))
        val b = campos(c.publica.blob)
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(b[2]), BigInteger(b[1])))
        assertTrue(Signature.getInstance("SHA256withRSA").apply { initVerify(pub); update(dados) }.verify(campos[1]))
    }

    @Test fun materialEZeradoDepoisDeAssinarEmAssinaturaNormalEEmErro() {
        val c = importada("ed25519")
        val m = MaterialPrivado(c.material.formato, c.material.bytes.copyOf())
        runBlocking { AssinadorSoftware(c.publica) { m }.assinar(dados) }
        assertTrue(m.bytes.all { it == 0.toByte() })
        val ruim = MaterialPrivado(FormatoDoMaterial.OPENSSH_SEM_SENHA, "lixo".toByteArray())
        val r = runCatching { runBlocking { AssinadorSoftware(c.publica) { ruim }.assinar(dados) } }
        assertTrue(r.isFailure)
        assertTrue(ruim.bytes.all { it == 0.toByte() })
    }

    @Test fun materialNuloSignificaCancelado() {
        assertNull(runBlocking { AssinadorSoftware(importada("ed25519").publica) { null }.assinar(dados) })
    }

    private fun derDeRs(r: BigInteger, s: BigInteger): ByteArray {
        fun inteiro(v: BigInteger) = v.toByteArray().let { byteArrayOf(0x02, it.size.toByte()) + it }
        val corpo = inteiro(r) + inteiro(s)
        return byteArrayOf(0x30, corpo.size.toByte()) + corpo
    }
}
