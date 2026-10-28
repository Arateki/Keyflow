package com.keyflow.bancada.armazenamento

import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.ExecucaoInvalidaException
import com.keyflow.bancada.resultado.ExecucaoJson
import com.keyflow.bancada.resultado.MotivoIncompleta
import com.keyflow.bancada.resultado.epochDeIso
import java.io.File
import java.io.FileOutputStream

/**
 * O histórico local da bancada (BHS-01/03/06, BEX-10): uma execução por arquivo JSON em [diretorio] (`filesDir/bancada/execucoes/`,
 * fora do backup), gravada de forma **atômica** (arquivo `.tmp` + `rename`: quem morre no meio da escrita deixa o arquivo anterior intacto).
 *
 * - **Retenção**: até [maximoCompletasPorModelo] execuções completas por modelo+backend (as mais antigas saem); execução `incompleta` com
 *   mais de [diasDaIncompleta] dias sai também.
 * - **Recuperação**: uma execução que ficou `em-andamento` no disco sem ninguém rodando (o processo morreu) é regravada como
 *   `incompleta`/`INTERROMPIDA` ([recuperarInterrompidas]).
 *
 * Não conhece UI nem motor. [redigir] é o `SecretFilter` do app: o texto livre passa por ele ao gravar (BON-03).
 */
class RepositorioDeExecucoes(
    private val diretorio: File,
    private val agora: () -> Long = System::currentTimeMillis,
    private val redigir: (String) -> String = { it },
    private val maximoCompletasPorModelo: Int = MAXIMO_POR_MODELO,
    private val diasDaIncompleta: Int = DIAS_DA_INCOMPLETA,
) {
    /** Grava (ou regrava) [execucao]. Nas execuções que terminaram, aplica a retenção. */
    @Synchronized
    fun gravar(execucao: Execucao) {
        diretorio.mkdirs()
        val destino = arquivoDe(execucao)
        val temporario = File(diretorio, destino.name + SUFIXO_TEMPORARIO)
        val bytes = ExecucaoJson.escrever(execucao, redigir).toByteArray(Charsets.UTF_8)
        FileOutputStream(temporario).use { saida ->
            saida.write(bytes)
            saida.fd.sync()
        }
        if (!temporario.renameTo(destino)) {
            temporario.delete()
            throw java.io.IOException("não consegui gravar ${destino.name}")
        }
        if (execucao.estado != EstadoExecucao.EM_ANDAMENTO) aplicarRetencao()
    }

    /** As execuções gravadas, da mais recente para a mais antiga; filtra por modelo e backend quando pedidos. Arquivo ilegível é ignorado. */
    @Synchronized
    fun listar(modeloId: String? = null, backend: String? = null): List<Execucao> =
        arquivos().mapNotNull(::ler)
            .filter { (modeloId == null || it.modelo.id == modeloId) && (backend == null || it.modelo.backend == backend) }
            .sortedWith(compareByDescending<Execucao> { it.iniciada }.thenByDescending { it.id })

    @Synchronized
    fun lerPorId(id: String): Execucao? = arquivos().mapNotNull(::ler).firstOrNull { it.id == id }

    /** Apaga [id]; `true` se havia. */
    @Synchronized
    fun apagar(id: String): Boolean {
        val arquivo = arquivos().firstOrNull { ler(it)?.id == id } ?: return false
        return arquivo.delete()
    }

    /** "Apagar histórico" (BHS-03): tudo, ou só o de [modeloId]. Devolve quantas apagou. */
    @Synchronized
    fun apagarHistorico(modeloId: String? = null): Int =
        arquivos().count { arquivo -> (modeloId == null || ler(arquivo)?.modelo?.id == modeloId) && arquivo.delete() }

    /**
     * Regrava como `incompleta` o que ficou `em-andamento` sem estar em [ativas] (os ids de execuções que ESTE processo ainda roda), e apaga
     * os `.tmp` órfãos de uma escrita interrompida. Só olha o que começou antes de [antesDeMs] (o instante em que este processo nasceu): uma
     * execução que o processo atual acabou de começar nunca é "interrompida". Devolve quantas execuções recuperou.
     */
    @Synchronized
    fun recuperarInterrompidas(ativas: Set<String> = emptySet(), antesDeMs: Long = Long.MAX_VALUE): Int {
        diretorio.listFiles { f -> f.name.endsWith(SUFIXO_TEMPORARIO) }?.forEach { it.delete() }
        var recuperadas = 0
        for (arquivo in arquivos()) {
            val execucao = ler(arquivo) ?: continue
            val comecou = epochDeIso(execucao.iniciada) ?: 0L
            if (execucao.estado == EstadoExecucao.EM_ANDAMENTO && execucao.id !in ativas && comecou < antesDeMs) {
                gravar(execucao.copy(estado = EstadoExecucao.INCOMPLETA, motivo = MotivoIncompleta.INTERROMPIDA))
                recuperadas++
            }
        }
        return recuperadas
    }

    /** Mantém as [maximoCompletasPorModelo] completas mais recentes de cada modelo+backend e tira as incompletas velhas. */
    @Synchronized
    fun aplicarRetencao() {
        val todas = arquivos().mapNotNull { arquivo -> ler(arquivo)?.let { arquivo to it } }
        val limite = agora() - diasDaIncompleta * DIA_MS
        for ((arquivo, execucao) in todas) {
            if (execucao.estado == EstadoExecucao.INCOMPLETA && (epochDeIso(execucao.iniciada) ?: Long.MAX_VALUE) < limite) arquivo.delete()
        }
        todas.filter { it.second.estado == EstadoExecucao.COMPLETA }
            .groupBy { it.second.modelo.id to it.second.modelo.backend }
            .values
            .forEach { grupo ->
                grupo.sortedWith(compareByDescending<Pair<File, Execucao>> { it.second.iniciada }.thenByDescending { it.second.id })
                    .drop(maximoCompletasPorModelo)
                    .forEach { it.first.delete() }
            }
    }

    private fun arquivos(): List<File> =
        diretorio.listFiles { f -> f.isFile && f.name.endsWith(SUFIXO) }?.sortedBy { it.name }.orEmpty()

    private fun ler(arquivo: File): Execucao? = try {
        ExecucaoJson.ler(arquivo.readText(Charsets.UTF_8))
    } catch (e: ExecucaoInvalidaException) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    private fun arquivoDe(e: Execucao): File {
        val carimbo = e.iniciada.replace("-", "").replace(":", "").removeSuffix("Z")
        return File(diretorio, "$carimbo-${seguro(e.modelo.id)}-${seguro(e.modelo.backend.lowercase())}-${seguro(e.id)}$SUFIXO")
    }

    private fun seguro(nome: String): String = nome.map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '_' }.joinToString("").take(64)

    companion object {
        const val MAXIMO_POR_MODELO = 20
        const val DIAS_DA_INCOMPLETA = 7
        private const val DIA_MS = 24L * 60 * 60 * 1000
        private const val SUFIXO = ".json"
        private const val SUFIXO_TEMPORARIO = ".tmp"

        /** A pasta do histórico dentro de `filesDir`. */
        fun pasta(filesDir: File): File = File(filesDir, "bancada/execucoes")
    }
}
