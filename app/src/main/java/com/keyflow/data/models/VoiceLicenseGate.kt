package com.keyflow.data.models

import java.io.File

/**
 * Aceite da OpenRAIL-M só do Supertonic (FPRV-03). Sem o aceite gravado, [begin] não chama o download.
 * Os outros modelos, inclusive o VITS, passam direto. O texto da licença e do Attachment A fica em
 * `app/licenses/` e no diálogo; aqui só a decisão e o registro por id.
 */
object VoiceLicenseGate {
    const val SUPERTONIC_ID = "supertonic-3"
    const val ASSET = "licenses/OpenRAIL-M_supertonic-3.txt"

    fun requiresAcceptance(modelId: String): Boolean = modelId == SUPERTONIC_ID

    /** [start] é o downloader. Não é chamado quando este modelo ainda não foi aceito. */
    fun begin(modelId: String, accepted: Boolean, start: () -> StartResult): StartResult {
        if (requiresAcceptance(modelId) && !accepted) return StartResult.NeedsLicenseAcceptance(modelId)
        return start()
    }
}

/** Ids aceitos, um por linha. Arquivo do app, não segredo. */
class VoiceLicenseAcceptance(private val file: File) {
    fun isAccepted(modelId: String): Boolean = read().contains(modelId)

    fun accept(modelId: String) {
        val ids = read() + modelId
        file.parentFile?.mkdirs()
        file.writeText(ids.joinToString("\n"))
    }

    private fun read(): Set<String> =
        if (!file.isFile) emptySet() else file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
