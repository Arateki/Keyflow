package com.keyflow.computador

/** O host único legado (`computerSsh*` das `Settings`), lido pelo repositório e entregue à migração. */
class HostUnicoLegado(
    val host: String,
    val porta: Int,
    val usuario: String,
    /** `"password"` ou `"key"`. */
    val tipoAuth: String,
    val senha: String,
    val chavePem: String,
) {
    override fun toString() = "HostUnicoLegado(host=$host, porta=$porta, usuario=$usuario, tipoAuth=$tipoAuth, segredos=***)"
}

/** Os segredos do legado, **fora** do host: o repositório os leva ao cofre novo e apaga os antigos. */
class SegredosMigrados(val senha: String?, val chavePem: String?) {
    override fun toString() = "SegredosMigrados(***)"
}

class ResultadoMigracao(val config: ConfigHosts, val segredos: SegredosMigrados, val chaveId: String?) {
    override fun toString() = "ResultadoMigracao(hosts=${config.hosts.size}, segredos=***)"
}

/** CMP-06. Função pura e determinística: mesmos dados legados dão o mesmo id, então migrar de novo não duplica. */
object MigracaoHostUnico {
    const val NOME = "Servidor SSH"
    const val ID_HOST = "5e7a0001"
    const val ID_CHAVE = "5e7a0002"

    /**
     * @param existente a lista atual; se já tem o host migrado (ou `computer_hosts_json` existe e não está vazia) devolve `null`.
     * @return `null` quando não há nada a migrar ou o legado não passa na validação.
     */
    fun migrar(legado: HostUnicoLegado, existente: ConfigHosts? = null): ResultadoMigracao? {
        val endereco = legado.host.trim()
        if (endereco.isEmpty()) return null
        if (existente != null && (existente.hosts.isNotEmpty())) return null
        val temChave = legado.tipoAuth == "key" && legado.chavePem.isNotBlank()
        val temSenha = legado.senha.isNotEmpty()
        val auth = when {
            temChave -> AutenticacaoHost.Chave(ID_CHAVE)
            legado.tipoAuth == "key" -> AutenticacaoHost.Perguntar
            else -> AutenticacaoHost.Senha
        }
        val host = Host(
            id = ID_HOST, nome = NOME, endereco = enderecoSeguro(endereco),
            porta = legado.porta.takeIf { it in LimitesHost.PORTA_MIN..LimitesHost.PORTA_MAX } ?: LimitesHost.PORTA_PADRAO,
            usuario = legado.usuario.trim().ifEmpty { "root" }, autenticacao = auth,
            lembrarSenha = auth == AutenticacaoHost.Senha && temSenha,
        )
        // Um legado que o modelo novo recusaria não é migrado (nem apagado): o codec o descartaria e o dado se perderia.
        if (ValidadorHost.validar(host).isNotEmpty()) return null
        val config = ConfigHosts(listOf(host))
        val segredos = SegredosMigrados(
            senha = legado.senha.takeIf { auth == AutenticacaoHost.Senha && it.isNotEmpty() },
            chavePem = legado.chavePem.takeIf { temChave },
        )
        return ResultadoMigracao(config, segredos, if (temChave) ID_CHAVE else null)
    }

    /** IPv6 sem colchetes no legado vira com colchetes, que é o formato do modelo novo. */
    private fun enderecoSeguro(e: String) = if (e.contains(':') && !e.startsWith("[")) "[$e]" else e
}
