package com.keyflow.computador.chaves

/** A janela de 5 minutos (D14): um prompt bem-sucedido libera a chave por [DURACAO_MS]; dentro dela não há novo prompt. */
class JanelaDeBiometria(private val relogio: RelogioDeChaves, private val duracaoMs: Long = DURACAO_MS) {
    @Volatile
    private var ultimaMs: Long? = null

    fun valida(): Boolean {
        val u = ultimaMs ?: return false
        val delta = relogio.agoraMs() - u
        // Relógio que anda para trás não renova a janela: conta como vencida.
        return delta in 0 until duracaoMs
    }

    fun marcar() {
        ultimaMs = relogio.agoraMs()
    }

    fun encerrar() {
        ultimaMs = null
    }

    companion object {
        const val DURACAO_MS = 300_000L
        const val DURACAO_S = 300
    }
}

/** Como pedir ao Keystore a janela de 300 s, conforme a API (CMP-14). Pura, para testar sem aparelho. */
sealed interface PlanoDeAutenticacao {
    /** API 24–29: `setUserAuthenticationValidityDurationSeconds`. */
    data class Validade(val segundos: Int) : PlanoDeAutenticacao

    /** API 30+: `setUserAuthenticationParameters(segundos, AUTH_BIOMETRIC_STRONG)`; credencial do aparelho junto só se a T2 confirmar. */
    data class Parametros(val segundos: Int, val biometriaForte: Boolean = true) : PlanoDeAutenticacao

    companion object {
        fun para(api: Int): PlanoDeAutenticacao =
            if (api >= 30) Parametros(JanelaDeBiometria.DURACAO_S) else Validade(JanelaDeBiometria.DURACAO_S)
    }
}

/**
 * Decide se a sonda do Ed25519 no Keystore (CMP-11) aprova o aparelho. Recebe só fatos medidos; quem os mede é o Android.
 * Reprova (e o ed25519 cai no envelope) por: API < 33, exceção, pública que não é Ed25519 (a armadilha do P-256), assinatura que não verifica.
 */
object AvaliadorDaSonda {
    const val API_MINIMA = 33
    private val prefixoSpki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    sealed interface Veredito {
        data object Aprovada : Veredito
        data class Reprovada(val motivo: String) : Veredito
    }

    fun avaliar(api: Int, spki: ByteArray?, assinaturaVerifica: Boolean?, erro: Throwable? = null): Veredito = when {
        api < API_MINIMA -> Veredito.Reprovada("API $api é anterior à 33")
        erro != null -> Veredito.Reprovada("exceção ${erro.javaClass.simpleName}")
        spki == null -> Veredito.Reprovada("o Keystore não devolveu a chave pública")
        spki.size != 44 || !spki.copyOfRange(0, prefixoSpki.size).contentEquals(prefixoSpki) -> Veredito.Reprovada("a chave pública não é Ed25519 (${spki.size} bytes)")
        assinaturaVerifica != true -> Veredito.Reprovada("a assinatura não verifica")
        else -> Veredito.Aprovada
    }

    /** Os 32 bytes da chave dentro do SPKI de 44 bytes, ou `null` se o formato não é o de Ed25519. */
    fun bytesDaPublica(spki: ByteArray): ByteArray? =
        if (spki.size == 44 && spki.copyOfRange(0, prefixoSpki.size).contentEquals(prefixoSpki)) spki.copyOfRange(12, 44) else null
}
