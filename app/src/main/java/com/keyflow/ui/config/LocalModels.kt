package com.keyflow.ui.config

import com.keyflow.data.models.DownloadFailure
import com.keyflow.data.models.DownloadProgress
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.StartResult
import kotlin.math.roundToInt

/** Em que pé está cada modelo do catálogo, do ponto de vista do painel (MGR-02). */
enum class LocalModelStatus { AVAILABLE, DOWNLOADING, INSTALLED }

data class LocalModelItem(
    val spec: ModelSpec,
    val status: LocalModelStatus,
    /** Só um modelo instalado pode estar selecionado. */
    val selected: Boolean,
    /** Só quando [status] é DOWNLOADING. */
    val progress: DownloadProgress? = null,
    /** CAT-03: RAM total do aparelho abaixo da recomendada — aviso, não bloqueio. */
    val heavy: Boolean = false,
    /** Último download que falhou (DLM-02) — some ao tentar de novo. */
    val failure: DownloadFailure? = null,
)

/** Tudo que o painel Local mostra. Vazio = catálogo sem nada baixado nem em curso. */
data class LocalModelsUi(
    val items: List<LocalModelItem>,
) {
    /** DLM-06: com um download em curso, os outros "Baixar" ficam indisponíveis. */
    val downloadActive: Boolean get() = items.any { it.status == LocalModelStatus.DOWNLOADING }

    companion object {
        /**
         * Catálogo × instalados × download em curso × falhas × seleção → estado da lista (função pura,
         * NM-T10). A seleção só vale para modelo instalado: selecionado e apagado aparece como disponível.
         */
        fun compose(
            catalog: List<ModelSpec> = ModelCatalog.MODELS,
            installed: Set<String>,
            active: DownloadProgress?,
            failures: Map<String, DownloadFailure>,
            selectedId: String?,
            deviceRamMb: Long,
        ): LocalModelsUi = LocalModelsUi(
            catalog.map { spec ->
                val downloading = active?.modelId == spec.id && active.state != DownloadState.FAILED
                val status = when {
                    spec.id in installed -> LocalModelStatus.INSTALLED
                    downloading -> LocalModelStatus.DOWNLOADING
                    else -> LocalModelStatus.AVAILABLE
                }
                LocalModelItem(
                    spec = spec,
                    status = status,
                    selected = status == LocalModelStatus.INSTALLED && spec.id == selectedId,
                    progress = active.takeIf { status == LocalModelStatus.DOWNLOADING },
                    heavy = deviceRamMb in 1 until spec.recommendedRamMb,
                    failure = failures[spec.id].takeIf { status == LocalModelStatus.AVAILABLE },
                )
            },
        )

        val EMPTY: LocalModelsUi = compose(installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 0)
    }
}

/** O que o painel pede ao gerenciador de modelos. Seleção e idioma vão pelas Settings, não por aqui. */
interface LocalModelActions {
    /** Pode ler disco e falar com o DownloadManager: chamar fora da main thread. */
    suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult
    fun cancel(modelId: String)
    fun delete(modelId: String)

    /** Sem gerenciador (galeria, testes de outras abas): nada acontece. */
    object None : LocalModelActions {
        override suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult = StartResult.StorageUnavailable
        override fun cancel(modelId: String) = Unit
        override fun delete(modelId: String) = Unit
    }
}

/** "~153 MB" — MiB arredondado, como o gerenciador de arquivos do Android mostra. */
fun formatModelSize(bytes: Long): String = "~${(bytes / 1_048_576.0).roundToInt()} MB"

/** Mensagem de uma pré-checagem que impediu o download (DLM-05/06); null quando não há o que dizer. */
fun startProblem(result: StartResult): String? = when (result) {
    is StartResult.NotEnoughSpace ->
        "Espaço insuficiente: precisa de ${formatModelSize(result.requiredBytes)} livres, há ${formatModelSize(result.freeBytes)}."
    is StartResult.AnotherDownloadActive -> "Espere o download em andamento terminar."
    StartResult.StorageUnavailable -> "Armazenamento indisponível para baixar agora."
    StartResult.Started, StartResult.AlreadyInstalled, is StartResult.NeedsMeteredConfirmation -> null
}

fun failureMessage(failure: DownloadFailure): String = when (failure) {
    DownloadFailure.DOWNLOAD_FAILED -> "O download falhou — tente novamente."
    DownloadFailure.CORRUPTED -> "Download corrompido — tente novamente."
    DownloadFailure.INSTALL_FAILED -> "Não foi possível instalar — verifique o espaço e tente novamente."
}

fun downloadStateLabel(progress: DownloadProgress): String = when (progress.state) {
    DownloadState.QUEUED -> "Na fila"
    DownloadState.WAITING_FOR_NETWORK -> "Aguardando rede"
    DownloadState.WAITING_FOR_WIFI -> "Aguardando Wi-Fi"
    DownloadState.FILES_DONE -> "Verificando"
    DownloadState.RUNNING, DownloadState.FAILED -> "${(progress.fraction * 100).toInt()}%"
}
