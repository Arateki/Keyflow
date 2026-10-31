package com.keyflow.computador.chaves

import com.keyflow.computador.ImpressaoDigital
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.interfaces.RSAPrivateKey
import org.connectbot.sshlib.SshKeys
import org.connectbot.sshlib.SshSigning

/** O que sai de uma importação: a pública, o tipo e o segredo (que o chamador entrega ao envelope e zera). */
class ChaveImportada(
    val tipo: TipoDeChave,
    val bits: Int,
    val publica: ChavePublicaSsh,
    val material: MaterialPrivado,
) {
    val impressao: ImpressaoDigital get() = publica.impressao
    val rotulo: String get() = "${tipo.rotuloBase} $bits"
    override fun toString() = "ChaveImportada($tipo, $bits, ***)"
}

/**
 * Importa chave privada em OpenSSH ou PEM (PKCS#1, SEC1, PKCS#8) pelo `SshKeys` do cbssh (CMP-12).
 *
 * Sem estado: **não tem campo algum**, então o texto colado e a passphrase não ficam aqui depois de [importar]. Os `CharArray`
 * recebidos são zerados ao fim (mesmo em erro). Limite honesto: o cbssh só lê `String`, então uma cópia imutável existe por instantes
 * na pilha da biblioteca até o coletor levá-la; o app não a guarda nem a repassa.
 *
 * Recusa DSA e RSA < 2048 com o motivo; passphrase errada é [ErroChave.PASSPHRASE], ausente é [ErroChave.PASSPHRASE_NECESSARIA].
 */
class ImportadorDeChave {
    fun importar(pem: CharArray, passphrase: CharArray?): Resultado<ChaveImportada> {
        try {
            val texto = String(pem).trim()
            val exigeSenha = when (val d = inspecionar(texto)) {
                is Inspecao.Recusa -> return Resultado.Falha(d.erro)
                is Inspecao.Ok -> d.cifrada
            }
            val senha = passphrase?.takeIf { it.isNotEmpty() }?.let { String(it) }
            if (exigeSenha && senha == null) return Resultado.Falha(ErroChave.PASSPHRASE_NECESSARIA)
            val par = try {
                SshKeys.decodePemPrivateKey(texto, if (exigeSenha) senha else null)
            } catch (e: Exception) {
                // A mensagem da biblioteca nunca é repassada: ela pode citar trechos do que foi lido.
                return Resultado.Falha(if (exigeSenha) ErroChave.PASSPHRASE else ErroChave.FORMATO)
            }
            return classificar(par)
        } finally {
            pem.fill('\u0000')
            passphrase?.fill('\u0000')
        }
    }

