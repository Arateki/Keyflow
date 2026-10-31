package com.keyflow.computador

import com.keyflow.computador.chaves.ChaveSsh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Porta da gravação: metadados no DataStore, senhas no cofre; permite provar a ordem sem Android. */
interface DadosDaEdicaoDeHost {
    suspend fun hostsOnce(): ConfigHosts
    suspend fun atualizarHosts(f: (ConfigHosts) -> ConfigHosts): ConfigHosts
    suspend fun chavesOnce(): List<ChaveSsh>
    fun senhaLembrada(hostId: String): String?
    fun lembrarSenha(hostId: String, senha: String)
    fun esquecerSenha(hostId: String)
}

/** CMP-09: a preferência não prova que existe segredo. Publica a permissão só depois de confirmar o cofre. */
class GravadorDeHosts(private val dados: DadosDaEdicaoDeHost) {
    private val trava = Mutex()

    suspend fun salvar(host: Host, senha: String?): ResultadoEdicao = trava.withLock {
        val guardar = host.autenticacao == AutenticacaoHost.Senha && host.lembrarSenha
        val antiga = if (guardar) dados.senhaLembrada(host.id)?.takeIf { it.isNotEmpty() } else null
        val nova = senha?.takeIf { it.isNotEmpty() }
        val disponivel = when (val a = host.autenticacao) {
            AutenticacaoHost.Senha -> nova != null || antiga != null
            is AutenticacaoHost.Chave -> a.keyId.isNotBlank() && dados.chavesOnce().any { it.id == a.keyId }
            AutenticacaoHost.Perguntar -> false
        }
        val previa = EdicaoDeHost.aplicar(dados.hostsOnce(), host, credencialDisponivel = disponivel)
        if (previa is ResultadoEdicao.Erros) return@withLock previa

        // Depois do primeiro efeito, fechar o modal não pode desfazer só o segredo de uma configuração já confirmada.
        withContext(NonCancellable) {
            fun restaurar() { runCatching { if (antiga == null) dados.esquecerSenha(host.id) else dados.lembrarSenha(host.id, antiga) } }
            val mudouSenha = guardar && nova != null && nova != antiga
            if (mudouSenha) {
                try {
                    dados.lembrarSenha(host.id, nova)
                    check(dados.senhaLembrada(host.id) == nova) { "cofre não confirmou a senha" }
                } catch (e: CancellationException) { restaurar(); throw e
                } catch (_: Exception) {
                    restaurar()
                    return@withContext ResultadoEdicao.Erros(listOf(ErroHost(CampoHost.AUTENTICACAO, MotivoHost.SENHA_NAO_GUARDADA)))
                }
            }
            var resultado: ResultadoEdicao = previa
            try {
                dados.atualizarHosts { config ->
                    // Revalida nome/limite contra a lista mais recente, já com a senha efetivamente gravada.
                    val temCredencial = if (guardar) !dados.senhaLembrada(host.id).isNullOrEmpty() else disponivel
                    val r = EdicaoDeHost.aplicar(config, host, credencialDisponivel = temCredencial)
                    resultado = r
                    if (r is ResultadoEdicao.Ok) r.config else config
                }
            } catch (e: Exception) { if (mudouSenha) restaurar(); throw e }
            if (resultado is ResultadoEdicao.Erros) { if (mudouSenha) restaurar() }
            else if (!guardar) dados.esquecerSenha(host.id)
            resultado
        }
    }
}
