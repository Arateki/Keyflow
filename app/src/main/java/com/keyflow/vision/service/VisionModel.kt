package com.keyflow.vision.service

import com.keyflow.vision.ocr.OcrModelFiles

/**
 * O OCR como o processo `:vision` o vê: caminhos absolutos e limites já resolvidos. [modelId] e [version] decidem se o que está
 * carregado serve; trocar de modelo (ou da versão dos arquivos) descarrega o anterior.
 */
data class VisionModel(
    val modelId: String,
    val files: OcrModelFiles,
    val idleUnloadMs: Long,
    val ramEstimateMb: Int,
    val threads: Int,
    val version: String = "",
) {
    fun toParcel(): VisionConfig = VisionConfig().also {
        it.modelId = modelId
        it.detectorPath = files.detector
        it.recognizerPath = files.recognizer
        it.dictionaryPath = files.dictionary
        it.idleUnloadMs = idleUnloadMs
        it.ramEstimateMb = ramEstimateMb
        it.threads = threads
        it.version = version
    }

    companion object {
        fun fromParcel(config: VisionConfig): VisionModel = VisionModel(
            modelId = config.modelId.orEmpty(),
            files = OcrModelFiles(config.detectorPath.orEmpty(), config.recognizerPath.orEmpty(), config.dictionaryPath.orEmpty()),
            idleUnloadMs = config.idleUnloadMs,
            ramEstimateMb = config.ramEstimateMb,
            threads = config.threads.coerceAtLeast(1),
            version = config.version.orEmpty(),
        )
    }
}
