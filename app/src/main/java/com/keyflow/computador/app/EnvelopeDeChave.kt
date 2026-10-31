package com.keyflow.computador.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.keyflow.computador.chaves.CofreDeSegredos
import com.keyflow.computador.chaves.FormatoDoMaterial
import com.keyflow.computador.chaves.MaterialPrivado
import com.keyflow.computador.chaves.PlanoDeAutenticacao
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** O envelope não conseguiu devolver o segredo; [invalidada] distingue "a biometria mudou" de "não está mais aqui". */
class EnvelopeIndisponivel(val invalidada: Boolean, causa: Throwable? = null) : Exception(if (invalidada) "chave invalidada" else "envelope indisponível", causa)

/**
 * O envelope (CMP-11): o segredo (semente Ed25519 ou OpenSSH sem senha) é cifrado com **AES-256-GCM de uma chave do Android Keystore**
 * (API 24+, alias `keyflow_ssh_env_<id>`, nunca exportável) e o texto cifrado vai ao cofre do app em `ssh_key.<id>`. Em claro, o segredo só
 * existe num array durante a assinatura (quem o recebe o zera). O id entra como dado autenticado (AAD): um texto cifrado de uma chave não
 * abre sob o alias de outra. Com biometria, a chave AES exige autenticação com a **mesma janela de 300 s** (D14), sem `CryptoObject`.
 */
class EnvelopeDeChave(
    private val cofre: CofreDeSegredos,
    private val api: Int = Build.VERSION.SDK_INT,
    private val aplicarBiometriaNoKeystore: Boolean = true,
) {
    private val ks: KeyStore get() = KeyStore.getInstance(PROVEDOR).apply { load(null) }

    fun aliasDe(id: String) = "$PREFIXO_ALIAS$id"
    fun chaveDoCofre(id: String) = "$PREFIXO_COFRE$id"

    /** Guarda [material] cifrado. **Não** zera o array recebido: quem o gerou decide (o importador e o provedor o zeram). */
    fun cifrar(id: String, material: MaterialPrivado, exigeBiometria: Boolean) {
        val alias = aliasDe(id)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // IV aleatório pelo próprio Keystore (o padrão exige): o IV vai junto do texto cifrado.
            .setRandomizedEncryptionRequired(true)
        if (exigeBiometria && aplicarBiometriaNoKeystore) AutenticacaoKeystore.aplicar(spec, api)
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEDOR)
        gerador.init(spec.build())
        val chave: SecretKey = gerador.generateKey()
        try {
            val cifra = Cipher.getInstance(TRANSFORMACAO)
            cifra.init(Cipher.ENCRYPT_MODE, chave)
            cifra.updateAAD(aad(id))
            val cifrado = cifra.doFinal(material.bytes)
            val pacote = byteArrayOf(VERSAO, codigo(material.formato)) + byteArrayOf(cifra.iv.size.toByte()) + cifra.iv + cifrado
            cofre.gravar(chaveDoCofre(id), Base64.encodeToString(pacote, Base64.NO_WRAP))
        } catch (e: Exception) {
            apagar(id)
            throw e
        }
    }

    /** Decifra. Lança [EnvelopeIndisponivel] se a chave foi invalidada ou sumiu; deixa passar `UserNotAuthenticatedException` (o chamador pede a biometria). */
    fun decifrar(id: String): MaterialPrivado {
        val texto = cofre.ler(chaveDoCofre(id)) ?: throw EnvelopeIndisponivel(false)
        val pacote = try { Base64.decode(texto, Base64.NO_WRAP) } catch (e: IllegalArgumentException) { throw EnvelopeIndisponivel(false, e) }
        if (pacote.size < 4 || pacote[0] != VERSAO) throw EnvelopeIndisponivel(false)
        val formato = formato(pacote[1]) ?: throw EnvelopeIndisponivel(false)
        val ivLen = pacote[2].toInt() and 0xff
        if (pacote.size < 3 + ivLen + 16) throw EnvelopeIndisponivel(false)
        val iv = pacote.copyOfRange(3, 3 + ivLen)
        val cifrado = pacote.copyOfRange(3 + ivLen, pacote.size)
        val chave = ks.getKey(aliasDe(id), null) as? SecretKey ?: throw EnvelopeIndisponivel(false)
        return try {
            val cifra = Cipher.getInstance(TRANSFORMACAO)
            cifra.init(Cipher.DECRYPT_MODE, chave, GCMParameterSpec(128, iv))
            cifra.updateAAD(aad(id))
            MaterialPrivado(formato, cifra.doFinal(cifrado))
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw EnvelopeIndisponivel(true, e)
        } catch (e: java.security.GeneralSecurityException) {
            if (e is android.security.keystore.UserNotAuthenticatedException) throw e
            throw EnvelopeIndisponivel(false, e)
        }
    }

    /** Teste barato de "a chave de envelope foi invalidada?" — só inicializa a cifra, sem decifrar nada. */
    fun invalidada(id: String): Boolean {
        val chave = ks.getKey(aliasDe(id), null) as? SecretKey ?: return false
        return try {
            Cipher.getInstance(TRANSFORMACAO).init(Cipher.DECRYPT_MODE, chave, GCMParameterSpec(128, ByteArray(12)))
            false
        } catch (e: KeyPermanentlyInvalidatedException) {
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Apaga o alias do Keystore e o texto cifrado do cofre. */
    fun apagar(id: String) {
        runCatching { ks.deleteEntry(aliasDe(id)) }
        cofre.apagar(chaveDoCofre(id))
    }

    private fun aad(id: String) = "keyflow-ssh:$id".toByteArray(Charsets.UTF_8)

    private fun codigo(f: FormatoDoMaterial): Byte = when (f) { FormatoDoMaterial.SEMENTE_ED25519 -> 1; FormatoDoMaterial.OPENSSH_SEM_SENHA -> 2 }
    private fun formato(b: Byte): FormatoDoMaterial? = when (b.toInt()) { 1 -> FormatoDoMaterial.SEMENTE_ED25519; 2 -> FormatoDoMaterial.OPENSSH_SEM_SENHA; else -> null }

    companion object {
        const val PROVEDOR = "AndroidKeyStore"
        const val PREFIXO_ALIAS = "keyflow_ssh_env_"
        const val PREFIXO_COFRE = "ssh_key."
        private const val TRANSFORMACAO = "AES/GCM/NoPadding"
        private const val VERSAO: Byte = 1
    }
}

/** Aplica a janela de 300 s ao `KeyGenParameterSpec` conforme a API (D14). Fica aqui porque é o que toca o `android.security.keystore`. */
internal object AutenticacaoKeystore {
    fun aplicar(spec: KeyGenParameterSpec.Builder, api: Int) {
        spec.setUserAuthenticationRequired(true)
        when (val plano = PlanoDeAutenticacao.para(api)) {
            is PlanoDeAutenticacao.Parametros ->
                // O `api` recebido é o do plano; a guarda de SDK é a que o lint e o aparelho enxergam.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) spec.setUserAuthenticationParameters(plano.segundos, KeyProperties.AUTH_BIOMETRIC_STRONG)
                else @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(plano.segundos)
            is PlanoDeAutenticacao.Validade -> @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(plano.segundos)
        }
        // Com aviso antes de ligar (a tela): trocar a biometria cadastrada invalida a chave.
        spec.setInvalidatedByBiometricEnrollment(true)
    }
}
