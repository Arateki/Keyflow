package com.keyflow.vision.client

import android.content.Context
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelStore
import com.keyflow.vision.ocr.OcrModelFiles
import com.keyflow.vision.service.VisionModel
import java.io.File

/**
 * Monta o [VisionModel] que atravessa o AIDL a partir do catálogo e do que o `ModelStore` instalou. O fluxo de captura só CONSULTA se o
 * modelo está instalado (nunca baixa — design §9, regra MDL-02): o download é da aba Visão.
 */
object VisionModels {

    /** Quanto o modelo fica carregado sem uso antes de ser descarregado. Ponto de partida ⚠️ — a medir no A32 (design §6.5). */
    const val IDLE_UNLOAD_MS = 30_000L

    /** Memória de trabalho estimada, em MB (design §6.5: ~250 MB). ⚠️ medida na VC-T6; ver STATE.md. */
    const val RAM_ESTIMATE_MB = 250

    /** O OCR como o catálogo o descreve. */
    val OCR_ID: String get() = ModelCatalog.OCR_PADDLE_LATIN.id

    /** Os três arquivos estão em [dir]. Falta algum papel no catálogo → `IllegalArgumentException` (erro de catálogo). */
    fun from(spec: ModelSpec, dir: File, version: String? = null, threads: Int = com.keyflow.onnx.OnnxSession.DEFAULT_THREADS): VisionModel {
        fun path(role: FileRole): String {
            val file = requireNotNull(spec.file(role)) { "${spec.id}: OCR sem arquivo $role no catálogo" }
            return File(dir, file.fileName).absolutePath
        }
        return VisionModel(
            modelId = spec.id,
            files = OcrModelFiles(path(FileRole.DETECTOR), path(FileRole.RECOGNIZER), path(FileRole.DICTIONARY)),
            idleUnloadMs = IDLE_UNLOAD_MS,
            ramEstimateMb = RAM_ESTIMATE_MB,
            threads = threads,
            version = version ?: spec.files.joinToString("+") { it.sha256.take(12) },
        )
    }

    /** O OCR instalado, ou `null` se não está (ou se o catálogo não o conhece). Só leitura. */
    fun installed(context: Context): VisionModel? {
        val spec = ModelCatalog.byId(OCR_ID) ?: return null
        val store = ModelStore.get(context)
        if (!store.isInstalled(spec.id)) return null
        // A versão é a da instalação no disco (manifest), não a do catálogo: entre aplicar um catálogo novo e instalar o arquivo novo os bytes ainda são os antigos.
        return from(spec, store.dirFor(spec.id), version = store.installedVersion(spec.id))
    }
}
