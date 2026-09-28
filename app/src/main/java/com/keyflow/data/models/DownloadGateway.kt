package com.keyflow.data.models

import java.io.File

/** Estado de um pedido de download (lógica testável na JVM). */
enum class RowStatus { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED }

/** Por que um pedido está pausado — só o que muda a mensagem na UI. */
enum class PauseReason { WAITING_FOR_NETWORK, WAITING_FOR_WIFI, WAITING_TO_RETRY, OTHER }

data class DownloadRow(
    val id: Long,
    val status: RowStatus,
    val bytesSoFar: Long,
    val pauseReason: PauseReason? = null,
)

/**
 * Quem baixa os arquivos (G6): o [ModelDownloader] só fala com isto, e o teste troca por um fake. Era o
 * DownloadManager do sistema; desde 2026-09-27 é a fila do app, [HttpDownloadGateway] (ver lá o porquê).
 * Um pedido desconhecido (removido) volta como FAILED.
 */
interface DownloadGateway {
    fun enqueue(url: String, destination: File, title: String, allowMetered: Boolean): Long
    fun query(ids: List<Long>): Map<Long, DownloadRow>
    fun remove(ids: List<Long>)
}
