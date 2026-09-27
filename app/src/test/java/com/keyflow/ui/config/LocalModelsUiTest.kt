package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.data.models.DownloadFailure
import com.keyflow.data.models.DownloadProgress
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.StartResult
import org.junit.Test

/** NM-T10: a composição pura do estado do painel Local e os textos que dependem dele. */
class LocalModelsUiTest {

    private val base = ModelCatalog.WHISPER_BASE.id
    private val small = ModelCatalog.WHISPER_SMALL.id
    private val parakeet = ModelCatalog.PARAKEET_V3.id

    private fun ui(
        installed: Set<String> = emptySet(),
        active: DownloadProgress? = null,
        failures: Map<String, DownloadFailure> = emptyMap(),
        selected: String? = null,
        ramMb: Long = 8_000,
    ) = LocalModelsUi.compose(installed = installed, active = active, failures = failures, selectedId = selected, deviceRamMb = ramMb)

    private fun LocalModelsUi.item(id: String) = items.single { it.spec.id == id }

    @Test
    fun `nothing downloaded lists the whole catalog as available`() {
        val ui = ui()

        assertThat(ui.items.map { it.spec.id }).containsExactly(base, small, parakeet).inOrder()
        assertThat(ui.items.map { it.status }.toSet()).containsExactly(LocalModelStatus.AVAILABLE)
        assertThat(ui.items.none { it.selected }).isTrue()
        assertThat(ui.downloadActive).isFalse()
        assertThat(LocalModelsUi.EMPTY).isEqualTo(ui(ramMb = 0))
    }

    @Test
    fun `installed and selected`() {
        val ui = ui(installed = setOf(base, small), selected = small)

        assertThat(ui.item(base).status).isEqualTo(LocalModelStatus.INSTALLED)
        assertThat(ui.item(base).selected).isFalse()
        assertThat(ui.item(small).selected).isTrue()
    }

    @Test
    fun `a selection whose model is gone is not shown as selected`() {
        assertThat(ui(selected = base).item(base).selected).isFalse()
    }

    @Test
    fun `the active download carries its progress and blocks the other downloads`() {
        val progress = DownloadProgress(small, DownloadState.RUNNING, 100, 400)
        val ui = ui(active = progress)

        assertThat(ui.item(small).status).isEqualTo(LocalModelStatus.DOWNLOADING)
        assertThat(ui.item(small).progress).isEqualTo(progress)
        assertThat(ui.item(base).progress).isNull()
        assertThat(ui.downloadActive).isTrue()
    }

    @Test
    fun `a failed download is not downloading and shows its failure`() {
        val ui = ui(
            active = DownloadProgress(small, DownloadState.FAILED, 0, 400),
            failures = mapOf(small to DownloadFailure.CORRUPTED, base to DownloadFailure.CORRUPTED),
            installed = setOf(base),
        )

        assertThat(ui.item(small).status).isEqualTo(LocalModelStatus.AVAILABLE)
        assertThat(ui.item(small).failure).isEqualTo(DownloadFailure.CORRUPTED)
        // Instalado depois da falha: a falha antiga não aparece mais.
        assertThat(ui.item(base).failure).isNull()
    }

    @Test
    fun `heavy warning when the device has less ram than recommended, never when unknown`() {
        val ui = ui(ramMb = 1_500)

        assertThat(ui.item(base).heavy).isFalse()
        assertThat(ui.item(small).heavy).isFalse()
        assertThat(ui.item(parakeet).heavy).isTrue()
        assertThat(ui(ramMb = 0).items.none { it.heavy }).isTrue()
    }

    @Test
    fun `sizes read like the file manager`() {
        assertThat(formatModelSize(ModelCatalog.WHISPER_BASE.totalBytes)).isEqualTo("~153 MB")
        assertThat(formatModelSize(ModelCatalog.PARAKEET_V3.totalBytes)).isEqualTo("~639 MB")
    }

    @Test
    fun `start problems become messages and the rest stays quiet`() {
        assertThat(startProblem(StartResult.NotEnoughSpace(2_097_152, 1_048_576)))
            .isEqualTo("Espaço insuficiente: precisa de ~2 MB livres, há ~1 MB.")
        assertThat(startProblem(StartResult.AnotherDownloadActive(base))).isNotNull()
        assertThat(startProblem(StartResult.StorageUnavailable)).isNotNull()
        assertThat(startProblem(StartResult.Started)).isNull()
        assertThat(startProblem(StartResult.NeedsMeteredConfirmation(1))).isNull()
    }

    @Test
    fun `progress labels`() {
        assertThat(downloadStateLabel(DownloadProgress(base, DownloadState.RUNNING, 1, 4))).isEqualTo("25%")
        assertThat(downloadStateLabel(DownloadProgress(base, DownloadState.WAITING_FOR_NETWORK, 1, 4))).isEqualTo("Aguardando rede")
        assertThat(downloadStateLabel(DownloadProgress(base, DownloadState.FILES_DONE, 4, 4))).isEqualTo("Verificando")
        DownloadFailure.entries.forEach { assertThat(failureMessage(it)).isNotEmpty() }
    }
}
