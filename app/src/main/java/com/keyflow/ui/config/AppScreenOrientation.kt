package com.keyflow.ui.config

/**
 * As 4 orientações possíveis de tela para rotação contínua (0° → 90° → 180° → 270° → 0°).
 *
 * Cada toque no botão do cabeçalho avança sempre na mesma direção (sentido horário), permitindo
 * usar o aplicativo em qualquer uma das 4 posições do aparelho sem desvirar para trás.
 */
enum class AppScreenOrientation(val id: Int, val angleDeg: Int) {
    PORTRAIT(0, 0),
    LANDSCAPE(1, 90),
    REVERSE_PORTRAIT(2, 180),
    REVERSE_LANDSCAPE(3, 270);

    val isLandscape: Boolean get() = this == LANDSCAPE || this == REVERSE_LANDSCAPE

    fun next(): AppScreenOrientation = when (this) {
        PORTRAIT -> LANDSCAPE
        LANDSCAPE -> REVERSE_PORTRAIT
        REVERSE_PORTRAIT -> REVERSE_LANDSCAPE
        REVERSE_LANDSCAPE -> PORTRAIT
    }

    companion object {
        fun fromId(id: Int): AppScreenOrientation {
            val normalized = ((id % 4) + 4) % 4
            return entries.firstOrNull { it.id == normalized } ?: PORTRAIT
        }
    }
}
