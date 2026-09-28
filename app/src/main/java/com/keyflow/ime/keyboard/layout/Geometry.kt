package com.keyflow.ime.keyboard.layout

/** Uma tecla já posicionada (bounds calculados por peso). Sem `android.graphics` — pura, testável na JVM. */
data class PlacedKey(val keyDef: KeyDef, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

data class PlacedRow(val keys: List<PlacedKey>)

/**
 * Geometria pura da grade (RND-01/LAY-01): distribui a largura de uma linha pelo **peso** de cada tecla
 * (nunca px fixo), então resize/re-medida são só reexecutar com outra largura — mesma lógica que o
 * `KeyboardGridView` (KC-T3) chama em `onMeasure`/`onDraw`.
 */
object Geometry {
    fun placeRow(row: KeyRow, top: Float, bottom: Float, totalWidth: Float): List<PlacedKey> {
        val totalWeight = row.keys.sumOf { it.widthWeight.toDouble() }.toFloat()
        var x = 0f
        return row.keys.map { key ->
            val width = totalWidth * (key.widthWeight / totalWeight)
            val placed = PlacedKey(key, left = x, top = top, right = x + width, bottom = bottom)
            x += width
            placed
        }
    }

    fun placeLayer(layer: KeyboardLayer, totalWidth: Float, rowHeight: Float): List<PlacedRow> =
        layer.rows.mapIndexed { index, row ->
            val top = index * rowHeight
            PlacedRow(placeRow(row, top = top, bottom = top + rowHeight, totalWidth = totalWidth))
        }

    fun hitTest(row: List<PlacedKey>, x: Float, y: Float): KeyDef? =
        row.firstOrNull { it.contains(x, y) }?.keyDef

    fun hitTestLayer(rows: List<PlacedRow>, x: Float, y: Float): KeyDef? =
        rows.firstNotNullOfOrNull { hitTest(it.keys, x, y) }

    /** A tecla posicionada sob ([x], [y]), ou `null` fora de todas. */
    fun keyAt(rows: List<PlacedRow>, x: Float, y: Float): PlacedKey? =
        rows.firstNotNullOfOrNull { row -> row.keys.firstOrNull { it.contains(x, y) } }

    /**
     * Para qual tecla foi um dedo que pressiona [current]. Fica nela enquanto estiver dentro ou a menos
     * de [hysteresis] da borda: num toque rápido o dedo escorrega uns pixels, e a tecla não pode trocar
     * nem sumir por isso. Passou disso, vale a tecla sob o dedo; fora de todas (saiu do teclado), `null`.
     */
    fun slideTarget(current: PlacedKey, rows: List<PlacedRow>, x: Float, y: Float, hysteresis: Float): PlacedKey? {
        val nearCurrent = x >= current.left - hysteresis && x < current.right + hysteresis &&
            y >= current.top - hysteresis && y < current.bottom + hysteresis
        if (nearCurrent) return current
        return keyAt(rows, x, y)
    }
}
