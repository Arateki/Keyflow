package com.keyflow.computador.app

import android.os.Build
import com.keyflow.computador.chaves.AvaliadorDaSonda
import com.keyflow.computador.chaves.ChaveSsh
import com.keyflow.computador.chaves.ErroChave
import com.keyflow.computador.chaves.Resultado
import com.keyflow.computador.chaves.TipoDeChave
import com.keyflow.ui.config.AcoesDeChaves
import com.keyflow.ui.config.InformacaoDaChave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A interface recebe só metadados e pública; toda criptografia e persistência rodam em IO. */
class AcoesDeChavesReais(private val app: ComputadorApp) : AcoesDeChaves {
    override fun biometriaDisponivel() = app.autenticador.disponivel()
    override suspend fun informacao(chave: ChaveSsh) = withContext(Dispatchers.IO) {
        InformacaoDaChave(
            app.provedorDeChaves.linhaDaPublica(chave, Build.MODEL),
            app.provedorDeChaves.estado(chave),
            if (chave.tipo == TipoDeChave.ED25519) app.provedorDeChaves.veredictoDaSonda() is AvaliadorDaSonda.Veredito.Aprovada else null,
        )
    }
    override suspend fun gerar(nome: String, tipo: TipoDeChave, biometria: Boolean) = withContext(Dispatchers.IO) {
        guardar { app.provedorDeChaves.gerar(nome, tipo, biometria) }
    }
    override suspend fun importar(nome: String, privada: CharArray, passphrase: CharArray?, biometria: Boolean): Resultado<ChaveSsh> = withContext(Dispatchers.IO) {
        try { guardar { app.provedorDeChaves.importar(privada, passphrase, nome, biometria) } }
        finally { privada.fill('\u0000'); passphrase?.fill('\u0000') }
    }
    private suspend fun guardar(criar: suspend () -> Resultado<ChaveSsh>): Resultado<ChaveSsh> {
        val resultado = criar()
        if (resultado is Resultado.Ok) {
            try { app.repositorio.atualizarChaves { it + resultado.valor } }
            catch (e: Exception) {
                app.provedorDeChaves.excluir(resultado.valor)
                if (e is CancellationException) throw e
                return Resultado.Falha(ErroChave.FALHA_DO_APARELHO)
            }
        }
        return resultado
    }
    override suspend fun renomear(chave: ChaveSsh, nome: String) {
        require(nome.isNotBlank() && nome.length <= 80)
        withContext(Dispatchers.IO) { app.repositorio.atualizarChaves { lista -> lista.map { if (it.id == chave.id) it.copy(nome = nome.trim()) else it } } }
    }
    override suspend fun excluir(chave: ChaveSsh) {
        withContext(Dispatchers.IO) {
            app.repositorio.hostsDaChave(chave.id).forEach { app.gerenciador.fecharDoAgenteNoHost(it.id) }
            app.repositorio.excluirChave(chave, app.provedorDeChaves::excluir)
        }
    }
}
