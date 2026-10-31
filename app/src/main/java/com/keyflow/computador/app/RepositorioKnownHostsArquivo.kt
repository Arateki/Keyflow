package com.keyflow.computador.app

import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.KnownHostsArquivo
import com.keyflow.computador.RepositorioDeConfianca
import java.io.File

/**
 * O `known_hosts` em `filesDir` (formato OpenSSH, **fora do backup**, CMP-19). Cada operação lê o arquivo, aplica a mudança pura de
 * [KnownHostsArquivo] e grava de forma atômica (arquivo temporário + renomear): uma queda no meio não deixa o arquivo pela metade.
 * Linhas que o app não entende voltam como estavam.
 */
class RepositorioKnownHostsArquivo(private val arquivo: File) : RepositorioDeConfianca {
    private val trava = Any()

    private fun ler(): KnownHostsArquivo = KnownHostsArquivo.ler(if (arquivo.isFile) arquivo.readText() else null)

    private fun gravar(novo: KnownHostsArquivo) {
        arquivo.parentFile?.mkdirs()
        val tmp = File(arquivo.parentFile, arquivo.name + ".tmp")
        tmp.writeText(novo.serializar())
        check(tmp.renameTo(arquivo) || (arquivo.delete() && tmp.renameTo(arquivo))) { "não consegui gravar o known_hosts" }
    }

    override fun chavesConhecidas(host: String, porta: Int): List<ChaveDoServidor> = synchronized(trava) { ler().chavesConhecidas(host, porta) }

    override fun confiar(host: String, porta: Int, chave: ChaveDoServidor) = synchronized(trava) { gravar(ler().comChave(host, porta, chave)) }

    override fun esquecer(host: String, porta: Int) = synchronized(trava) { gravar(ler().semHost(host, porta)) }
}
