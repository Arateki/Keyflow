package com.keyflow.ui.config

import com.google.common.truth.Truth.assertThat
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.ModelCatalog
import com.keyflow.vision.ocr.OcrFailure
import com.keyflow.vision.ocr.OcrResult
import org.junit.Test

/** A área do Testar da Visão: quando o botão liga, o que cada resultado do OCR mostra. */
class VisaoTestTest {

    private val ocr = ModelCatalog.OCR_PADDLE_LATIN
    private val gemma = ModelCatalog.GEMMA_4_E2B

    private fun models(installed: Set<String>) = LocalModelsUi.compose(
        catalog = ModelCatalog.visionModels(debug = true), installed = installed, active = null, failures = emptyMap(),
        selectedId = null, deviceRamMb = 8_000,
    )

    private fun availability(settings: Settings, installed: Set<String> = emptySet()) = visionTestAvailability(settings, models(installed))

    @Test
    fun `the test runs only with the local OCR installed and chosen`() {
        val chosen = Settings(visionSource = VisionSource.LOCAL, visionLocalModelId = ocr.id)
        assertThat(availability(chosen, installed = setOf(ocr.id))).isEqualTo(VisionTestAvailability.Ready)
    }

    @Test
    fun `no local model chosen says to download and select one`() {
        assertThat(availability(Settings(visionSource = VisionSource.LOCAL), installed = setOf(ocr.id)))
            .isEqualTo(VisionTestAvailability.Unavailable(VisionTestUnavailable.NO_LOCAL_MODEL))
    }

    @Test
    fun `a chosen model that is not installed anymore asks to download it again`() {
        assertThat(availability(Settings(visionSource = VisionSource.LOCAL, visionLocalModelId = ocr.id)))
            .isEqualTo(VisionTestAvailability.Unavailable(VisionTestUnavailable.MODEL_NOT_INSTALLED))
        assertThat(availability(Settings(visionSource = VisionSource.LOCAL, visionLocalModelId = "sumiu"), installed = setOf(ocr.id)))
            .isEqualTo(VisionTestAvailability.Unavailable(VisionTestUnavailable.MODEL_NOT_INSTALLED))
    }

    /** O Gemma 4 e os provedores ainda não têm motor de visão (VC-T15): o botão fica desligado e diz por quê, sem fingir um teste. */
    @Test
    fun `the Gemma 4 and the cloud sources are not testable yet`() {
        val notYet = VisionTestAvailability.Unavailable(VisionTestUnavailable.NOT_YET)
        assertThat(availability(Settings(visionSource = VisionSource.LOCAL, visionLocalModelId = gemma.id), installed = setOf(gemma.id))).isEqualTo(notYet)
        listOf(VisionSource.OPENAI, VisionSource.ANTHROPIC, VisionSource.GOOGLE, VisionSource.CUSTOM).forEach {
            assertThat(availability(Settings(visionSource = it, visionLocalModelId = ocr.id), installed = setOf(ocr.id))).isEqualTo(notYet)
        }
    }

    @Test
    fun `each OCR result becomes a state the screen can draw`() {
        assertThat(visionTestStateFor(OcrResult.Text("linha 1\nlinha 2"), 1_200))
            .isEqualTo(VisionTestState(text = "linha 1\nlinha 2", durationMs = 1_200))
        assertThat(visionTestStateFor(OcrResult.Empty, 300)).isEqualTo(VisionTestState(durationMs = 300, error = VisionTestError.EMPTY))
        assertThat(visionTestStateFor(OcrResult.Unavailable, 5)).isEqualTo(VisionTestState(error = VisionTestError.UNAVAILABLE))
        assertThat(visionTestStateFor(OcrResult.Failed(OcrFailure.OOM), 9).error).isEqualTo(VisionTestError.OUT_OF_MEMORY)
        assertThat(visionTestStateFor(OcrResult.Failed(OcrFailure.PROCESS_DIED), 9).error).isEqualTo(VisionTestError.INTERRUPTED)
        assertThat(visionTestStateFor(OcrResult.Failed(OcrFailure.TIMEOUT), 9).error).isEqualTo(VisionTestError.INTERRUPTED)
        assertThat(visionTestStateFor(OcrResult.Failed(OcrFailure.ENGINE), 9).error).isEqualTo(VisionTestError.FAILED)
        assertThat(visionTestStateFor(OcrResult.Failed(OcrFailure.BAD_INPUT), 9).error).isEqualTo(VisionTestError.FAILED)
        // Cancelado por quem pediu (o "Parar"): nada a avisar.
        assertThat(visionTestStateFor(OcrResult.Failed(OcrFailure.CANCELLED), 9).error).isNull()
    }

    @Test
    fun `every message the test area can show has a text`() {
        VisionTestError.entries.forEach { assertThat(VisionTestMessages.error(it)).isNotEmpty() }
        VisionTestUnavailable.entries.forEach { assertThat(VisionTestMessages.unavailable(it)).isNotEmpty() }
        assertThat(VisionTestMessages.unavailable(VisionTestUnavailable.NO_LOCAL_MODEL)).isEqualTo("Baixe e selecione um modelo para testar.")
    }
}
