package com.keyflow.computador.app

import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.Host
import com.keyflow.computador.ImpressaoDigital
import com.keyflow.computador.RepositorioDeConfianca
import com.keyflow.computador.chaves.Assinador
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.computador.ssh.ConexaoCbssh
import com.keyflow.computador.ssh.ConexaoSsh
import com.keyflow.computador.ssh.DestinoSsh
import com.keyflow.computador.ssh.InteracaoComUsuario
import com.keyflow.computador.ssh.ResultadoTeste
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A confiança **só de leitura** do "Testar conexão" (CMP-08): enxerga o `known_hosts` de verdade (um servidor conhecido com chave diferente
 * continua sendo "mudou"), mas o que o teste aceita fica em memória e **morre com ele**. Testar nunca passa a confiar num servidor.
 */
class ConfiancaEfemera(private val base: RepositorioDeConfianca) : RepositorioDeConfianca {
    private val aceitas = mutableListOf<Triple<String, Int, ChaveDoServidor>>()

    override fun chavesConhecidas(host: String, porta: Int): List<ChaveDoServidor> =
        base.chavesConhecidas(host, porta) + aceitas.filter { it.first == host && it.second == porta }.map { it.third }

    @Synchronized override fun confiar(host: String, porta: Int, chave: ChaveDoServidor) { aceitas += Triple(host, porta, chave) }
    override fun esquecer(host: String, porta: Int) = Unit
}

/** O "Testar conexão" **real** (CMP-08): TCP, troca de chaves, verificação do servidor e autenticação, sem abrir o shell. Máximo de 15 s. */
class TestadorDeConexao(
    private val confianca: RepositorioDeConfianca,
    private val escopo: CoroutineScope,
    private val limiteMs: Long = 15_000,
    private val fabrica: (RepositorioDeConfianca, InteracaoComUsuario, CoroutineScope) -> ConexaoSsh = { c, i, e -> ConexaoCbssh(c, i, e) },
) {
    /**
     * @param senha a senha **digitada na tela** (ou a lembrada); o teste nunca abre diálogo. Host "perguntar" sem senha falha com [CausaSsh.AUTENTICACAO].
     * @param assinador para host com chave; `null` com host de chave falha por [CausaSsh.CHAVE_NAO_ACEITA].
     */
    suspend fun testar(host: Host, senha: String?, assinador: Assinador?): ResultadoTeste {
        val efemera = ConfiancaEfemera(confianca)
        val interacao = object : InteracaoComUsuario {
            // O teste mostra a impressão digital no resultado; aceitar aqui vale só para esta conexão (confiança efêmera).
            override suspend fun confirmarServidor(host: String, porta: Int, algoritmo: String, impressao: ImpressaoDigital) = true
            override suspend fun pedirSenha(): String? = senha
            override suspend fun pedirCodigo(nome: String, instrucao: String, perguntas: List<String>, eco: List<Boolean>): List<String>? = null
        }
        val conexao = fabrica(efemera, interacao, escopo)
        val destino = DestinoSsh(host, senhaLembrada = senha.takeIf { host.autenticacao !is AutenticacaoHost.Chave }, assinador = assinador, confiarSozinho = true)
        return withTimeoutOrNull(limiteMs) { conexao.testar(destino) } ?: ResultadoTeste.Falhou(CausaSsh.TEMPO)
    }
}
