package com.keyflow.data.models

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Resposta ao pedido de baixar um modelo — o que a UI precisa para agir ou avisar. */
sealed interface StartResult {
    data object Started : StartResult
    data object AlreadyInstalled : StartResult
    /** DLM-06: um por vez. */
    data class AnotherDownloadActive(val modelId: String) : StartResult
    /** DLM-04: em rede medida, a UI confirma com o tamanho e chama de novo com `allowMetered = true`. */
    data class NeedsMeteredConfirmation(val bytes: Long) : StartResult
    /** DLM-05. */
    data class NotEnoughSpace(val requiredBytes: Long, val freeBytes: Long) : StartResult
    /** Armazenamento externo do app indisponível — o DownloadManager só grava lá. */
    data object StorageUnavailable : StartResult
}

enum class DownloadState { QUEUED, RUNNING, WAITING_FOR_NETWORK, WAITING_FOR_WIFI, FILES_DONE, FAILED }

data class DownloadProgress(
    val modelId: String,
    val state: DownloadState,
    val bytesDone: Long,
    val totalBytes: Long,
) {
    val fraction: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** Tamanho e espaço livre de volumes — trocável em teste. */
interface VolumeStats {
    fun freeBytes(dir: File): Long
    fun sameVolume(a: File, b: File): Boolean

    object Files : VolumeStats {
        override fun freeBytes(dir: File): Long = dir.also { it.mkdirs() }.usableSpace

        // Mesmo volume ⇔ mesmo tamanho total: é o caso do armazenamento "externo" emulado, que no
        // Android é uma pasta da mesma partição /data (quase todo aparelho). Cartão SD adotado diverge.
        override fun sameVolume(a: File, b: File): Boolean {
            a.mkdirs()
            b.mkdirs()
            return a.totalSpace == b.totalSpace
        }
    }
}

/** Decisões puras do downloader (DLM-04/05/06) — testáveis sem Android. */
object DownloadPolicy {

    /**
     * Espaço exigido para baixar um modelo de [totalBytes]. O DownloadManager só grava no armazenamento
     * externo do app e o modelo depois é **copiado** para o interno: no mesmo volume ele existe duas
     * vezes por um instante — daí 2,2×. Em volumes separados, 1,2× em cada um (DLM-05).
     */
    fun requiredBytes(totalBytes: Long, sameVolume: Boolean): Long =
        if (sameVolume) totalBytes * 22 / 10 else totalBytes * 12 / 10

    fun checkStart(
        spec: ModelSpec,
        installed: Boolean,
        activeModelId: String?,
        metered: Boolean,
        allowMetered: Boolean,
        freeInternal: Long,
        freeTmp: Long,
        sameVolume: Boolean,
    ): StartResult? {
        if (installed) return StartResult.AlreadyInstalled
        if (activeModelId != null) return StartResult.AnotherDownloadActive(activeModelId)
        val required = requiredBytes(spec.totalBytes, sameVolume)
        val free = if (sameVolume) freeInternal else minOf(freeInternal, freeTmp)
        if (free < required) return StartResult.NotEnoughSpace(required, free)
        if (metered && !allowMetered) return StartResult.NeedsMeteredConfirmation(spec.totalBytes)
        return null
    }

    /** Soma o progresso dos arquivos (DLM-01): arquivo concluído conta inteiro; falha em um = falha no modelo. */
    fun aggregate(spec: ModelSpec, rowsByFile: Map<String, DownloadRow>): DownloadProgress {
        val rows = spec.files.map { rowsByFile[it.fileName] }
        val bytesDone = spec.files.zip(rows).sumOf { (file, row) ->
            when (row?.status) {
                RowStatus.SUCCESSFUL -> file.bytes
                null -> 0L
                else -> row.bytesSoFar.coerceIn(0, file.bytes)
            }
        }
        val statuses = rows.map { it?.status ?: RowStatus.FAILED }
        val reasons = rows.mapNotNull { it?.pauseReason }
        val state = when {
            RowStatus.FAILED in statuses -> DownloadState.FAILED
            statuses.all { it == RowStatus.SUCCESSFUL } -> DownloadState.FILES_DONE
            RowStatus.RUNNING in statuses -> DownloadState.RUNNING
            PauseReason.WAITING_FOR_WIFI in reasons -> DownloadState.WAITING_FOR_WIFI
            PauseReason.WAITING_FOR_NETWORK in reasons -> DownloadState.WAITING_FOR_NETWORK
            else -> DownloadState.QUEUED
        }
        return DownloadProgress(spec.id, state, bytesDone, spec.totalBytes)
    }
}

/**
 * Baixa modelos do catálogo pelo DownloadManager do sistema (G6, NM-T6): um pedido por arquivo para
 * `externalFilesDir/model-tmp/<id>/`, com os ids dos pedidos em `downloads.json` — é esse arquivo que
 * sobrevive à morte do app e diz que há um download em curso. Conclusão, SHA e instalação: NM-T7.
 */
class ModelDownloader(
    private val gateway: DownloadGateway,
    private val tmpRoot: File?,
    private val store: ModelStore,
    private val isMetered: () -> Boolean,
    private val volumes: VolumeStats = VolumeStats.Files,
    private val catalog: (String) -> ModelSpec? = ModelCatalog::byId,
    private val pollMs: Long = 500,
) {

    /** Modelo com download em curso (há `downloads.json`), se houver. */
    fun activeModelId(): String? =
        tmpRoot?.listFiles { f -> f.isDirectory && File(f, RECORD).isFile }?.firstOrNull()?.name

    fun start(spec: ModelSpec, allowMetered: Boolean): StartResult {
        val root = tmpRoot ?: return StartResult.StorageUnavailable
        val modelsRoot = store.dirFor(spec.id).parentFile!!
        val blocked = DownloadPolicy.checkStart(
            spec = spec,
            installed = store.isInstalled(spec.id),
            activeModelId = activeModelId(),
            metered = isMetered(),
            allowMetered = allowMetered,
            freeInternal = volumes.freeBytes(modelsRoot),
            freeTmp = volumes.freeBytes(root),
            sameVolume = volumes.sameVolume(root, modelsRoot),
        )
        if (blocked != null) return blocked

        val dir = File(root, spec.id)
        dir.deleteRecursively()
        dir.mkdirs()
        val enqueued = mutableMapOf<String, Long>()
        try {
            spec.files.forEachIndexed { index, file ->
                enqueued[file.fileName] = gateway.enqueue(
                    url = file.url,
                    destination = File(dir, file.fileName),
                    title = "KeyFlow · ${spec.displayName} (${index + 1}/${spec.files.size})",
                    allowMetered = allowMetered,
                )
            }
            writeRecord(dir, spec.id, enqueued)
        } catch (e: Exception) {
            gateway.remove(enqueued.values.toList())
            dir.deleteRecursively()
            throw e
        }
        return StartResult.Started
    }

    /** Estado atual do download de [modelId]; null se não há download dele em curso. */
    fun snapshot(modelId: String): DownloadProgress? {
        val spec = catalog(modelId) ?: return null
        val record = readRecord(modelId) ?: return null
        val rows = gateway.query(record.values.toList())
        return DownloadPolicy.aggregate(spec, record.mapValues { (_, id) -> rows.getValue(id) })
    }

    /** Polling do [snapshot] enquanto houver coletor (G6 — o DownloadManager não empurra progresso). */
    fun progress(modelId: String): Flow<DownloadProgress?> = flow {
        while (true) {
            emit(snapshot(modelId))
            delay(pollMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** DLM-08: tira os pedidos do sistema e apaga os parciais. */
    fun cancel(modelId: String) {
        readRecord(modelId)?.let { gateway.remove(it.values.toList()) }
        tmpRoot?.let { File(it, modelId).deleteRecursively() }
    }

    /** Arquivos baixados de [modelId] (nomes do catálogo), para a verificação e instalação da NM-T7. */
    internal fun tmpDirFor(modelId: String): File? = tmpRoot?.let { File(it, modelId) }

    /** `nome do arquivo → id do pedido` gravado no início do download. */
    internal fun readRecord(modelId: String): Map<String, Long>? {
        val file = tmpRoot?.let { File(it, "$modelId/$RECORD") } ?: return null
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        val files = json.optJSONArray("files") ?: return null
        return (0 until files.length()).associate { i ->
            val entry = files.getJSONObject(i)
            entry.getString("name") to entry.getLong("requestId")
        }
    }

    private fun writeRecord(dir: File, modelId: String, requests: Map<String, Long>) {
        val json = JSONObject()
            .put("modelId", modelId)
            .put(
                "files",
                JSONArray().apply {
                    requests.forEach { (name, id) -> put(JSONObject().put("name", name).put("requestId", id)) }
                },
            )
        File(dir, RECORD).writeText(json.toString())
    }

    companion object {
        const val TMP_DIR = "model-tmp"
        const val RECORD = "downloads.json"

        fun create(context: Context, store: ModelStore): ModelDownloader {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return ModelDownloader(
                gateway = SystemDownloadGateway(context),
                tmpRoot = context.getExternalFilesDir(null)?.let { File(it, TMP_DIR) },
                store = store,
                isMetered = { connectivity.isActiveNetworkMetered },
            )
        }
    }
}
