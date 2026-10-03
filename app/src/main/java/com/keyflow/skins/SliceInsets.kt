package com.keyflow.skins

/**
 * Margens do fatiamento 3×3 de uma face de tecla (`keyboard.slice` do manifest), **em unidades do `viewBox`** do SVG:
 * os quatro cantos mantêm a proporção e só o miolo estica. Puro (sem Android): o manifest e o desenhista usam o mesmo tipo.
 */
data class SliceInsets(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left >= 0f && top >= 0f && right >= 0f && bottom >= 0f) { "margens do fatiamento não podem ser negativas" }
    }
}
