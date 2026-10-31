package com.keyflow.computador

/** O resultado de gravar um host editado na tela (CMP-05, 07, 09). */
sealed interface ResultadoEdicao {
    data class Ok(val config: ConfigHosts, val host: Host) : ResultadoEdicao
    data class Erros(val erros: List<ErroHost>) : ResultadoEdicao
}

/**
 * A regra de gravar um host vindo do modal, em função **pura** (roda dentro da transação do DataStore): normaliza (espaços das pontas), valida
 * contra os outros hosts, aplica a [RegraAgente] (a marca do agente nunca fica ligada em host que o app não autentica sozinho) e troca ou
 * acrescenta o host na lista. Não mexe em segredo.
 */
object EdicaoDeHost {
    fun aplicar(config: ConfigHosts, editado: Host, chavePedePassphrase: (String) -> Boolean = { false }): ResultadoEdicao {
        var host = editado.copy(nome = editado.nome.trim(), endereco = editado.endereco.trim(), usuario = editado.usuario.trim())
        if (host.autenticacao !is AutenticacaoHost.Senha) host = host.copy(lembrarSenha = false)
        if (host.agente == PoliticaAgente.PERMITIDO && RegraAgente.podeMarcar(host, chavePedePassphrase) !is PodeMarcar.Sim) host = host.copy(agente = PoliticaAgente.PROIBIDO)
        val outros = config.hosts.filter { it.id != host.id }
        val erros = ValidadorHost.validar(host, config.hosts)
        if (erros.isNotEmpty()) return ResultadoEdicao.Erros(erros)
        val novos = if (config.hosts.any { it.id == host.id }) config.hosts.map { if (it.id == host.id) host else it } else outros + host
        return ResultadoEdicao.Ok(ConfigHosts(novos), host)
    }

    /** Id novo de 8 hex que não colide com os existentes. */
    fun novoId(existentes: Collection<String>, sorteio: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(8) }): String {
        repeat(32) { val id = sorteio(); if (id !in existentes) return id }
        error("não achei um id livre")
    }
}
