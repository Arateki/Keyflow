package com.keyflow.skins

/**
 * O relatório de um import (FORMAT.md §6), em três listas de **textos já em português** (ou no idioma do app: saem de `tr`):
 * o que entrou, o que foi ignorado ou não tem efeito, e o que impediu a instalação. Com qualquer [errors], **nada** é instalado.
 *
 * [installed] só descreve o que seria instalado (por exemplo "orbe: 4 estados, 46 frames"); a UI mostra as três listas num diálogo.
 */
data class ImportReport(
    val installed: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()

    /** Junta outro relatório a este (a validação do store acrescenta o que só ele sabe: render de teste dos SVGs). */
    operator fun plus(other: ImportReport) = ImportReport(
        installed = installed + other.installed,
        warnings = warnings + other.warnings,
        errors = errors + other.errors,
    )
}
