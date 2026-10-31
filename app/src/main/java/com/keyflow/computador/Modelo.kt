package com.keyflow.computador

/** Limites do modelo de hosts (CMP-05). */
object LimitesHost {
    const val MAX_HOSTS = 50
    const val NOME_MIN = 1
    const val NOME_MAX = 40
    const val USUARIO_MIN = 1
    const val USUARIO_MAX = 64
    const val ENDERECO_MAX = 253
    const val PORTA_MIN = 1
    const val PORTA_MAX = 65535
    const val PORTA_PADRAO = 22
}

/** Como o host se autentica. Nunca carrega segredo: a chave é uma referência (`keyId`) e a senha mora no cofre. */
sealed interface AutenticacaoHost {
    /** Chave guardada pelo app (Keystore ou envelope), referenciada pelo id. */
    data class Chave(val keyId: String) : AutenticacaoHost

    /** Senha; só é resolvida pelo app quando [Host.lembrarSenha] está ligado. */
    data object Senha : AutenticacaoHost

    /** Pergunta na hora, a cada conexão. */
    data object Perguntar : AutenticacaoHost
}

/** "Pode ser usado pelo agente" (D5, CMP-09). O padrão é proibir. */
enum class PoliticaAgente { PROIBIDO, PERMITIDO }

/** Política de imagens por host (CMP-37). O padrão é perguntar. */
enum class PoliticaImagens { DESLIGADO, PERGUNTAR, LIGADO }

/** Um servidor SSH cadastrado. Não tem nenhum campo de segredo (teste `HostSemSegredoTest`). */
data class Host(
    val id: String,
    val nome: String,
    val endereco: String,
    val porta: Int = LimitesHost.PORTA_PADRAO,
    val usuario: String,
    val autenticacao: AutenticacaoHost = AutenticacaoHost.Perguntar,
    val lembrarSenha: Boolean = false,
    val agente: PoliticaAgente = PoliticaAgente.PROIBIDO,
    val imagens: PoliticaImagens = PoliticaImagens.PERGUNTAR,
) {
    val agentePodeUsar: Boolean get() = agente == PoliticaAgente.PERMITIDO
}

/** A lista de hosts persistida em `computer_hosts_json`. */
data class ConfigHosts(val hosts: List<Host> = emptyList()) {
    fun porId(id: String): Host? = hosts.firstOrNull { it.id == id }
}
