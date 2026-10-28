package com.keyflow.reasoning.local

import com.keyflow.data.LocalBackendChoice
import com.keyflow.data.LocalBackendPrefs

/** Renova a ficha e devolve backend + ficha do mesmo snapshot gravado, antes de enfileirar a pré-carga explícita. */
internal suspend fun backendChoiceForSelection(
    modelId: String,
    update: suspend ((LocalBackendPrefs) -> LocalBackendPrefs) -> LocalBackendPrefs,
): LocalBackendChoice {
    val prefs = update { it.renewSelection(modelId) }
    return LocalBackendChoice(prefs.backendFor(modelId), prefs.ticketFor(modelId))
}
