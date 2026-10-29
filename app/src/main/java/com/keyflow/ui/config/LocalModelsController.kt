package com.keyflow.ui.config

import android.app.ActivityManager
import android.content.Context
import com.keyflow.core.ComputeBackend
import com.keyflow.data.EffectiveConfig
import com.keyflow.data.LocalBackendPrefs
import com.keyflow.data.SettingsRepository
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelKind
import com.keyflow.reasoning.local.ComputeSupport
import com.keyflow.reasoning.local.LocalReasoningPreloader
import com.keyflow.transcription.local.LocalTranscriber
import com.keyflow.data.models.DownloadFailure
import com.keyflow.data.models.DownloadProgress
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelDownloader
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelStore
import com.keyflow.data.models.StartResult
import com.keyflow.data.models.VoiceLicenseAcceptance
import com.keyflow.data.models.VoiceLicenseGate
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
    /** Instalados de uma versão que o catálogo já não é (ver [ModelStore.outdatedIds]): o card oferece atualizar. */
    val outdated: Set<String> = emptySet(),
    /** Onde cada modelo de texto roda (CPU/GPU), o aviso de volta para a CPU e a GPU já pronta — ver [LocalBackendPrefs]. */
    val backends: LocalBackendPrefs = LocalBackendPrefs.EMPTY,
    /** O aparelho oferece a GPU (a `libOpenCL.so` do fabricante está lá) — ver [ComputeSupport]. */
    val gpuAvailable: Boolean = false,
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
    private val settings = SettingsRepository(app)
    private val voiceLicenses = VoiceLicenseAcceptance(java.io.File(app.filesDir, "voice-license-accepted.txt"))

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
        combine(
            store.installedIds, activeProgress(), downloader.failures, store.outdatedIds, settings.localBackendPrefsFlow(),
        ) { installed, active, failures, outdated, backends ->
            // A 1ª leitura do `stat` da libOpenCL.so é disco: este bloco roda no IO (flowOn abaixo), não na principal.
            LocalModelsSnapshot(installed, active, failures, outdated, backends, ComputeSupport.device.gpuAvailable)
        }.flowOn(Dispatchers.IO)

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
        VoiceLicenseGate.begin(spec.id, voiceLicenses.isAccepted(spec.id)) { downloader.start(spec, allowMetered) }

    override fun acceptVoiceLicense(modelId: String) {
        voiceLicenses.accept(modelId)
    }

    override fun prepare(modelId: String, language: String?) {
        io.launch {
            if (!store.isInstalled(modelId)) return@launch
            when (ModelCatalog.byId(modelId)?.kind) {
                ModelKind.SPEECH -> LocalTranscriber.get(app).sync(EffectiveConfig.Local(modelId, language))
                ModelKind.TEXT -> LocalReasoningPreloader.select(app, modelId)
                else -> Unit
            }
        }
    }

    override fun chooseBackend(modelId: String, backend: ComputeBackend, prepare: Boolean) {
        io.launch {
            // Grava antes de preparar: a pré-carga lê a preferência do DataStore.
            settings.updateLocalBackendPrefs { it.choose(modelId, backend) }
            if (prepare && store.isInstalled(modelId)) LocalReasoningPreloader.select(app, modelId)
        }
    }

    override fun cancel(modelId: String) {
        io.launch { downloader.cancel(modelId) }
    }

    override fun delete(modelId: String) {
        io.launch {
            store.delete(modelId)
            // Uma cópia nova do modelo não herda a escolha, o aviso nem a "GPU pronta" da antiga.
            settings.updateLocalBackendPrefs { it.forget(modelId) }
        }
    }

    private companion object {
        const val POLL_MS = 500L
    }
}
