package com.keyflow.computador.app

import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.EdicaoDeHost
import com.keyflow.computador.ErroHost
import com.keyflow.computador.Host
import com.keyflow.computador.ResultadoEdicao
import com.keyflow.computador.sessao.GerenciadorDeSessoes
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.computador.ssh.ResultadoTeste
import com.keyflow.ui.config.AcoesDeHost

/** As ações do painel de hosts ligadas ao repositório, ao gerenciador de sessões e ao teste real. */
class AcoesDeHostReais(
    private val repositorio: RepositorioComputador,
    private val gerenciador: GerenciadorDeSessoes,
    private val testador: TestadorDeConexao,
    private val provedorDeChaves: ProvedorDeChavesAndroid,
) : AcoesDeHost {
    private fun enderecoDeConfianca(h: Host) = h.endereco.removePrefix("[").removeSuffix("]")

    override suspend fun salvar(host: Host, senha: String?): List<ErroHost> {
        val antes = repositorio.hostsOnce().porId(host.id)
        var resultado: ResultadoEdicao? = null
        repositorio.atualizarHosts { c ->
            val r = EdicaoDeHost.aplicar(c, host)
            resultado = r
            if (r is ResultadoEdicao.Ok) r.config else c
        }
        return when (val r = resultado) {
            is ResultadoEdicao.Erros -> r.erros
            is ResultadoEdicao.Ok -> {
                if (r.host.autenticacao == AutenticacaoHost.Senha) {
                    if (r.host.lembrarSenha) senha?.let { repositorio.lembrarSenha(r.host.id, it) } else repositorio.esquecerSenha(r.host.id)
                } else repositorio.esquecerSenha(r.host.id)
                // `known_hosts` é por `host:porta`: o endereço antigo deixa de valer para este host.
                if (antes != null && (antes.endereco != r.host.endereco || antes.porta != r.host.porta)) repositorio.confianca.esquecer(enderecoDeConfianca(antes), antes.porta)
                emptyList()
            }
            null -> emptyList()
        }
    }

    override suspend fun excluir(host: Host) = repositorio.excluirHost(host.id) { gerenciador.fecharDoHost(it) }

    override suspend fun testar(host: Host, senha: String?): ResultadoTeste {
        val chave = (host.autenticacao as? AutenticacaoHost.Chave)?.let { a -> repositorio.chavesOnce().firstOrNull { it.id == a.keyId } }
        if (host.autenticacao is AutenticacaoHost.Chave && chave == null) return ResultadoTeste.Falhou(CausaSsh.CHAVE_NAO_ACEITA)
        val lembrada = if (host.autenticacao == AutenticacaoHost.Senha && host.lembrarSenha) repositorio.senhaLembrada(host.id) else null
        return testador.testar(host, senha ?: lembrada, chave?.let(provedorDeChaves::assinador))
    }

    override fun impressoesConhecidas(host: Host): List<ChaveDoServidor> = repositorio.confianca.chavesConhecidas(enderecoDeConfianca(host), host.porta)

    override fun esquecerServidor(host: Host) = repositorio.confianca.esquecer(enderecoDeConfianca(host), host.porta)

    override suspend fun desligarAgente(host: Host) = gerenciador.fecharDoAgenteNoHost(host.id)

    override fun novoId(existentes: Collection<String>): String = EdicaoDeHost.novoId(existentes)
}