    private fun classificar(par: KeyPair): Resultado<ChaveImportada> {
        val pub = try {
            SshSigning.encodePublicKey(par)
        } catch (e: Exception) {
            return Resultado.Falha(ErroChave.TIPO_NAO_SUPORTADO)
        }
        val (tipo, bits) = when (pub.algorithmName) {
            "ssh-ed25519" -> TipoDeChave.ED25519 to 256
            "ecdsa-sha2-nistp256" -> TipoDeChave.ECDSA_P256 to 256
            "ecdsa-sha2-nistp384" -> TipoDeChave.ECDSA_P384 to 384
            "ecdsa-sha2-nistp521" -> TipoDeChave.ECDSA_P521 to 521
            "ssh-rsa" -> {
                val bitsRsa = (par.private as? RSAPrivateKey)?.modulus?.bitLength() ?: return Resultado.Falha(ErroChave.TIPO_NAO_SUPORTADO)
                if (bitsRsa < 2048) return Resultado.Falha(ErroChave.RSA_FRACA)
                TipoDeChave.RSA to bitsRsa
            }
            else -> return Resultado.Falha(ErroChave.TIPO_NAO_SUPORTADO)
        }
        val material = try {
            if (tipo == TipoDeChave.ED25519) {
                MaterialPrivado(FormatoDoMaterial.SEMENTE_ED25519, sementeDeEd25519(par.private.encoded))
            } else {
                MaterialPrivado(FormatoDoMaterial.OPENSSH_SEM_SENHA, SshKeys.encodeOpenSshPrivateKey(par, null).toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            return Resultado.Falha(ErroChave.TIPO_NAO_SUPORTADO)
        }
        val algoritmo = if (tipo == TipoDeChave.RSA) "rsa-sha2-256" else tipo.algoritmoSsh
        return Resultado.Ok(ChaveImportada(tipo, bits, ChavePublicaSsh(algoritmo, pub.publicKeyBlob), material))
    }

    /** PKCS#8 de Ed25519 termina em `OCTET STRING(32)` com a semente (RFC 8410): os últimos 32 bytes. */
    private fun sementeDeEd25519(pkcs8: ByteArray): ByteArray {
        require(pkcs8.size >= 34 && pkcs8[pkcs8.size - 34] == 0x04.toByte() && pkcs8[pkcs8.size - 33] == 0x20.toByte()) { "PKCS#8 Ed25519 inesperado" }
        return pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size)
    }

    private sealed interface Inspecao {
        data class Ok(val cifrada: Boolean) : Inspecao
        data class Recusa(val erro: ErroChave) : Inspecao
    }

    /** Olha o cabeçalho (sem decifrar nada) para saber se pede senha e recusar DSA antes de qualquer cálculo. */
    private fun inspecionar(texto: String): Inspecao {
        return when {
            texto.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") -> inspecionarOpenSsh(texto)
            texto.startsWith("-----BEGIN DSA PRIVATE KEY-----") -> Inspecao.Recusa(ErroChave.DSA_RECUSADA)
            texto.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----") -> Inspecao.Ok(true)
            texto.startsWith("-----BEGIN RSA PRIVATE KEY-----") || texto.startsWith("-----BEGIN EC PRIVATE KEY-----") ->
                Inspecao.Ok(texto.lineSequence().any { it.trim().startsWith("Proc-Type:") && it.contains("ENCRYPTED") })
            texto.startsWith("-----BEGIN PRIVATE KEY-----") -> Inspecao.Ok(false)
            else -> Inspecao.Recusa(ErroChave.FORMATO)
        }
    }

    private fun inspecionarOpenSsh(texto: String): Inspecao {
        val corpo = texto.lineSequence().map { it.trim() }.dropWhile { !it.startsWith("-----BEGIN") }.drop(1).takeWhile { !it.startsWith("-----END") }.joinToString("")
        val dados = com.keyflow.computador.Base64Puro.decodificar(corpo) ?: return Inspecao.Recusa(ErroChave.FORMATO)
        val magico = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (dados.size < magico.size || !dados.copyOfRange(0, magico.size).contentEquals(magico)) return Inspecao.Recusa(ErroChave.FORMATO)
        return try {
            val b = ByteBuffer.wrap(dados, magico.size, dados.size - magico.size)
            val cifra = string(b)
            string(b) // kdfname
            string(b) // kdfoptions
            if (b.int != 1) return Inspecao.Recusa(ErroChave.FORMATO)
            val pub = ByteBuffer.wrap(string(b))
            // A pública vem em claro mesmo com a privada cifrada: dá para recusar DSA antes de pedir a senha.
            if (String(string(pub), Charsets.US_ASCII) == "ssh-dss") Inspecao.Recusa(ErroChave.DSA_RECUSADA) else Inspecao.Ok(String(cifra, Charsets.US_ASCII) != "none")
        } catch (e: RuntimeException) {
            Inspecao.Recusa(ErroChave.FORMATO)
        }
    }

    private fun string(b: ByteBuffer): ByteArray {
        val n = b.int
        require(n in 0..b.remaining())
        return ByteArray(n).also { b.get(it) }
    }
}
