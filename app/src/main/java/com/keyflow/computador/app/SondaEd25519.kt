package com.keyflow.computador.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.subtle.Ed25519Verify
import com.keyflow.computador.chaves.AvaliadorDaSonda
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Uma chave temporária do Keystore a sondar: pública em SPKI, uma assinatura de teste e a limpeza. */
interface CandidataEd25519 {
    /** SPKI X.509 da pública que o Keystore devolveu. */
    val spki: ByteArray

    /** Assina [dados] com o algoritmo de `Signature` que o provedor aceitou. */
    fun assinar(dados: ByteArray): ByteArray

    fun apagar()
}

/** De onde vem a candidata. O de produção usa o Keystore; o teste injeta uma que devolve P-256 (a armadilha relatada por terceiros). */
fun interface GeradorEd25519Keystore {
    fun gerar(alias: String): CandidataEd25519
}

/** O gerador de produção: `KeyPairGenerator` **EC** com a curva `ed25519` e `DIGEST_NONE`; descobre o nome do algoritmo de `Signature`. */
object GeradorEd25519DoKeystore : GeradorEd25519Keystore {
    private val nomes = listOf("Ed25519", "EdDSA")

    override fun gerar(alias: String): CandidataEd25519 {
        val gerador = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, EnvelopeDeChave.PROVEDOR)
        gerador.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
                .setDigests(KeyProperties.DIGEST_NONE)
                .build(),
        )
        val par = gerador.generateKeyPair()
        val spki = par.public.encoded
        return object : CandidataEd25519 {
            override val spki = spki
            override fun assinar(dados: ByteArray): ByteArray {
                // getKey/getCertificate, não getEntry (relato de falha na API 35).
                val ks = KeyStore.getInstance(EnvelopeDeChave.PROVEDOR).apply { load(null) }
                val privada = ks.getKey(alias, null) as java.security.PrivateKey
                var ultimo: Exception? = null
                for (nome in nomes) {
                    try {
                        return Signature.getInstance(nome).run { initSign(privada); update(dados); sign() }
                    } catch (e: Exception) {
                        ultimo = e
                    }
                }
                throw ultimo ?: IllegalStateException("nenhum nome de Signature aceito")
            }
            override fun apagar() {
                runCatching { KeyStore.getInstance(EnvelopeDeChave.PROVEDOR).apply { load(null) }.deleteEntry(alias) }
            }
        }
    }
}

/**
 * A sonda de produção (CMP-11): antes de usar o Keystore para Ed25519 num aparelho, gera uma chave temporária, confere o formato,
 * assina e verifica. O resultado é lembrado por processo (e só vale para esta API e este fingerprint de sistema). Reprovou → o
 * ed25519 nasce em envelope, e quem chama sabe o motivo para mostrar (nenhuma falha silenciosa).
 */
class SondaEd25519(
    private val api: Int = Build.VERSION.SDK_INT,
    private val gerador: GeradorEd25519Keystore = GeradorEd25519DoKeystore,
) {
    @Volatile
    private var resultado: AvaliadorDaSonda.Veredito? = null

    fun avaliar(): AvaliadorDaSonda.Veredito = resultado ?: synchronized(this) { resultado ?: medir().also { resultado = it } }

    private fun medir(): AvaliadorDaSonda.Veredito {
        if (api < AvaliadorDaSonda.API_MINIMA) return AvaliadorDaSonda.avaliar(api, null, null)
        val alias = "keyflow_ssh_sonda_" + UUID.randomUUID().toString().take(8)
        var candidata: CandidataEd25519? = null
        try {
            candidata = gerador.gerar(alias)
            val spki = candidata.spki
            val bytes = AvaliadorDaSonda.bytesDaPublica(spki) ?: return AvaliadorDaSonda.avaliar(api, spki, null)
            val dados = "keyflow-sonda".toByteArray()
            val assinatura = candidata.assinar(dados)
            val verifica = try { Ed25519Verify(bytes).verify(assinatura, dados); true } catch (e: java.security.GeneralSecurityException) { false }
            return AvaliadorDaSonda.avaliar(api, spki, verifica)
        } catch (e: Exception) {
            return AvaliadorDaSonda.avaliar(api, null, null, e)
        } finally {
            candidata?.apagar()
            runCatching { KeyStore.getInstance(EnvelopeDeChave.PROVEDOR).apply { load(null) }.deleteEntry(alias) }
        }
    }
}
