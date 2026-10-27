package com.keyflow.ui.config

import com.keyflow.i18n.tr
import com.keyflow.data.models.DownloadFailure
import com.keyflow.data.models.DownloadProgress
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelPreparation
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
    /** Carga e aquecimento reais, somente no card selecionado e instalado. */
    val preparation: ModelPreparation? = null,
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
            catalog: List<ModelSpec> = ModelCatalog.SPEECH,
            installed: Set<String>,
            active: DownloadProgress?,
            failures: Map<String, DownloadFailure>,
            selectedId: String?,
            deviceRamMb: Long,
            preparation: ModelPreparation? = null,
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
                    preparation = preparation?.takeIf { status == LocalModelStatus.INSTALLED && spec.id == selectedId && it.modelId == spec.id },
                )
            },
        )

        val EMPTY: LocalModelsUi = compose(installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 0)

        /** O vazio do Raciocínio: lista os modelos de TEXTO. O [EMPTY] acima é o da Transcrição (fala). */
        val EMPTY_TEXT: LocalModelsUi = compose(
            catalog = ModelCatalog.TEXT, installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 0,
        )

        /** O vazio da Visão: lista os modelos de VISÃO/OCR. */
        val EMPTY_VISION: LocalModelsUi = compose(
            catalog = ModelCatalog.VISION, installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 0,
        )

        /** O vazio da Voz (Fala): lista os modelos de VOZ/TTS. */
        val EMPTY_FALA: LocalModelsUi = compose(
            catalog = ModelCatalog.VOICE, installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 0,
        )
    }
}

/** O que o painel pede ao gerenciador de modelos. Seleção e idioma vão pelas Settings, não por aqui. */
interface LocalModelActions {
    /** Pode ler disco e falar com a fila de downloads: chamar fora da main thread. */
    suspend fun download(spec: ModelSpec, allowMetered: Boolean): StartResult
    fun cancel(modelId: String)
    fun delete(modelId: String)

    /** Seleção explícita de um instalado: preparar em segundo plano, sem trabalho de disco na UI. */
    fun prepare(modelId: String, language: String? = null) = Unit

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
        tr("Espaço insuficiente: precisa de %s livres, há %s.", formatModelSize(result.requiredBytes), formatModelSize(result.freeBytes))
    is StartResult.AnotherDownloadActive -> tr("Espere o download em andamento terminar.")
    StartResult.StorageUnavailable -> tr("Armazenamento indisponível para baixar agora.")
    StartResult.Started, StartResult.AlreadyInstalled, is StartResult.NeedsMeteredConfirmation -> null
}

fun failureMessage(failure: DownloadFailure): String = when (failure) {
    DownloadFailure.DOWNLOAD_FAILED -> tr("O download falhou — tente novamente.")
    DownloadFailure.CORRUPTED -> tr("Download corrompido — tente novamente.")
    DownloadFailure.INSTALL_FAILED -> tr("Não foi possível instalar — verifique o espaço e tente novamente.")
}

fun downloadStateLabel(progress: DownloadProgress): String = when (progress.state) {
    DownloadState.QUEUED -> tr("Na fila")
    DownloadState.WAITING_FOR_NETWORK -> tr("Aguardando rede")
    DownloadState.WAITING_FOR_WIFI -> tr("Aguardando Wi-Fi")
    DownloadState.FILES_DONE -> tr("Verificando")
    DownloadState.RUNNING, DownloadState.FAILED -> "${(progress.fraction * 100).toInt()}%"
}
