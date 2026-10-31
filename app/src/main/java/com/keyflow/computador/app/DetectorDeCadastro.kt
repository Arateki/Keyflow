package com.keyflow.computador.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Detecta que a biometria cadastrada mudou (CMP-14). Nas APIs 24–29 uma chave com duração positiva (os 300 s) fica presa ao root SID e o
 * `setInvalidatedByBiometricEnrollment(true)` **não** garante a invalidação ao cadastrar outra digital (AOSP `KeymasterUtils`, ramo de
 * duração positiva). Então, nessas APIs, cada chave com biometria ganha uma **chave sentinela**: AES que exige autenticação a cada uso
 * (duração -1), invalidada por cadastro novo. Só inicializar a cifra dela basta: `KeyPermanentlyInvalidatedException` = o cadastro mudou.
 * Na API 30+ (`AUTH_BIOMETRIC_STRONG`) a própria chave já é invalidada, e este detector não faz nada.
 */
interface DetectorDeCadastro {
    /** Cria a sentinela da chave [id] (no-op onde não é preciso). */
    fun criar(id: String)

    /** `true` se o cadastro biométrico mudou depois da criação da chave [id]. */
    fun invalidada(id: String): Boolean

    fun apagar(id: String)
}

/** O Keystore por trás da sentinela; separado para o teste simular "criada na API 29, usada na 30+" sem aparelho com biometria. */
interface SentinelaKeystore {
    fun existe(id: String): Boolean
    fun criar(id: String)

    /** `true` se a cifra da sentinela não inicializa por `KeyPermanentlyInvalidatedException`. */
    fun invalidada(id: String): Boolean
    fun apagar(id: String)
}

/**
 * Sentinelas **novas** só nascem na API ≤ 29; as que **já existem** são consultadas em qualquer API. Depois de um OTA de 29 para 30+, a
 * chave antiga continua com a janela de 300 s presa ao root SID e só a sentinela sabe que o cadastro mudou.
 */
class DetectorDeCadastroKeystore(
    private val api: Int = Build.VERSION.SDK_INT,
    private val sentinelas: SentinelaKeystore = SentinelaReal,
) : DetectorDeCadastro {
    override fun criar(id: String) { if (api < 30) sentinelas.criar(id) }

    override fun invalidada(id: String): Boolean = sentinelas.existe(id) && sentinelas.invalidada(id)

    override fun apagar(id: String) = sentinelas.apagar(id)
}

object SentinelaReal : SentinelaKeystore {
    private fun alias(id: String) = "keyflow_ssh_sent_$id"
    private fun ks(): KeyStore = KeyStore.getInstance(EnvelopeDeChave.PROVEDOR).apply { load(null) }

    override fun existe(id: String): Boolean = try { ks().containsAlias(alias(id)) } catch (e: Exception) { false }

    override fun criar(id: String) {
        val spec = KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        @Suppress("DEPRECATION") spec.setUserAuthenticationValidityDurationSeconds(-1)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, EnvelopeDeChave.PROVEDOR).apply { init(spec.build()) }.generateKey()
    }

    override fun invalidada(id: String): Boolean = try {
        val k = ks().getKey(alias(id), null) as? SecretKey ?: return false
        Cipher.getInstance("AES/CBC/PKCS7Padding").init(Cipher.ENCRYPT_MODE, k)
        false
    } catch (e: KeyPermanentlyInvalidatedException) {
        true
    } catch (e: Exception) {
        false
    }

    override fun apagar(id: String) { runCatching { ks().deleteEntry(alias(id)) } }
}
