package com.keyflow.ime

/**
 * Esquema de cores do teclado (IME). [SYSTEM] é o padrão desde 2026-09-28 (pedido do Yan: "semelhante à
 * cor da Samsung, aquela cor padrão do sistema"); [ATELIE] é o papel quente de antes, que segue como
 * opção. O app de config continua no Ateliê — isto vale só para teclado, barras e faixa de sugestões.
 */
enum class KeyboardSkin(val id: String) {
    SYSTEM("system"),
    ATELIE("atelie"),
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun of(id: String?): KeyboardSkin = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * As cores que as views do teclado pintam ([KeyFlowKeyboardView], barras, grade, faixa de sugestões,
 * popups) — antes cada uma repetia os mesmos `if (dark) … else …`. Os acentos (teal do mic e do shift,
 * terracota de erro) ficam de fora: são a identidade do KeyFlow nos dois esquemas.
 */
data class KeyboardColors(
    /** Fundo do teclado e das barras. */
    val tray: Int,
    /** Tecla de letra. */
    val key: Int,
    /** Contorno da tecla (no Sistema, igual à tecla: o Samsung não desenha contorno). */
    val keyBorder: Int,
    /** Tecla de função (shift, apagar, ?123, enter) e botões das barras. */
    val modifier: Int,
    /** Texto e ícones. */
    val ink: Int,
) {
    /** Contorno das etiquetas de sugestão: a tinta bem suave, visível nos dois esquemas. */
    val chipOutline: Int get() = (ink and 0x00FFFFFF) or 0x40000000

    companion object {
        // Medidas no teclado Samsung (One UI) do A32 do Yan, em prints de 2026-09-28.
        private val SYSTEM_LIGHT = KeyboardColors(
            tray = 0xFFE6E6E6.toInt(),
            key = 0xFFFDFDFD.toInt(),
            keyBorder = 0xFFFDFDFD.toInt(),
            modifier = 0xFFD1D1D1.toInt(),
            ink = 0xFF252525.toInt(),
        )
        private val SYSTEM_DARK = KeyboardColors(
            tray = 0xFF0A0A0A.toInt(),
            key = 0xFF2D2D2D.toInt(),
            keyBorder = 0xFF2D2D2D.toInt(),
            modifier = 0xFF171717.toInt(),
            ink = 0xFFFAFAFA.toInt(),
        )
        private val ATELIE_LIGHT = KeyboardColors(
            tray = 0xFFDCD0AC.toInt(),
            key = 0xFFFBF6E6.toInt(),
            keyBorder = 0xFFD7C9A0.toInt(),
            modifier = 0xFFCFC096.toInt(),
            ink = 0xFF2B2720.toInt(),
        )
        private val ATELIE_DARK = KeyboardColors(
            tray = 0xFF332E27.toInt(),
            key = 0xFF3A332A.toInt(),
            keyBorder = 0xFF4A4234.toInt(),
            modifier = 0xFF2B2720.toInt(),
            ink = 0xFFF1E7C9.toInt(),
        )

        fun of(skin: KeyboardSkin, dark: Boolean): KeyboardColors = when (skin) {
            KeyboardSkin.SYSTEM -> if (dark) SYSTEM_DARK else SYSTEM_LIGHT
            KeyboardSkin.ATELIE -> if (dark) ATELIE_DARK else ATELIE_LIGHT
        }
    }
}
