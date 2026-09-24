package com.keyflow.data.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/** Estado de um pedido no DownloadManager, já sem as constantes do Android (lógica testável na JVM). */
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
 * Camada fina sobre o [DownloadManager] (G6): o [ModelDownloader] só fala com isto, e o teste troca por
 * um fake. Um pedido que sumiu do sistema (o usuário limpou pela tela de downloads) volta como FAILED.
 */
interface DownloadGateway {
    fun enqueue(url: String, destination: File, title: String, allowMetered: Boolean): Long
    fun query(ids: List<Long>): Map<Long, DownloadRow>
    fun remove(ids: List<Long>)
}

class SystemDownloadGateway(context: Context) : DownloadGateway {

    private val manager = context.getSystemService(DownloadManager::class.java)

    override fun enqueue(url: String, destination: File, title: String, allowMetered: Boolean): Long {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverMetered(allowMetered)
            .setAllowedOverRoaming(allowMetered)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        return manager.enqueue(request)
    }

    override fun query(ids: List<Long>): Map<Long, DownloadRow> {
        if (ids.isEmpty()) return emptyMap()
        val rows = mutableMapOf<Long, DownloadRow>()
        manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val bytesCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val reasonCol = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            while (cursor.moveToNext()) {
                val status = statusOf(cursor.getInt(statusCol))
                rows[cursor.getLong(idCol)] = DownloadRow(
                    id = cursor.getLong(idCol),
                    status = status,
                    bytesSoFar = cursor.getLong(bytesCol).coerceAtLeast(0),
                    pauseReason = if (status == RowStatus.PAUSED) pauseReasonOf(cursor.getInt(reasonCol)) else null,
                )
            }
        }
        return ids.associateWith { id -> rows[id] ?: DownloadRow(id, RowStatus.FAILED, 0) }
    }

    override fun remove(ids: List<Long>) {
        if (ids.isNotEmpty()) manager.remove(*ids.toLongArray())
    }

    private fun statusOf(status: Int): RowStatus = when (status) {
        DownloadManager.STATUS_PENDING -> RowStatus.PENDING
        DownloadManager.STATUS_RUNNING -> RowStatus.RUNNING
        DownloadManager.STATUS_PAUSED -> RowStatus.PAUSED
        DownloadManager.STATUS_SUCCESSFUL -> RowStatus.SUCCESSFUL
        else -> RowStatus.FAILED
    }

    private fun pauseReasonOf(reason: Int): PauseReason = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> PauseReason.WAITING_FOR_NETWORK
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> PauseReason.WAITING_FOR_WIFI
        DownloadManager.PAUSED_WAITING_TO_RETRY -> PauseReason.WAITING_TO_RETRY
        else -> PauseReason.OTHER
    }
}
