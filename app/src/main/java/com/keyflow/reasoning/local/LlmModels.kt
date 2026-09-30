package com.keyflow.reasoning.local

import android.content.Context
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import com.keyflow.data.models.ModelSpec
import com.keyflow.data.models.ModelStore
import java.io.File

/** Monta o [LlmModel] que atravessa o AIDL a partir do catálogo e do que o `ModelStore` instalou. */
object LlmModels {

    /**
     * Quanto o modelo fica carregado sem uso antes de ser descarregado (R8). Ponto de partida — a medição no A32
     * (RSN-T19) ajusta. O cliente mantém o bind por este tempo mais uma folga ([ReasoningConnection.LINGER_MS]).
     */
    const val IDLE_UNLOAD_MS = 120_000L

    /**
     * O arquivo `.litertlm` está em [dir]. A memória de trabalho estimada é a METADE da RAM total recomendada no
     * catálogo — a recomendação é do aparelho inteiro (sistema e outros apps incluídos); estimativa a revisar na RSN-T19.
     * Falta o arquivo do modelo no catálogo → `IllegalArgumentException` (é erro de catálogo, não de uso).
     */
    fun from(spec: ModelSpec, dir: File): LlmModel {
        val file = requireNotNull(spec.file(FileRole.MODEL)) { "${spec.id}: modelo de texto sem arquivo MODEL no catálogo" }
        return LlmModel(
            modelId = spec.id,
            modelPath = File(dir, file.fileName).absolutePath,
            engineType = spec.engineType.name,
            idleUnloadMs = IDLE_UNLOAD_MS,
            ramEstimateMb = spec.recommendedRamMb / 2,
        )
    }

    /** O modelo instalado com esse id, ou `null` se o catálogo não o conhece ou ele não é de texto. */
    fun forId(context: Context, modelId: String): LlmModel? {
        val spec = ModelCatalog.byId(modelId)?.takeIf { it.files.any { f -> f.role == FileRole.MODEL } } ?: return null
        return from(spec, ModelStore.get(context).dirFor(spec.id))
    }
}
