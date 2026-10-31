package com.keyflow.computador.chaves

import com.google.crypto.tink.subtle.Ed25519Sign
import org.connectbot.sshlib.SshSigning

/** Gera chaves Ed25519 em software (a semente de 32 bytes, que o envelope cifra). */
object ChavesSoftware {
    class Ed25519Gerada(val publica: ChavePublicaSsh, val material: MaterialPrivado) {
        override fun toString() = "Ed25519Gerada(${publica.impressao.texto}, ***)"
    }

    fun gerarEd25519(): Ed25519Gerada {
        val par = Ed25519Sign.KeyPair.newKeyPair()
        val publica = ChavePublicaSsh("ssh-ed25519", FormatadorChavePublica.blobEd25519(par.publicKey))
        return Ed25519Gerada(publica, MaterialPrivado(FormatoDoMaterial.SEMENTE_ED25519, par.privateKey))
    }
}

/**
 * Assina com material guardado em envelope: pede o [MaterialPrivado] só na hora de assinar, usa e **zera** o array (mesmo em erro).
 * [obterMaterial] devolve `null` quando o usuário cancelou a biometria que protege o envelope (então a assinatura é `null`).
 */
class AssinadorSoftware(
    private val publica: ChavePublicaSsh,
    private val obterMaterial: suspend () -> MaterialPrivado?,
) : Assinador {
    override fun chavePublica() = publica

    override suspend fun assinar(dados: ByteArray): ByteArray? {
        val material = obterMaterial() ?: return null
        try {
            return when (material.formato) {
                FormatoDoMaterial.SEMENTE_ED25519 -> {
                    val assinatura = Ed25519Sign(material.bytes).sign(dados)
                    FormatadorChavePublica.campo("ssh-ed25519") + FormatadorChavePublica.campo(assinatura)
                }
                FormatoDoMaterial.OPENSSH_SEM_SENHA ->
                    SshSigning.sign(publica.algoritmo, String(material.bytes, Charsets.UTF_8), null, dados)
            }
        } finally {
            material.zerar()
        }
    }
}
