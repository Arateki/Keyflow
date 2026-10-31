package com.keyflow.computador.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.keyflow.computador.Base64Puro
import com.keyflow.computador.chaves.Assinador
import com.keyflow.computador.chaves.AssinadorSoftware
import com.keyflow.computador.chaves.AvaliadorDaSonda
import com.keyflow.computador.chaves.ChavePublicaSsh
import com.keyflow.computador.chaves.ChaveSsh
import com.keyflow.computador.chaves.ChavesSoftware
import com.keyflow.computador.chaves.CofreDeSegredos
import com.keyflow.computador.chaves.ErroChave
import com.keyflow.computador.chaves.EstadoChave
import com.keyflow.computador.chaves.FormatadorChavePublica
import com.keyflow.computador.chaves.ImportadorDeChave
import com.keyflow.computador.chaves.JanelaDeBiometria
import com.keyflow.computador.chaves.MaterialPrivado
import com.keyflow.computador.chaves.Protecao
import com.keyflow.computador.chaves.RelogioDeChaves
import com.keyflow.computador.chaves.Resultado
import com.keyflow.computador.chaves.TipoDeChave
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** A proteção que a tela diz ao usuário (CMP-10): nunca em silêncio. */
fun Protecao.frase(): String = when (this) {
    Protecao.HARDWARE -> "Protegida pelo hardware do aparelho"
    Protecao.SOFTWARE_COFRE -> "Protegida pelo Keystore (por software)"
    Protecao.KEYSTORE_ENVELOPE -> "Guardada cifrada no cofre do aparelho"
}

/**
 * As chaves no Android (CMP-10, 11, 13, 14, 16). **ECDSA P-256** nasce sempre no Keystore, sem envelope (D13). **Ed25519** nasce no
 * Keystore só quando a API é 33+ e a [sonda] aprova; senão (ou para chave importada) vira **envelope**: semente em software cifrada por
 * uma chave AES-GCM do Keystore. A biometria é opcional por chave, com janela de 300 s (D14).
 *
 * [aplicarBiometriaNoKeystore] só existe para testes em aparelho sem biometria cadastrada: com `false`, a chave não pede autenticação ao
 * Keystore e a janela é aplicada só por esta classe (a lógica do prompt), mas a escolha de API continua em [AutenticacaoKeystore].
 */
