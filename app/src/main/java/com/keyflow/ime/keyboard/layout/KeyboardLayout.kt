package com.keyflow.ime.keyboard.layout

/** Uma tecla: ação, rótulo exibido e peso de largura (geometria por peso — nunca px fixo). */
data class KeyDef(val action: KeyAction, val label: String, val widthWeight: Float = 1f)

data class KeyRow(val keys: List<KeyDef>)

/** Uma camada da grade (letras ou símbolos), como um conjunto de linhas. */
data class KeyboardLayer(val id: LayerId, val rows: List<KeyRow>)

/**
 * Mapa de variantes por long-press (acentos), por letra base (LAY-04). Simples o bastante para ser
 * serializável (EXT-01) sem mudança estrutural futura.
 */
data class AccentMap(private val variants: Map<String, List<String>>) {
    fun variantsFor(base: String): List<String> = variants[base] ?: emptyList()
}

/** Layout completo de um idioma: as camadas (letras/símbolos) + o mapa de acentos. */
data class KeyboardLayout(
    val locale: String,
    val layers: Map<LayerId, KeyboardLayer>,
    val accents: AccentMap,
)

/**
 * Prepende a tecla de globo (KC-T9/globo PT↔EN↔ES) à 4ª fileira de cada camada — a mesma fileira
 * dedicada do `?123`/`ABC` (decisão do Yan, 2026-07-01). Sem efeito quando [show] é falso (só 1 idioma
 * habilitado): devolve o layout base sem tocar a fileira. Pura — `KeyFlowKeyboardView` decide o [show]
 * a partir de `InputMethodManager.getEnabledInputMethodSubtypeList`.
 */
fun KeyboardLayout.withLanguageKey(show: Boolean): KeyboardLayout {
    if (!show) return this
    val patchedLayers = layers.mapValues { (_, layer) ->
        val rows = layer.rows.toMutableList()
        val lastIndex = rows.lastIndex
        val row4 = rows[lastIndex]
        rows[lastIndex] = KeyRow(listOf(KeyDef(KeyAction.Language, "🌐", 1f)) + row4.keys)
        layer.copy(rows = rows)
    }
    return copy(layers = patchedLayers)
}
