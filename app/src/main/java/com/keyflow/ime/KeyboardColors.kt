package com.keyflow.ime

import android.graphics.Typeface

/**
 * Esquema de cores do teclado (IME). [SYSTEM] é o padrão desde 2026-09-28 (pedido do Yan: "semelhante à
 * cor da Samsung, aquela cor padrão do sistema"); [ATELIE] é o papel quente de antes, que segue como
 * opção. O app de config continua no Ateliê — isto vale só para teclado, barras e faixa de sugestões.
 *
 * [AERO] é protótipo (Yan, 2026-09-28): Frutiger Aero — teclas de vidro ([GlassPainter]) sobre uma lâmina
 * translúcida ([KeyboardBackdrop]). Desde 2026-09-29 a transparência é **real**: a janela do teclado é
 * translúcida e o que o app desenha atrás aparece através do vidro (com desfoque do compositor onde o
 * aparelho oferece). Tem uma aparência só, no tema claro e no escuro: o vidro não muda de cor.
 *
 * [GLITCH] (Yan, 2026-09-29): o efeito de falha gráfica em preto, branco, vermelho e azul, como a logo do TikTok —
 * ([GlitchPainter]) sobre um fundo preto com scanlines e rasgos. Também tem uma aparência só.
 */
enum class KeyboardSkin(
    val id: String,
    /**
     * Skin paga. Decisão do Yan (2026-09-29): **só a Sistema é gratuita**; a Ateliê e a Aero são pagas. Por
     * enquanto NADA é bloqueado — todas seguem liberadas para ele testar. Quando a monetização entrar (Milestone
     * 7, UNL-04 / MZ-T9), as pagas ficam bloqueadas até `skinsUnlocked`.
     */
    val paid: Boolean,
) {
    SYSTEM("system", paid = false),
    ATELIE("atelie", paid = true),
    AERO("aero", paid = true),

    /** Falha gráfica em preto, branco, vermelho e azul (a logo do TikTok). Paga, como o Aero: só o Sistema é livre. */
    GLITCH("glitch", paid = true),
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
    /** Balões que abrem por cima (prévia da tecla, acentos, grade de atalhos): sempre opacos. */
    val popup: Int = key,
    /**
     * Lâmina de vidro atrás de barras e grade; `null` = fundo liso [tray]. Com lâmina, [tray] é transparente
     * e teclas e botões são de vidro ([isGlass]).
     */
    val backdrop: KeyboardBackdrop? = null,
    /** Halo do texto das teclas (0 = sem); no vidro, é o que segura a letra sobre o que o app desenha atrás. */
    val inkShadow: Int = 0,
    /** Peso das letras: o vidro pede letra mais grossa, para ler sobre qualquer fundo. */
    val labelWeight: LabelWeight = LabelWeight.NORMAL,
    /** Multiplica a espessura do traço dos ícones (1 = a de sempre). */
    val iconStrokeScale: Float = 1f,
) {
    /** A skin tem superfície própria (Aero, Glitch): teclas pintadas por um [KeySurface] sobre um [backdrop]. */
    val hasSurface: Boolean get() = backdrop != null

    /** As teclas desta skin ([hasSurface]); `null` nas skins lisas. */
    fun surface(density: Float): KeySurface? = backdrop?.surface(density)

    /**
     * Passa uma cor de acento compartilhada (o verde do mic pronto, o âmbar de gravando, o terracota de erro) para
     * a paleta da skin. Só o Glitch troca: azul, vermelho e vermelho — não existe verde nem âmbar nele.
     */
    fun tone(color: Int): Int = if (backdrop != KeyboardBackdrop.GLITCH) color else when (color) {
        ACCENT_TEAL_INT -> GlitchPainter.BLUE
        ACCENT_RECORD_INT, ACCENT_TERRACOTTA_INT -> GlitchPainter.RED
        else -> color
    }

    /** Contorno das etiquetas de sugestão: a tinta bem suave, visível nos dois esquemas. */
    val chipOutline: Int get() = (ink and 0x00FFFFFF) or 0x40000000

    companion object {
        // Os acentos compartilhados (ver MicVisuals): repetidos aqui para o Glitch trocá-los.
        private const val ACCENT_TEAL_INT = 0xFF4E9683.toInt()
        private const val ACCENT_RECORD_INT = 0xFFC6873F.toInt()
        private const val ACCENT_TERRACOTTA_INT = 0xFFD9694B.toInt()

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

        // Aero: [key]/[modifier] são a tinta do vidro — clara e translúcida, por cima da lâmina e do que o app
        // desenha atrás; o brilho, o reflexo e o aro vêm do GlassPainter. Letras em preto com um halo branco forte
        // e peso médio: o vidro é claro e o fundo pode ser qualquer coisa. É a mesma paleta no claro e no escuro.
        private val AERO = KeyboardColors(
            tray = 0x00000000,
            key = 0x52F2FBFF,
            keyBorder = 0xE6FFFFFF.toInt(),
            modifier = 0x66A6DCFF,
            ink = 0xFF000000.toInt(),
            popup = 0xFFF3FAFF.toInt(),
            backdrop = KeyboardBackdrop.AERO,
            inkShadow = 0xF2FFFFFF.toInt(),
            labelWeight = LabelWeight.MEDIUM,
            iconStrokeScale = 1.2f,
        )

        // Glitch: faces pretas, teclas de função em azul fundo, letras brancas em três camadas (o GlitchPainter desenha
        // o deslocamento ciano/vermelho) e nenhuma sombra: o halo do vidro não faz sentido aqui.
        private val GLITCH = KeyboardColors(
            tray = 0x00000000,
            key = 0xFF0B0B0B.toInt(),
            keyBorder = 0xFFFFFFFF.toInt(),
            modifier = 0xFF10267A.toInt(),
            ink = 0xFFFFFFFF.toInt(),
            popup = 0xFF000000.toInt(),
            backdrop = KeyboardBackdrop.GLITCH,
            labelWeight = LabelWeight.BOLD,
            iconStrokeScale = 1.15f,
        )

        fun of(skin: KeyboardSkin, dark: Boolean): KeyboardColors = when (skin) {
            KeyboardSkin.SYSTEM -> if (dark) SYSTEM_DARK else SYSTEM_LIGHT
            KeyboardSkin.ATELIE -> if (dark) ATELIE_DARK else ATELIE_LIGHT
            KeyboardSkin.AERO -> AERO
            KeyboardSkin.GLITCH -> GLITCH
        }
    }
}

/** Peso das letras de uma skin. */
enum class LabelWeight {
    NORMAL,
    MEDIUM,
    BOLD,
    ;

    /** A fonte com este peso; `null` = a que a view já usa. */
    fun typeface(): Typeface? = when (this) {
        NORMAL -> null
        MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        BOLD -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
}