class ProvedorDeChavesAndroid(
    private val cofre: CofreDeSegredos,
    private val autenticador: AutenticadorDeUsuario,
    private val relogio: RelogioDeChaves = RelogioDeChaves { System.currentTimeMillis() },
    private val api: Int = Build.VERSION.SDK_INT,
    private val sonda: SondaEd25519 = SondaEd25519(api),
    private val forcarEnvelopeEd25519: Boolean = false,
    private val aplicarBiometriaNoKeystore: Boolean = true,
    private val detector: DetectorDeCadastro = DetectorDeCadastroKeystore(api),
    private val gerarEd25519Software: () -> ChavesSoftware.Ed25519Gerada = ChavesSoftware::gerarEd25519,
    private val importador: ImportadorDeChave = ImportadorDeChave(),
) {
    private val envelope = EnvelopeDeChave(cofre, api, aplicarBiometriaNoKeystore)
    private val janelas = HashMap<String, JanelaDeBiometria>()

    private fun ks(): KeyStore = KeyStore.getInstance(EnvelopeDeChave.PROVEDOR).apply { load(null) }
    private fun alias(id: String) = "$PREFIXO_ALIAS$id"
    // O estado INVALIDADA é **persistente** (no cofre): sobrevive a reinício do processo e a OTA, e uma chave invalidada não volta a funcionar.
    private fun marcaInvalidada(id: String) = "$PREFIXO_INVALIDADA$id"
    /** Leitura incerta = bloqueado (fail-closed): se o cofre não responde, a chave conta como invalidada e não assina. */
    private fun jaInvalidada(id: String): Boolean = id in invalidadasNestaExecucao || try { cofre.ler(marcaInvalidada(id)) != null } catch (e: Exception) { true }

    /**
     * Marca a chave como invalidada. Se o cofre não gravar, a marca vale **em memória** nesta execução (a chave continua bloqueada) e a exceção
     * não escapa de `estado`/`assinar`: o contrato é "bloqueado, `null`, sem exceção".
     */
    private fun invalidar(id: String) {
        invalidadasNestaExecucao += id
        try { cofre.gravar(marcaInvalidada(id), "1") } catch (e: Exception) { /* só em memória */ }
    }
    private val invalidadasNestaExecucao: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun novoId() = UUID.randomUUID().toString().replace("-", "").take(8)

    /** Veredito da sonda do Ed25519 neste aparelho (para a tela explicar por que a chave caiu no envelope). */
    fun veredictoDaSonda(): AvaliadorDaSonda.Veredito = sonda.avaliar()

    /** Como `gerar(ED25519)` vai guardar a chave neste aparelho, sem gerar nada. */
    fun ed25519VaiParaOKeystore(): Boolean = !forcarEnvelopeEd25519 && api >= AvaliadorDaSonda.API_MINIMA && sonda.avaliar() is AvaliadorDaSonda.Veredito.Aprovada

    suspend fun gerar(nome: String, tipo: TipoDeChave, exigeBiometria: Boolean): Resultado<ChaveSsh> {
        if (tipo != TipoDeChave.ED25519 && tipo != TipoDeChave.ECDSA_P256) return Resultado.Falha(ErroChave.TIPO_NAO_SUPORTADO)
        if (exigeBiometria && autenticador.disponivel() != Disponibilidade.SIM) return Resultado.Falha(ErroChave.SEM_BIOMETRIA)
        val id = novoId()
        return try {
            when (tipo) {
                TipoDeChave.ECDSA_P256 -> gerarNoKeystore(id, nome, tipo, exigeBiometria)
                else -> if (ed25519VaiParaOKeystore()) gerarNoKeystore(id, nome, tipo, exigeBiometria) else gerarEnvelopeEd25519(id, nome, exigeBiometria)
            }
        } catch (e: Exception) {
            excluirArtefatos(id)
            Resultado.Falha(ErroChave.FALHA_DO_APARELHO)
        }
    }

    /** Chave importada entra sempre no envelope; o texto e a passphrase são zerados pelo importador. */
    suspend fun importar(pem: CharArray, passphrase: CharArray?, nome: String, exigeBiometria: Boolean): Resultado<ChaveSsh> {
        if (exigeBiometria && autenticador.disponivel() != Disponibilidade.SIM) {
            pem.fill('\u0000'); passphrase?.fill('\u0000')
            return Resultado.Falha(ErroChave.SEM_BIOMETRIA)
        }
        return when (val r = importador.importar(pem, passphrase)) {
            is Resultado.Falha -> r
            is Resultado.Ok -> {
                val id = novoId()
                val m = r.valor.material
                try {
                    if (exigeBiometria && !autenticarParaCriar(id)) return Resultado.Falha(ErroChave.CANCELADA)
                    envelope.cifrar(id, m, exigeBiometria)
                    if (exigeBiometria && aplicarBiometriaNoKeystore) detector.criar(id)
                    cofre.gravar("$PREFIXO_PUBLICA$id", Base64Puro.codificar(r.valor.publica.blob))
                    Resultado.Ok(ChaveSsh(id, nome, r.valor.tipo, r.valor.impressao, Protecao.KEYSTORE_ENVELOPE, exigeBiometria, relogio.agoraMs()))
                } catch (e: Exception) {
                    excluirArtefatos(id)
                    Resultado.Falha(ErroChave.FALHA_DO_APARELHO)
                } finally {
                    m.zerar()
                }
            }
        }
    }

    /** A pública no formato do tipo (CMP-13); `null` se a chave não está mais no aparelho. */
    fun chavePublica(chave: ChaveSsh): ChavePublicaSsh? = try {
        when (chave.protecao) {
            Protecao.KEYSTORE_ENVELOPE -> publicaGuardada(chave.id)
            else -> publicaDoKeystore(chave.id, chave.tipo)
        }
    } catch (e: Exception) {
        null
    }

    fun linhaDaPublica(chave: ChaveSsh, aparelho: String): String? = chavePublica(chave)?.let { FormatadorChavePublica.linha(it, aparelho) }

    fun assinador(chave: ChaveSsh): Assinador = AssinadorAndroid(chave)

    /** A janela de biometria desta chave está aberta agora? Chave sem biometria sempre está. A reconexão automática usa isto para não abrir prompt sozinha. */
    fun janelaAberta(chave: ChaveSsh): Boolean = !chave.exigeBiometria || synchronized(janelas) { janelas[chave.id]?.valida() == true }

    /** `INVALIDADA` quando o Keystore invalidou a chave (biometria cadastrada mudou); a tela mostra a mensagem. */
    fun estado(chave: ChaveSsh): EstadoChave {
        if (jaInvalidada(chave.id)) return EstadoChave.INVALIDADA
        val invalida = try {
            (chave.exigeBiometria && detector.invalidada(chave.id)) ||
            if (chave.protecao == Protecao.KEYSTORE_ENVELOPE) envelope.invalidada(chave.id) else nativaInvalidada(chave.id, chave.tipo)
        } catch (e: Exception) {
            false
        }
        if (invalida) invalidar(chave.id)
        return if (invalida) EstadoChave.INVALIDADA else EstadoChave.ATIVA
    }

    /** Apaga o alias do Keystore e o texto cifrado do cofre. */
    fun excluir(chave: ChaveSsh) = excluirArtefatos(chave.id)

    private fun excluirArtefatos(id: String) {
        runCatching { ks().deleteEntry(alias(id)) }
        runCatching { detector.apagar(id) }
        envelope.apagar(id)
        cofre.apagar("$PREFIXO_PUBLICA$id")
        janelas.remove(id)
        invalidadasNestaExecucao -= id
        cofre.apagar(marcaInvalidada(id))
    }

    /**
     * Cifrar com uma chave AES que exige autenticação também pede a janela aberta: o prompt vem antes, e a janela já nasce marcada.
     * Chave nativa (P-256/Ed25519 no Keystore) não precisa: gerar o par não usa a chave.
     */
    private suspend fun autenticarParaCriar(id: String): Boolean {
        val ok = autenticador.autenticar("Criar a chave", "Confirme que é você para guardar esta chave.")
        if (ok) janelas.getOrPut(id) { JanelaDeBiometria(relogio) }.marcar()
        return ok
    }

    // ---- geração ------------------------------------------------------------------------------------------------------------------

    private fun gerarNoKeystore(id: String, nome: String, tipo: TipoDeChave, exigeBiometria: Boolean): Resultado<ChaveSsh> {
        val ed = tipo == TipoDeChave.ED25519
        val spec = KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(if (ed) "ed25519" else "secp256r1"))
            .setDigests(if (ed) KeyProperties.DIGEST_NONE else KeyProperties.DIGEST_SHA256)
        if (exigeBiometria && aplicarBiometriaNoKeystore) AutenticacaoKeystore.aplicar(spec, api)
        val gerador = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, EnvelopeDeChave.PROVEDOR)
        gerador.initialize(spec.build())
        gerador.generateKeyPair()
        if (exigeBiometria && aplicarBiometriaNoKeystore) detector.criar(id)
        val publica = publicaDoKeystore(id, tipo) ?: error("pública ausente")
        val protecao = nivelDeProtecao(id)
        return Resultado.Ok(ChaveSsh(id, nome, tipo, publica.impressao, protecao, exigeBiometria, relogio.agoraMs()))
    }

    private suspend fun gerarEnvelopeEd25519(id: String, nome: String, exigeBiometria: Boolean): Resultado<ChaveSsh> {
        if (exigeBiometria && !autenticarParaCriar(id)) return Resultado.Falha(ErroChave.CANCELADA)
        val g = gerarEd25519Software()
        try {
            envelope.cifrar(id, g.material, exigeBiometria)
            if (exigeBiometria && aplicarBiometriaNoKeystore) detector.criar(id)
            // O envelope guarda a pública junto da semente: o Keystore não a conhece.
            cofre.gravar("$PREFIXO_PUBLICA$id", Base64Puro.codificar(g.publica.blob))
            return Resultado.Ok(ChaveSsh(id, nome, TipoDeChave.ED25519, g.publica.impressao, Protecao.KEYSTORE_ENVELOPE, exigeBiometria, relogio.agoraMs()))
        } finally {
            g.material.zerar()
        }
    }

    private fun publicaGuardada(id: String): ChavePublicaSsh? {
        val b64 = cofre.ler("$PREFIXO_PUBLICA$id") ?: return null
        val blob = Base64Puro.decodificar(b64) ?: return null
        val tipo = FormatadorChavePublica.tipoDoBlob(blob) ?: return null
        return ChavePublicaSsh(if (tipo == "ssh-rsa") "rsa-sha2-256" else tipo, blob)
    }

    private fun publicaDoKeystore(id: String, tipo: TipoDeChave): ChavePublicaSsh? {
        val cert = ks().getCertificate(alias(id)) ?: return null
        val pub = cert.publicKey
        return when (tipo) {
            TipoDeChave.ED25519 -> {
                val bytes = AvaliadorDaSonda.bytesDaPublica(pub.encoded) ?: return null
                ChavePublicaSsh("ssh-ed25519", FormatadorChavePublica.blobEd25519(bytes))
            }
            TipoDeChave.ECDSA_P256 -> {
                val ec = pub as? ECPublicKey ?: return null
                ChavePublicaSsh("ecdsa-sha2-nistp256", FormatadorChavePublica.blobEcdsaP256(pontoNaoComprimido(ec)))
            }
            else -> null
        }
    }

    private fun pontoNaoComprimido(k: ECPublicKey): ByteArray {
        fun fixo(v: BigInteger): ByteArray = v.toByteArray().let { b -> ByteArray(32).also { out ->
            val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
            System.arraycopy(src, 0, out, 32 - src.size, src.size)
        } }
        return byteArrayOf(4) + fixo(k.w.affineX) + fixo(k.w.affineY)
    }

    private fun nivelDeProtecao(id: String): Protecao = try {
        val privada = ks().getKey(alias(id), null) as PrivateKey
        val info = KeyFactory.getInstance(privada.algorithm, EnvelopeDeChave.PROVEDOR).getKeySpec(privada, KeyInfo::class.java)
        val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT || info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION") info.isInsideSecureHardware
        }
        if (hardware) Protecao.HARDWARE else Protecao.SOFTWARE_COFRE
    } catch (e: Exception) {
        // Não deu para ler o nível: assume o mais fraco e diz isso, em vez de prometer hardware.
        Protecao.SOFTWARE_COFRE
    }

    private fun nativaInvalidada(id: String, tipo: TipoDeChave): Boolean {
        val privada = ks().getKey(alias(id), null) as? PrivateKey ?: return false
        return try {
            iniciarAssinatura(privada, tipo)
            false
        } catch (e: KeyPermanentlyInvalidatedException) {
            true
        } catch (e: UserNotAuthenticatedException) {
            false
        }
    }

    /** Ed25519 do Keystore: o nome do algoritmo de `Signature` varia por provedor (`Ed25519` ou `EdDSA`); tenta os dois. */
    private fun iniciarAssinatura(privada: PrivateKey, tipo: TipoDeChave): Signature {
        if (tipo != TipoDeChave.ED25519) return Signature.getInstance("SHA256withECDSA").apply { initSign(privada) }
        var ultimo: Exception? = null
        for (nome in listOf("Ed25519", "EdDSA")) {
            try {
                return Signature.getInstance(nome).apply { initSign(privada) }
            } catch (e: UserNotAuthenticatedException) {
                throw e
            } catch (e: KeyPermanentlyInvalidatedException) {
                throw e
            } catch (e: Exception) {
                ultimo = e
            }
        }
        throw ultimo ?: IllegalStateException("nenhum nome de Signature aceito")
    }

    // ---- assinatura ---------------------------------------------------------------------------------------------------------------

    private inner class AssinadorAndroid(private val chave: ChaveSsh) : Assinador {
        private val janela get() = janelas.getOrPut(chave.id) { JanelaDeBiometria(relogio) }

        override val invalidada: Boolean get() = jaInvalidada(chave.id)

        override fun chavePublica(): ChavePublicaSsh = this@ProvedorDeChavesAndroid.chavePublica(chave) ?: error("chave ausente: ${chave.id}")

        override suspend fun assinar(dados: ByteArray): ByteArray? {
            if (jaInvalidada(chave.id)) return null
            // APIs 24–29: o Keystore pode não invalidar a chave ao cadastrar outra digital; a sentinela diz.
            if (chave.exigeBiometria && detector.invalidada(chave.id)) { invalidar(chave.id); return null }
            if (chave.exigeBiometria && !janela.valida() && !pedirBiometria()) return null
            return try {
                assinarAgora(dados)
            } catch (e: UserNotAuthenticatedException) {
                // O Keystore diz que a janela dele venceu (o nosso relógio e o do Keystore podem divergir): um prompt e uma nova tentativa.
                janela.encerrar()
                if (!pedirBiometria()) return null
                try { assinarAgora(dados) } catch (e2: UserNotAuthenticatedException) { null } catch (e2: EnvelopeIndisponivel) { marcarInvalidada(e2) }
            } catch (e: KeyPermanentlyInvalidatedException) {
                invalidar(chave.id)
                null
            } catch (e: EnvelopeIndisponivel) {
                marcarInvalidada(e)
            } catch (e: java.security.GeneralSecurityException) {
                // Falha do aparelho ao usar a chave: sem assinatura (o cbssh pula a chave), nunca uma exceção para a sessão.
                null
            }
        }

        private fun marcarInvalidada(e: EnvelopeIndisponivel): ByteArray? {
            if (e.invalidada) invalidar(chave.id)
            return null
        }

        private suspend fun pedirBiometria(): Boolean {
            val ok = autenticador.autenticar("Usar a chave ${chave.nome.take(40)}", "Confirme que é você para liberar esta chave por 5 minutos.")
            if (ok) janela.marcar()
            return ok
        }

        private suspend fun assinarAgora(dados: ByteArray): ByteArray? = when (chave.protecao) {
            Protecao.KEYSTORE_ENVELOPE -> AssinadorSoftware(chavePublica()) { envelope.decifrar(chave.id) }.assinar(dados)
            else -> assinarNoKeystore(dados)
        }

        private fun assinarNoKeystore(dados: ByteArray): ByteArray {
            val privada = ks().getKey(alias(chave.id), null) as? PrivateKey ?: throw EnvelopeIndisponivel(false)
            val assinatura = iniciarAssinatura(privada, chave.tipo).apply { update(dados) }.sign()
            return if (chave.tipo == TipoDeChave.ED25519) {
                FormatadorChavePublica.campo("ssh-ed25519") + FormatadorChavePublica.campo(assinatura)
            } else {
                val (r, s) = RsDeDer.separar(assinatura)
                FormatadorChavePublica.campo("ecdsa-sha2-nistp256") + FormatadorChavePublica.campo(FormatadorChavePublica.mpint(r) + FormatadorChavePublica.mpint(s))
            }
        }
    }

    companion object {
        const val PREFIXO_ALIAS = "keyflow_ssh_"
        const val PREFIXO_PUBLICA = "ssh_pub."
        const val PREFIXO_INVALIDADA = "ssh_inv."
    }
}

/** Separa `r` e `s` de uma assinatura ECDSA em DER (`SEQUENCE { INTEGER r, INTEGER s }`). */
internal object RsDeDer {
    fun separar(der: ByteArray): Pair<BigInteger, BigInteger> {
        require(der.isNotEmpty() && der[0] == 0x30.toByte()) { "DER inesperado" }
        var i = lerComprimento(der, 1).second
        require(der[i++] == 0x02.toByte())
        val (lr, ir) = lerComprimento(der, i)
        val r = BigInteger(1, der.copyOfRange(ir, ir + lr))
        i = ir + lr
        require(der[i++] == 0x02.toByte())
        val (ls, is_) = lerComprimento(der, i)
        return r to BigInteger(1, der.copyOfRange(is_, is_ + ls))
    }

    private fun lerComprimento(b: ByteArray, inicio: Int): Pair<Int, Int> {
        val primeiro = b[inicio].toInt() and 0xff
        if (primeiro < 0x80) return primeiro to inicio + 1
        val n = primeiro and 0x7f
        var v = 0
        for (k in 1..n) v = (v shl 8) or (b[inicio + k].toInt() and 0xff)
        return v to inicio + 1 + n
    }
}
