package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.ComputeBackend
import com.keyflow.data.LocalBackendPrefs
import com.keyflow.data.models.DownloadFailure
import com.keyflow.data.models.DownloadProgress
import com.keyflow.data.models.DownloadState
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.StartResult
import com.keyflow.ui.gallery.SamplePanelModels
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
    fun `an outdated install is available with the update flag, never selected`() {
        val ui = LocalModelsUi.compose(
            installed = setOf(base), outdated = setOf(small, base), active = null, failures = emptyMap(), selectedId = small, deviceRamMb = 8_000,
        )

        assertThat(ui.item(small).status).isEqualTo(LocalModelStatus.AVAILABLE)
        assertThat(ui.item(small).outdated).isTrue()
        assertThat(ui.item(small).selected).isFalse()
        // Instalado vale mais que a marca (o store nunca os dá juntos; a composição não deve mostrar "Atualizar" num instalado).
        assertThat(ui.item(base).outdated).isFalse()
        assertThat(ui.item(parakeet).outdated).isFalse()
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

    // ── o catálogo de texto (aba Raciocínio): a mesma composição, outra lista ────────────────────────────────────

    private val qwen = ModelCatalog.QWEN3_0_6B.id
    private val gemma = ModelCatalog.GEMMA_4_E2B.id
    private val smol = ModelCatalog.SMOLLM2_1_7B.id
    private val deepseek = ModelCatalog.DEEPSEEK_R1_1_5B.id
    private val gemmaE4b = ModelCatalog.GEMMA_4_E4B.id
    private val qwen4b = ModelCatalog.QWEN3_4B.id

    private fun textUi(
        installed: Set<String> = emptySet(),
        active: DownloadProgress? = null,
        selected: String? = null,
        ramMb: Long = 8_000,
    ) = LocalModelsUi.compose(
        catalog = ModelCatalog.TEXT, installed = installed, active = active, failures = emptyMap(), selectedId = selected, deviceRamMb = ramMb,
    )

    @Test
    fun `the text catalog lists only text models, lightest first`() {
        assertThat(textUi().items.map { it.spec.id })
            .containsExactlyElementsIn(ModelCatalog.TEXT.map { it.id })
            .inOrder()
        assertThat(textUi().items.map { it.spec.id }).containsNoneOf(base, small, parakeet)
    }

    @Test
    fun `an installed text model shows installed and only an installed one can be selected`() {
        val ui = textUi(installed = setOf(qwen), selected = gemma) // gemma está "selecionado" mas não instalado
        assertThat(ui.item(qwen).status).isEqualTo(LocalModelStatus.INSTALLED)
        assertThat(ui.item(gemma).status).isEqualTo(LocalModelStatus.AVAILABLE)
        assertThat(ui.item(gemma).selected).isFalse()
        assertThat(textUi(installed = setOf(qwen), selected = qwen).item(qwen).selected).isTrue()
    }

    @Test
    fun `a text download in progress shows progress and blocks the other downloads`() {
        val progress = DownloadProgress(gemma, DownloadState.RUNNING, 50, 100)
        val ui = textUi(active = progress)
        assertThat(ui.item(gemma).status).isEqualTo(LocalModelStatus.DOWNLOADING)
        assertThat(ui.item(gemma).progress).isEqualTo(progress)
        assertThat(ui.item(qwen).status).isEqualTo(LocalModelStatus.AVAILABLE)
        assertThat(ui.downloadActive).isTrue()
    }

    @Test
    fun `the heavy warning follows each text model's recommended RAM`() {
        // Recomendações (2026-10-27): Leve 3072, Versátil, Raciocínio e Equilibrado 4096, Intermediário 6144, Avançado 8192, Potente 12288.
        val small = textUi(ramMb = 2_000)
        assertThat(small.items.all { it.heavy }).isTrue()
        // 3,6 GB (o A32): o Leve não pesa; Raciocínio e Equilibrado mostram o aviso, mas carregam (a guarda é outra regra: RamGuard).
        val a32 = textUi(ramMb = 3_600)
        assertThat(a32.item(qwen).heavy).isFalse()
        assertThat(a32.item(deepseek).heavy).isTrue()
        assertThat(a32.item(gemma).heavy).isTrue()
        assertThat(a32.item(smol).heavy).isTrue()
        // 5 GB: o Raciocínio e o Equilibrado (4096) cabem; o Intermediário (6144) e o Avançado (8192) ainda pesam.
        val five = textUi(ramMb = 5_000)
        assertThat(five.item(deepseek).heavy).isFalse()
        assertThat(five.item(gemma).heavy).isFalse()
        assertThat(five.item(smol).heavy).isTrue()
        assertThat(five.item(gemmaE4b).heavy).isTrue()
        // 6,5/7,4 GB: o Potente, o Avançado e as candidatas com estimativa de 8192 MB ainda mostram o aviso.
        val heaviest = listOf(qwen4b, gemmaE4b, ModelCatalog.NANBEIGE_4_2_3B.id, ModelCatalog.QWEN3_5_4B.id)
        assertThat(textUi(ramMb = 6_500).items.filter { it.heavy }.map { it.spec.id }).containsExactlyElementsIn(heaviest)
        assertThat(textUi(ramMb = 7_400).items.filter { it.heavy }.map { it.spec.id }).containsExactlyElementsIn(heaviest)
        // 12 GB de RAM anunciada mostram ~11 GB: o Avançado deixa de pesar, o Potente (12288) ainda mostra o aviso, como o Avançado nos 8 GB.
        assertThat(textUi(ramMb = 11_200).items.filter { it.heavy }.map { it.spec.id }).containsExactly(qwen4b)
        assertThat(textUi(ramMb = 12_288).items.none { it.heavy }).isTrue()
    }

    @Test
    fun `a deleted selected text model reads as available and not selected`() {
        val ui = textUi(installed = emptySet(), selected = qwen)
        assertThat(ui.item(qwen).status).isEqualTo(LocalModelStatus.AVAILABLE)
        assertThat(ui.item(qwen).selected).isFalse()
    }

    /** O vazio do Raciocínio nunca pode listar os modelos de fala (o `EMPTY` da Transcrição lista). */
    @Test
    fun `EMPTY_TEXT lists the text catalog and never the speech one`() {
        assertThat(LocalModelsUi.EMPTY_TEXT.items.map { it.spec.id })
            .containsExactlyElementsIn(ModelCatalog.TEXT.map { it.id })
            .inOrder()
        assertThat(LocalModelsUi.EMPTY_TEXT).isEqualTo(textUi(ramMb = 0))
        assertThat(LocalModelsUi.EMPTY.items.map { it.spec.id }).containsNoneOf(qwen, gemma)
    }

    // ── o catálogo de visão (aba Visão) ────────────────────────────────────────────────────────────

    // O catálogo real de Visão e o de Voz estão vazios (2026-10-24, pins inventados saíram): a composição da lista é
    // exercitada com modelos de amostra.
    private val fastOcr = SamplePanelModels.VISION_LIGHT.id
    private val balancedOcr = SamplePanelModels.VISION_BALANCED.id
    private val smolVlm = SamplePanelModels.VISION_HEAVY.id

    private fun visionUi(
        installed: Set<String> = emptySet(),
        active: DownloadProgress? = null,
        selected: String? = null,
        ramMb: Long = 8_000,
    ) = LocalModelsUi.compose(
        catalog = SamplePanelModels.VISION, installed = installed, active = active, failures = emptyMap(), selectedId = selected, deviceRamMb = ramMb,
    )

    @Test
    fun `a vision list shows only the vision models it was given, lightest first`() {
        assertThat(visionUi().items.map { it.spec.id })
            .containsExactlyElementsIn(SamplePanelModels.VISION.map { it.id })
            .inOrder()
        assertThat(visionUi().items.map { it.spec.id }).containsNoneOf(base, small, parakeet, qwen, gemma)
    }

    /** VC-T2a: a lista da Visão traz só o OCR (PaddleOCR), baixável e ainda não instalado. */
    @Test
    fun `EMPTY_VISION lists only the ocr model, available to download`() {
        assertThat(LocalModelsUi.EMPTY_VISION.items.map { it.spec.id }).containsExactly("ocr-paddle-latin")
        assertThat(LocalModelsUi.EMPTY_VISION.items.single().status).isEqualTo(LocalModelStatus.AVAILABLE)
    }

    @Test
    fun `EMPTY_VISION lists the vision catalog and never speech or text`() {
        assertThat(LocalModelsUi.EMPTY_VISION.items.map { it.spec.id })
            .containsExactlyElementsIn(ModelCatalog.VISION.map { it.id })
            .inOrder()
        assertThat(LocalModelsUi.EMPTY_VISION).isEqualTo(
            LocalModelsUi.compose(catalog = ModelCatalog.VISION, installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 0),
        )
        assertThat(LocalModelsUi.EMPTY_VISION.items.map { it.spec.id }).containsNoneOf(base, small, parakeet, qwen, gemma)
        assertThat(LocalModelsUi.EMPTY.items.map { it.spec.id }).containsNoneOf(fastOcr, balancedOcr, smolVlm)
        assertThat(LocalModelsUi.EMPTY_TEXT.items.map { it.spec.id }).containsNoneOf(fastOcr, balancedOcr, smolVlm)
    }

    // ── o catálogo de voz (aba Voz / Fala) ─────────────────────────────────────────────────────────

    private val piper = SamplePanelModels.VOICE_LIGHT.id
    private val kokoro = SamplePanelModels.VOICE_BALANCED.id
    private val melo = SamplePanelModels.VOICE_HEAVY.id

    private fun voiceUi(
        installed: Set<String> = emptySet(),
        active: DownloadProgress? = null,
        selected: String? = null,
        ramMb: Long = 8_000,
    ) = LocalModelsUi.compose(
        catalog = SamplePanelModels.VOICE, installed = installed, active = active, failures = emptyMap(), selectedId = selected, deviceRamMb = ramMb,
    )

    @Test
    fun `a voice list shows only the voice models it was given, lightest first`() {
        assertThat(voiceUi().items.map { it.spec.id })
            .containsExactlyElementsIn(SamplePanelModels.VOICE.map { it.id })
            .inOrder()
        assertThat(voiceUi().items.map { it.spec.id }).containsNoneOf(base, small, parakeet, qwen, gemma, fastOcr, balancedOcr, smolVlm)
    }

    @Test
    fun `EMPTY_FALA lists the voice catalog and never speech, text or vision`() {
        assertThat(LocalModelsUi.EMPTY_FALA.items.map { it.spec.id })
            .containsExactlyElementsIn(ModelCatalog.VOICE.map { it.id })
            .inOrder()
        assertThat(LocalModelsUi.EMPTY_FALA).isEqualTo(
            LocalModelsUi.compose(catalog = ModelCatalog.VOICE, installed = emptySet(), active = null, failures = emptyMap(), selectedId = null, deviceRamMb = 0),
        )
        assertThat(LocalModelsUi.EMPTY_FALA.items.map { it.spec.id }).containsNoneOf(base, small, parakeet, qwen, gemma)
        assertThat(LocalModelsUi.EMPTY.items.map { it.spec.id }).containsNoneOf(piper, kokoro, melo)
        assertThat(LocalModelsUi.EMPTY_TEXT.items.map { it.spec.id }).containsNoneOf(piper, kokoro, melo)
        assertThat(LocalModelsUi.EMPTY_VISION.items.map { it.spec.id }).containsNoneOf(piper, kokoro, melo)
    }


    // ── seletor "Roda em" (CPU · GPU) do card de texto local ────────────────────────────────────────────

    private fun backendUi(
        installed: Set<String> = setOf(qwen),
        selected: String? = qwen,
        prefs: LocalBackendPrefs = LocalBackendPrefs.EMPTY,
        gpu: Boolean = true,
        catalog: List<com.keyflow.data.models.ModelSpec> = ModelCatalog.TEXT,
    ) = LocalModelsUi.compose(
        catalog = catalog, installed = installed, active = null, failures = emptyMap(), selectedId = selected, deviceRamMb = 8_000,
        backendPrefs = prefs, gpuAvailable = gpu,
    )

    @Test
    fun `a device with a GPU offers CPU and GPU on an installed text model`() {
        val item = backendUi().item(qwen)
        assertThat(item.backendOptions).containsExactly(ComputeBackend.CPU, ComputeBackend.GPU).inOrder()
        assertThat(item.showsBackendChooser).isTrue()
        assertThat(item.backend).isEqualTo(ComputeBackend.CPU)
    }

    @Test
    fun `with only one option the selector is not shown`() {
        val item = backendUi(gpu = false).item(qwen)
        assertThat(item.backendOptions).containsExactly(ComputeBackend.CPU)
        assertThat(item.showsBackendChooser).isFalse()
    }

    @Test
    fun `a model that is not installed never shows the selector`() {
        val ui = backendUi(installed = emptySet(), selected = null)
        assertThat(ui.items.none { it.showsBackendChooser }).isTrue()
    }

    @Test
    fun `speech vision and voice models never offer the GPU`() {
        val speech = LocalModelsUi.compose(
            catalog = ModelCatalog.SPEECH, installed = setOf(base), active = null, failures = emptyMap(), selectedId = base, deviceRamMb = 8_000,
            backendPrefs = LocalBackendPrefs.EMPTY.choose(base, ComputeBackend.GPU), gpuAvailable = true,
        )
        assertThat(speech.item(base).backendOptions).containsExactly(ComputeBackend.CPU)
        assertThat(speech.item(base).backend).isEqualTo(ComputeBackend.CPU)
        assertThat(speech.items.none { it.showsBackendChooser }).isTrue()
        val vision = backendUi(installed = setOf(SamplePanelModels.VISION_HEAVY.id), selected = null, catalog = SamplePanelModels.VISION)
        assertThat(vision.items.none { it.showsBackendChooser }).isTrue()
    }

    @Test
    fun `the saved choice shows as selected, and a choice the device no longer offers reads as the CPU`() {
        val prefs = LocalBackendPrefs.EMPTY.choose(qwen, ComputeBackend.GPU)
        assertThat(backendUi(prefs = prefs).item(qwen).backend).isEqualTo(ComputeBackend.GPU)
        assertThat(backendUi(prefs = prefs, gpu = false).item(qwen).backend).isEqualTo(ComputeBackend.CPU)
        assertThat(backendUi(prefs = prefs).item(gemma).backend).isEqualTo(ComputeBackend.CPU) // por modelo
    }

    @Test
    fun `the first GPU load warning shows until the GPU has loaded the model once`() {
        val chosen = LocalBackendPrefs.EMPTY.choose(qwen, ComputeBackend.GPU, ticket = 7L)
        assertThat(backendUi(prefs = chosen).item(qwen).gpuFirstLoad).isTrue()
        assertThat(backendUi(prefs = chosen.markGpuReady(qwen, 7L)).item(qwen).gpuFirstLoad).isFalse()
        assertThat(backendUi().item(qwen).gpuFirstLoad).isFalse() // na CPU não há o que avisar
    }

    @Test
    fun `the fallback notice shows only on the installed model that fell back`() {
        val fell = LocalBackendPrefs.EMPTY
            .choose(qwen, ComputeBackend.GPU, ticket = 7L).fellBack(qwen, ComputeBackend.GPU, 7L)
            .choose(gemma, ComputeBackend.GPU, ticket = 8L).fellBack(gemma, ComputeBackend.GPU, 8L)
        val ui = backendUi(prefs = fell, installed = setOf(qwen))
        assertThat(ui.item(qwen).backendFellBackFrom).isEqualTo(ComputeBackend.GPU)
        assertThat(ui.item(qwen).backend).isEqualTo(ComputeBackend.CPU)
        assertThat(ui.item(gemma).backendFellBackFrom).isNull() // não instalado
        assertThat(backendUi(prefs = LocalBackendPrefs.EMPTY).item(qwen).backendFellBackFrom).isNull()
    }

    /** O estado de antes do seletor (sem preferência, sem GPU) é o de sempre: nenhum card da galeria muda sem o aparelho oferecer a GPU. */
    @Test
    fun `without a GPU and without a preference the list is exactly what it was before the selector`() {
        assertThat(backendUi(gpu = false)).isEqualTo(
            LocalModelsUi.compose(
                catalog = ModelCatalog.TEXT, installed = setOf(qwen), active = null, failures = emptyMap(), selectedId = qwen, deviceRamMb = 8_000,
            ),
        )
    }
}
