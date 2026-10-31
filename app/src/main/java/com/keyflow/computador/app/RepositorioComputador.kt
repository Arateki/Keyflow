package com.keyflow.computador.app

import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.ConfigHosts
import com.keyflow.computador.DadosDaEdicaoDeHost
import com.keyflow.computador.Host
import com.keyflow.computador.MigracaoHostUnico
import com.keyflow.computador.PoliticaAgente
import com.keyflow.computador.RepositorioDeConfianca
import com.keyflow.computador.chaves.ChaveSsh
import com.keyflow.computador.chaves.CofreDeSegredos
import com.keyflow.computador.chaves.Resultado
import com.keyflow.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * A persistência da aba Computador (CMP-05, 06, 07, 15, 19) sobre o mesmo DataStore das Settings: hosts e metadados de chave são JSON
 * sem segredo; senhas lembradas moram no [cofre] (`ssh_pw.<hostId>`); a confiança nos servidores, no arquivo `known_hosts`.
 */
class RepositorioComputador(
    private val settings: SettingsRepository,
    private val cofre: CofreDeSegredos,
    arquivoKnownHosts: File,
) : DadosDaEdicaoDeHost {
    val confianca: RepositorioDeConfianca = RepositorioKnownHostsArquivo(arquivoKnownHosts)

    fun hostsFlow(): Flow<ConfigHosts> = settings.hostsFlow()
    override suspend fun hostsOnce(): ConfigHosts = settings.hostsOnce()
    override suspend fun atualizarHosts(f: (ConfigHosts) -> ConfigHosts): ConfigHosts = settings.atualizarHosts(f)
    fun chavesFlow(): Flow<List<ChaveSsh>> = settings.chavesFlow()
    override suspend fun chavesOnce(): List<ChaveSsh> = settings.chavesOnce()
    suspend fun atualizarChaves(f: (List<ChaveSsh>) -> List<ChaveSsh>): List<ChaveSsh> = settings.atualizarChaves(f)

    override fun senhaLembrada(hostId: String): String? = cofre.ler("$PREFIXO_SENHA$hostId")
    override fun lembrarSenha(hostId: String, senha: String) = cofre.gravar("$PREFIXO_SENHA$hostId", senha)
    override fun esquecerSenha(hostId: String) = cofre.apagar("$PREFIXO_SENHA$hostId")

    /**
     * CMP-07: fecha as sessões do host, apaga a entrada do `known_hosts`, a senha lembrada, e tira o host da lista (a marca do agente vai
     * junto). A **chave não é tocada**: pode servir a outros hosts.
     */
    suspend fun excluirHost(hostId: String, fecharSessoes: suspend (String) -> Unit) {
        val host = hostsOnce().porId(hostId) ?: return
        fecharSessoes(hostId)
        confianca.esquecer(host.endereco, host.porta)
        cofre.apagar("$PREFIXO_SENHA$hostId")
        atualizarHosts { c -> ConfigHosts(c.hosts.filter { it.id != hostId }) }
    }

    /** Os hosts que usam a chave, para a confirmação de exclusão (CMP-15). */
    suspend fun hostsDaChave(chaveId: String): List<Host> = hostsOnce().hosts.filter { (it.autenticacao as? AutenticacaoHost.Chave)?.keyId == chaveId }

    /**
     * CMP-15: os hosts que usavam a chave ficam "sem chave" (`Perguntar`) e perdem a marca do agente (CMP-09 não vale mais); depois a chave
     * sai dos metadados e [apagarMaterial] a tira do Keystore e do cofre. Devolve os hosts afetados.
     */
    suspend fun excluirChave(chave: ChaveSsh, apagarMaterial: (ChaveSsh) -> Unit): List<Host> {
        val afetados = hostsDaChave(chave.id)
        atualizarHosts { c ->
            ConfigHosts(c.hosts.map { h ->
                if ((h.autenticacao as? AutenticacaoHost.Chave)?.keyId == chave.id) h.copy(autenticacao = AutenticacaoHost.Perguntar, agente = PoliticaAgente.PROIBIDO) else h
            })
        }
        atualizarChaves { l -> l.filter { it.id != chave.id } }
        apagarMaterial(chave)
        return afetados
    }

    /**
     * CMP-06, no primeiro uso: com `computerSshHost` preenchido e sem host novo, cria o host "Servidor SSH", leva a senha ao segredo do
     * host e a chave privada a uma `ChaveSsh` importada por [importarChave], grava hosts+chaves+marca numa só transação e **só então** apaga
     * `computer_ssh_password`/`computer_ssh_key` do cofre antigo. Rodar de novo não duplica. Se a chave antiga não puder ser importada, o
     * host fica com autenticação "perguntar" e a chave legada **não é apagada** (nada se perde em silêncio). Devolve se migrou algo.
     */
    suspend fun migrarHostUnico(importarChave: suspend (CharArray) -> Resultado<ChaveSsh>): Boolean {
        if (settings.migracaoComputadorFeita()) return false
        val legado = settings.hostUnicoLegado()
        val existente = hostsOnce()
        val migracao = MigracaoHostUnico.migrar(legado, existente)
        if (migracao == null) {
            // Nada a migrar (legado vazio, ou já há hosts novos): marca e segue. Legado inválido não é apagado.
            if (legado.host.isBlank() || existente.hosts.isNotEmpty()) settings.marcarMigracaoComputador()
            return false
        }
        var host = migracao.config.hosts.single()
        var chaveCriada: ChaveSsh? = null
        var apagarChaveLegada = true
        migracao.segredos.chavePem?.let { pem ->
            when (val r = importarChave(pem.toCharArray())) {
                is Resultado.Ok -> { chaveCriada = r.valor; host = host.copy(autenticacao = AutenticacaoHost.Chave(r.valor.id)) }
                is Resultado.Falha -> { host = host.copy(autenticacao = AutenticacaoHost.Perguntar); apagarChaveLegada = false }
            }
        }
        migracao.segredos.senha?.let { cofre.gravar("$PREFIXO_SENHA${host.id}", it) }
        try {
            settings.gravarMigracaoComputador(ConfigHosts(listOf(host)), listOfNotNull(chaveCriada))
        } catch (e: Exception) {
            cofre.apagar("$PREFIXO_SENHA${host.id}")
            throw e
        }
        if (apagarChaveLegada) settings.apagarSegredosDoHostUnicoLegado()
        else settings.saveSecret(com.keyflow.data.SettingsCredentialKeys.COMPUTER_SSH_PASSWORD, "")
        return true
    }

    companion object {
        const val PREFIXO_SENHA = "ssh_pw."
    }
}
