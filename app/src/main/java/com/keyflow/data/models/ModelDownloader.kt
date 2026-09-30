package com.keyflow.data.models

import android.content.Context
import android.net.ConnectivityManager
import com.keyflow.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

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
    /** Armazenamento externo do app indisponível — é lá que os downloads ficam até a instalação. */
    data object StorageUnavailable : StartResult
}

/** Por que um download terminou sem instalar — a UI mostra e oferece tentar de novo. */
enum class DownloadFailure {
    /** O download desistiu (erro HTTP definitivo, tentativas esgotadas, pedido removido). */
    DOWNLOAD_FAILED,
    /** Tamanho ou SHA256 não bate com o catálogo (DLM-02): "download corrompido, tente novamente". */
    CORRUPTED,
    /** Os arquivos conferiram, mas a cópia para o armazenamento interno falhou. */
    INSTALL_FAILED,
}

/** O que [ModelDownloader.finish] fez com um download. */
sealed interface CompletionResult {
    /** Não há download desse modelo em curso. */
    data object NotActive : CompletionResult
    data object InProgress : CompletionResult
    data object Installed : CompletionResult
    /** Pedidos removidos e tmp apagado; a falha fica em [ModelDownloader.failures]. */
    data class Failed(val failure: DownloadFailure) : CompletionResult
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
     * Espaço exigido para baixar um modelo de [totalBytes]. Os downloads ficam no armazenamento
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
 * Baixa modelos do catálogo pela fila de downloads do app (G6; até 2026-09-27 era o DownloadManager —
 * ver [HttpDownloadGateway]): um pedido por arquivo para
 * `externalFilesDir/model-tmp/<id>/`, com os ids dos pedidos em `downloads.json` — é esse arquivo que
 * sobrevive à morte do app e diz que há um download em curso (NM-T6). Quando tudo chega, [finish]
 * confere tamanho e SHA256, instala no [ModelStore] e apaga o tmp (NM-T7). Como o `downloads.json` só
 * some depois da instalação, morrer no meio de qualquer passo deixa o download para o [reconcile].
 *
 * Início, conclusão e reconciliação passam por um lock do processo: o broadcast de conclusão, a UI e a
 * reconciliação podem chegar juntos, e só um deve verificar e instalar.
 */
class ModelDownloader(
    private val gateway: DownloadGateway,
    private val tmpRoot: File?,
    private val store: ModelStore,
    private val isMetered: () -> Boolean,
    private val volumes: VolumeStats = VolumeStats.Files,
    private val catalog: (String) -> ModelSpec? = ModelCatalog::byId,
    private val pollMs: Long = 500,
    /** Chamado depois de cada instalação concluída (auto-seleção, MGR-03). Na thread de quem fechou. */
    private val onInstalled: (String) -> Unit = {},
) {

    private val _failures = MutableStateFlow<Map<String, DownloadFailure>>(emptyMap())

    /** Último download que falhou por modelo, até a UI [clearFailure] ou um novo [start]. Só em memória. */
    val failures: StateFlow<Map<String, DownloadFailure>> = _failures.asStateFlow()

    fun clearFailure(modelId: String) {
        _failures.value -= modelId
    }

    /** Modelo com download em curso (há `downloads.json`), se houver. */
    fun activeModelId(): String? =
        tmpRoot?.listFiles { f -> f.isDirectory && File(f, RECORD).isFile }?.firstOrNull()?.name

    fun start(spec: ModelSpec, allowMetered: Boolean): StartResult = synchronized(LOCK) {
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
        clearFailure(spec.id)
        return StartResult.Started
    }

    /** Estado atual do download de [modelId]; null se não há download dele em curso. */
    fun snapshot(modelId: String): DownloadProgress? {
        val spec = catalog(modelId) ?: return null
        val record = readRecord(modelId) ?: return null
        val rows = gateway.query(record.values.toList())
        return DownloadPolicy.aggregate(spec, record.mapValues { (_, id) -> rows.getValue(id) })
    }

    /** Polling do [snapshot] enquanto houver coletor (G6 — a fila não empurra progresso). */
    fun progress(modelId: String): Flow<DownloadProgress?> = flow {
        while (true) {
            emit(snapshot(modelId))
            delay(pollMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** DLM-08: tira os pedidos do sistema e apaga os parciais. */
    fun cancel(modelId: String): Unit = synchronized(LOCK) { discard(modelId) }

    /**
     * Fecha o download de [modelId] se ele terminou (DLM-02): tudo SUCCESSFUL → confere tamanho e
     * SHA256 de cada arquivo → [ModelStore.install] → tira os pedidos do sistema e apaga o tmp. Falha do
     * sistema, arquivo divergente ou instalação que não completa → descarta tudo e registra a falha.
     * Lê até centenas de MB: chamar fora da main thread.
     */
    fun finish(modelId: String): CompletionResult = synchronized(LOCK) {
        val record = readRecord(modelId) ?: return CompletionResult.NotActive
        // Modelo que saiu do catálogo numa atualização do app: não há contra o que conferir.
        val spec = catalog(modelId) ?: return fail(modelId, DownloadFailure.DOWNLOAD_FAILED)
        // Morreu entre instalar e limpar: só falta a limpeza.
        if (store.isInstalled(modelId)) {
            discard(modelId)
            return CompletionResult.Installed
        }
        val rows = gateway.query(record.values.toList())
        val progress = DownloadPolicy.aggregate(spec, record.mapValues { (_, id) -> rows.getValue(id) })
        when (progress.state) {
            DownloadState.FAILED -> return fail(modelId, DownloadFailure.DOWNLOAD_FAILED)
            DownloadState.FILES_DONE -> Unit
            else -> return CompletionResult.InProgress
        }
        val dir = tmpDirFor(modelId)!!
        val intact = spec.files.all { file ->
            val onDisk = File(dir, file.fileName)
            onDisk.isFile && onDisk.length() == file.bytes && Sha256.hexOf(onDisk) == file.sha256
        }
        if (!intact) return fail(modelId, DownloadFailure.CORRUPTED)
        try {
            store.install(spec, dir)
        } catch (e: IOException) {
            return fail(modelId, DownloadFailure.INSTALL_FAILED)
        } catch (e: IllegalStateException) {
            return fail(modelId, DownloadFailure.INSTALL_FAILED)
        }
        discard(modelId)
        onInstalled(modelId)
        return CompletionResult.Installed
    }

    /**
     * DLM-07: casa cada `downloads.json` com o estado real no sistema — instala o que concluiu com o app
     * morto, registra o que falhou, deixa o que segue baixando. Tira do caminho sobras de um início
     * interrompido (pasta sem `downloads.json`). Chamado pelo broadcast de conclusão e ao abrir o
     * gerenciador (NM-T10).
     */
    fun reconcile(): Map<String, CompletionResult> = synchronized(LOCK) {
        val dirs = tmpRoot?.listFiles { f -> f.isDirectory } ?: return emptyMap()
        dirs.filterNot { File(it, RECORD).isFile }.forEach { it.deleteRecursively() }
        return dirs.filter { File(it, RECORD).isFile }.associate { it.name to finish(it.name) }
    }

    private fun fail(modelId: String, failure: DownloadFailure): CompletionResult {
        discard(modelId)
        _failures.value += modelId to failure
        return CompletionResult.Failed(failure)
    }

    private fun discard(modelId: String) {
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

        /** Um lock por processo: vale mesmo entre instâncias (a de teste e a do app). */
        private val LOCK = Any()

        @Volatile
        private var instance: ModelDownloader? = null

        /**
         * A instância do processo, com o [ModelStore] do app — um só, para o `installedIds` que a UI
         * observa mudar quando o broadcast de conclusão instala.
         */
        fun get(context: Context): ModelDownloader = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext, ModelStore.get(context)).also { instance = it }
        }

        fun create(context: Context, store: ModelStore): ModelDownloader {
            val settings = SettingsRepository(context)
            return ModelDownloader(
                gateway = AppDownloads.gateway(context),
                tmpRoot = context.getExternalFilesDir(null)?.let { File(it, TMP_DIR) },
                store = store,
                isMetered = meteredProbe(context),
                // Já fora da main thread (finish lê centenas de MB): a escrita curta no DataStore cabe aqui.
                onInstalled = { id -> runBlocking { settings.selectOnInstall(id) } },
            )
        }

        /**
         * "A rede ativa é medida?" (DLM-04). O `ConnectivityService` exige `ACCESS_NETWORK_STATE` no
         * manifesto — sem ela, o "Baixar" derrubava o app com `SecurityException` (A32, 2026-09-27).
         */
        internal fun meteredProbe(context: Context): () -> Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return { connectivity.isActiveNetworkMetered }
        }
    }
}
