package com.keyflow.ui.config

import android.app.ActivityManager
import android.content.Context
import com.keyflow.data.models.DownloadFailure
import com.keyflow.data.models.DownloadProgress
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelDownloader
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelStore
import com.keyflow.data.models.StartResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** O que o painel Local precisa saber do disco e da fila de downloads, num instante. */
data class LocalModelsSnapshot(
    val installed: Set<String>,
    val active: DownloadProgress?,
    val failures: Map<String, DownloadFailure>,
) {
    companion object {
        val EMPTY = LocalModelsSnapshot(emptySet(), null, emptyMap())
    }
}

/**
 * O gerenciador de modelos de verdade para a tela de config (NM-T10): [ModelStore] e [ModelDownloader]
 * do processo por trás das [LocalModelActions] do painel.
 */
class LocalModelsController(context: Context) : LocalModelActions {

    private val app = context.applicationContext
    private val store = ModelStore.get(app)
    private val downloader = ModelDownloader.get(app)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** RAM total do aparelho, para o aviso de peso (CAT-03). */
    val deviceRamMb: Long = ActivityManager.MemoryInfo()
        .also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        .totalMem / (1024 * 1024)

    /**
     * Enquanto coletado (tela visível): reconcilia uma vez ao abrir (DLM-07) e acompanha instalados,
     * progresso (polling de 500 ms, G6) e falhas. Download que terminou é fechado aqui mesmo — não
     * depende do broadcast chegar.
     */
    fun snapshots(): Flow<LocalModelsSnapshot> =
        combine(store.installedIds, activeProgress(), downloader.failures, ::LocalModelsSnapshot)

    private fun activeProgress(): Flow<DownloadProgress?> = flow {
        downloader.reconcile()
        while (true) {
            val progress = downloader.activeModelId()?.let(downloader::snapshot)
            emit(progress)
            if (progress?.state == DownloadState.FILES_DONE || progress?.state == DownloadState.FAILED) {
                downloader.finish(progress.modelId)
            } else {
                delay(POLL_MS)
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult =
        downloader.start(spec, allowMetered)

    override fun cancel(modelId: String) {
        io.launch { downloader.cancel(modelId) }
    }

    override fun delete(modelId: String) {
        io.launch { store.delete(modelId) }
    }

    private companion object {
        const val POLL_MS = 500L
    }
}
