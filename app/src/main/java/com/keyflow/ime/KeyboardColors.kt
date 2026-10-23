package com.keyflow.ime

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.keyflow.skins.PackKeyboard

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
 * [ROSE_GOLD] (Yan, 2026-09-29): um tema feminino em cetim rosé metálico ([RoseGoldPainter]) sobre um fundo blush; um
 * brilho atravessa o teclado de vez em quando ([ShimmerMotion]). Uma aparência só nos dois temas.
 *
 * [GLITCH] (Yan, 2026-09-29): o efeito de falha gráfica em preto, branco, vermelho e azul, como a logo do TikTok —
 * ([GlitchPainter]) sobre um fundo preto com scanlines e rasgos. Também tem uma aparência só.
 */
enum class KeyboardSkin(
    val id: String,
    /** Skin paga; Sistema e Ateliê são gratuitas na V1, as demais exigem o unlock. */
    val paid: Boolean,
) {
    SYSTEM("system", paid = false),
    ATELIE("atelie", paid = false),
    AERO("aero", paid = true),

    /** Falha gráfica em preto, branco, vermelho e azul (a logo do TikTok). Paga, como o Aero: só o Sistema é livre. */
    GLITCH("glitch", paid = true),

    /** Rose Gold: cetim rosé metálico, feminino e elegante (Yan, 2026-09-29). Paga, como as outras. */
    ROSE_GOLD("rosegold", paid = true),
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun of(id: String?): KeyboardSkin = entries.firstOrNull { it.id == id } ?: DEFAULT

        /** Resolve a escolha salva sem apagá-la quando o direito de uso foi revogado. */
        fun effective(id: String?, skinsUnlocked: Boolean): KeyboardSkin {
            val skin = of(id)
            return if (skin.paid && !skinsUnlocked) DEFAULT else skin
        }
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
    /**
     * O teclado de uma skin **de pacote** ([forPack]): as faces e o fundo vêm dos SVGs do pacote, ao lado do enum [backdrop] — que fica
     * `null` aqui, porque o enum é fechado e as skins de hoje não mudam. `null` em todas as skins de hoje.
     */
    val pack: PackKeyboard? = null,
) {
    /** A skin tem superfície própria (Aero, Glitch, pacote): teclas pintadas por um [KeySurface] sobre um fundo. */
    val hasSurface: Boolean get() = backdrop != null || pack != null

    /** As teclas desta skin ([hasSurface]); `null` nas skins lisas. [blurBehind]: o aparelho desfoca o que está atrás. */
    fun surface(density: Float, blurBehind: Boolean): KeySurface? = pack?.surface() ?: backdrop?.surface(density, blurBehind)

    /**
     * O fundo desenhado atrás das barras e da grade, ou `null` quando é a cor lisa [tray]: a lâmina do enum ([backdrop]) ou o
     * `keyboard_tray.svg` do pacote. Uma instância nova a cada chamada (cada área do teclado tem o seu tamanho).
     */
    fun backgroundDrawable(density: Float, blurBehind: Boolean): Drawable? = pack?.backgroundDrawable() ?: backdrop?.drawable(density, blurBehind)

    /** A cor da faixa do sistema sob o teclado, ou `null` para deixar a que o sistema já tem. */
    fun navigationBarColor(blurBehind: Boolean): Int? = pack?.navigationBarColor ?: backdrop?.navigationBarColor(blurBehind)

    /**
     * Passa uma cor de acento compartilhada (o verde do mic pronto, o âmbar de gravando, o terracota de erro) para
     * a paleta da skin. Só o Glitch troca, por um ciano e um vermelho FUNDOS (fundos de botão, não os neons das
     * falhas): não existe verde nem âmbar nele, e nada estoura.
     */
    fun tone(color: Int): Int = when (backdrop) {
        KeyboardBackdrop.GLITCH -> when (color) {
            ACCENT_TEAL_INT -> GLITCH_DEEP_CYAN
            ACCENT_RECORD_INT, ACCENT_TERRACOTTA_INT -> GLITCH_DEEP_RED
            else -> color
        }
        // Rose Gold: o verde vira rosa-poeira, o âmbar e o terracota viram framboesa (sem verde nem âmbar no tema).
        KeyboardBackdrop.ROSE_GOLD -> when (color) {
            ACCENT_TEAL_INT -> ROSE_DUSTY
            ACCENT_RECORD_INT -> ROSE_RASPBERRY
            ACCENT_TERRACOTTA_INT -> ROSE_DEEP_RASPBERRY
            else -> color
        }
        else -> color
    }

    /** Contorno das etiquetas de sugestão: a tinta bem suave, visível nos dois esquemas. */
    val chipOutline: Int get() = (ink and 0x00FFFFFF) or 0x40000000

    companion object {
        // Os acentos compartilhados (ver MicVisuals): repetidos aqui para o Glitch trocá-los.
        private const val ACCENT_TEAL_INT = 0xFF4E9683.toInt()
        private const val ACCENT_RECORD_INT = 0xFFC6873F.toInt()
        private const val ACCENT_TERRACOTTA_INT = 0xFFD9694B.toInt()
        private const val GLITCH_DEEP_CYAN = 0xFF0F3E44.toInt()
        private const val GLITCH_DEEP_RED = 0xFF7A1029.toInt()
        private const val ROSE_DUSTY = 0xFFB8707B.toInt()
        private const val ROSE_RASPBERRY = 0xFF9E3A55.toInt()
        private const val ROSE_DEEP_RASPBERRY = 0xFF8A2D45.toInt()

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

        // Glitch: discreto. Faces em cinza escuro neutro e letras claras; as cores do efeito (ciano e vermelho) ficam só nos
        // detalhes das teclas e nas falhas (GlitchPainter, KeyboardMotion). Sem azul, sem branco berrante: o Yan achou a
        // versão anterior "estourando, como um tema de alto contraste".
        private val GLITCH = KeyboardColors(
            tray = 0x00000000,
            key = 0xFF191919.toInt(),
            keyBorder = 0x40FFFFFF,
            modifier = 0xFF262626.toInt(),
            ink = 0xFFF0F0F0.toInt(),
            popup = 0xFF141414.toInt(),
            backdrop = KeyboardBackdrop.GLITCH,
            labelWeight = LabelWeight.MEDIUM,
            iconStrokeScale = 1.1f,
        )

        // Rose Gold: cetim rosé. [key] é a face das teclas de letra (blush) e [modifier] a das de função (rosé mais fundo); o
        // RoseGoldPainter faz o cetim, o bisel metálico e o brilho por cima. Letras em vinho, que lê bem nos dois tons.
        // Uma paleta só, no claro e no escuro, como o Aero e o Glitch.
        private val ROSE_GOLD = KeyboardColors(
            tray = 0x00000000,
            key = 0xFFF6DDD6.toInt(),
            keyBorder = 0xFFC98B85.toInt(),
            modifier = 0xFFE3B3AA.toInt(),
            ink = 0xFF5B2A35.toInt(),
            popup = 0xFFFFF3EF.toInt(),
            backdrop = KeyboardBackdrop.ROSE_GOLD,
            labelWeight = LabelWeight.MEDIUM,
            iconStrokeScale = 1.1f,
        )

        /**
         * As cores do teclado de um pacote de skin (SKP-KBD-03): letras e ícones em `keyboard.ink`, balões e faixa de sugestões em
         * `keyboard.popup`. As faces não têm cor aqui — vêm do SVG —, então [key] e [modifier] são a própria cor dos balões: é a tinta
         * "comum" que a superfície reconhece, e as etiquetas de sugestão ficam legíveis. Com `keyboard_tray.svg` a bandeja é o SVG e
         * [tray] fica transparente; sem ele, é a cor lisa `keyboard.trayColor`. Uma aparência só, no claro e no escuro (D5).
         */
        fun forPack(pack: PackKeyboard): KeyboardColors {
            val kb = pack.manifest
            return KeyboardColors(
                tray = if (pack.hasTray) 0x00000000 else kb.trayColor,
                key = kb.popup,
                keyBorder = (kb.ink and 0x00FFFFFF) or 0x40000000,
                modifier = kb.popup,
                ink = kb.ink,
                popup = kb.popup,
                pack = pack,
            )
        }

        /** As cores de [skin], ou as do [pack] quando há um (ele ganha: é a skin escolhida e carregada). */
        fun of(skin: KeyboardSkin, dark: Boolean, pack: PackKeyboard?): KeyboardColors = pack?.let(::forPack) ?: of(skin, dark)

        fun of(skin: KeyboardSkin, dark: Boolean): KeyboardColors = when (skin) {
            KeyboardSkin.SYSTEM -> if (dark) SYSTEM_DARK else SYSTEM_LIGHT
            KeyboardSkin.ATELIE -> if (dark) ATELIE_DARK else ATELIE_LIGHT
            KeyboardSkin.AERO -> AERO
            KeyboardSkin.GLITCH -> GLITCH
            KeyboardSkin.ROSE_GOLD -> ROSE_GOLD
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
