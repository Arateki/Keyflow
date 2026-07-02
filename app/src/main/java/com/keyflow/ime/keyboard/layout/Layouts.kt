package com.keyflow.ime.keyboard.layout

import com.keyflow.ime.keyboard.layout.KeyAction.Char as CharKey

/**
 * Layouts QWERTY por idioma (LAY-01..06). Uma única grade base; PT/EN/ES variam só o mapa de acentos, a
 * tecla `ñ` (ES, linha do meio) e `¿¡` (ES, camada símbolos). Geometria por **peso**, nunca px fixo — a
 * re-medida/resize fica a cargo de quem desenha (KC-T2).
 */
object Layouts {
    // Fileira de cima (Gboard/Samsung): long-press mostra o número junto dos acentos no mesmo popup —
    // não uma 5ª fileira fixa (decisão do Yan, 2026-06-30). Precisa vir antes de `cache` (ordem de
    // inicialização do object): `cache` já constrói os layouts que consultam este mapa.
    private val topRowDigits = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
    )

    /**
     * Layout para [locale] (ex.: "pt", "en", "es"); idioma desconhecido cai no EN (edge case do LAY-05).
     * [numberRowEnabled] (pedido do Yan, 2026-07-03): prepende uma fileira dedicada de dígitos acima do
     * "qwertyuiop" (estilo Gboard) — só na camada de letras, já que a de símbolos sempre teve números na
     * 1ª fileira. Sem cache: a construção é barata (algumas listas/mapas) e não é chamada por frame, só
     * na criação/reconfiguração do teclado.
     */
    fun forLocale(locale: String, numberRowEnabled: Boolean = false): KeyboardLayout = when (locale.lowercase()) {
        "pt" -> build(locale = "pt", accents = ptAccents(), includeEnye = false, numberRowEnabled = numberRowEnabled)
        "es" -> build(locale = "es", accents = esAccents(), includeEnye = true, numberRowEnabled = numberRowEnabled)
        else -> build(locale = "en", accents = enAccents(), includeEnye = false, numberRowEnabled = numberRowEnabled)
    }

    /**
     * Layout numérico (campo `TYPE_CLASS_NUMBER`/`TYPE_CLASS_PHONE` — NUM-01): grade "discador" 4x4 —
     * dígitos 3x3 + as duas teclas em volta do 0 (`⌫`/`⏎`) + uma 4ª coluna à direita com os símbolos mais
     * comuns de telefone/valor (`+`/`-`/`.`/`,`), espelhando o teclado numérico do Samsung (pedido do
     * Yan, 2026-07-01, testando no device físico). Sem shift/idioma/símbolos, e **travado** de propósito:
     * sem tecla `Layer` nenhuma, então não há como sair pra letras/símbolos manualmente — só o service
     * troca de volta ao focar um campo de texto normal.
     */
    private val numberLayoutCache: KeyboardLayout by lazy {
        val row1 = listOf("1", "2", "3", "+").map { KeyDef(CharKey(it), it) }
        val row2 = listOf("4", "5", "6", "-").map { KeyDef(CharKey(it), it) }
        val row3 = listOf("7", "8", "9", ".").map { KeyDef(CharKey(it), it) }
        val row4 = listOf(
            KeyDef(KeyAction.Backspace, "⌫"),
            KeyDef(CharKey("0"), "0"),
            KeyDef(KeyAction.Enter, "⏎"),
            KeyDef(CharKey(","), ","),
        )
        val layer = KeyboardLayer(LayerId.NUMBER, listOf(KeyRow(row1), KeyRow(row2), KeyRow(row3), KeyRow(row4)))
        KeyboardLayout(locale = "number", layers = mapOf(LayerId.NUMBER to layer), accents = AccentMap(emptyMap()))
    }

    fun numberLayout(): KeyboardLayout = numberLayoutCache

    private fun build(
        locale: String,
        accents: AccentMap,
        includeEnye: Boolean,
        numberRowEnabled: Boolean = false,
    ): KeyboardLayout {
        val space = spaceLabel(locale)
        val letters = lettersLayer(accents, includeEnye, space, numberRowEnabled)
        val symbols = symbolsLayer(includeInvertedPunctuation = locale == "es", spaceLabel = space)
        return KeyboardLayout(
            locale = locale,
            layers = mapOf(LayerId.LETTERS to letters, LayerId.SYMBOLS to symbols),
            accents = accents,
        )
    }

    /**
     * Label da tecla de espaço = nome do idioma ativo, igual o teclado do Samsung mostra (pedido do Yan,
     * 2026-07-03) — em vez do texto genérico "espaço".
     */
    private fun spaceLabel(locale: String): String = when (locale) {
        "pt" -> "Português"
        "es" -> "Español"
        else -> "English"
    }

    private fun charKey(
        letter: String,
        accents: AccentMap,
        weight: Float = 1f,
        extraVariant: String? = null,
    ): KeyDef {
        val variants = listOfNotNull(extraVariant) + accents.variantsFor(letter)
        return KeyDef(CharKey(letter, variants, digitHint = extraVariant), letter, weight)
    }

    /**
     * Fileira dedicada de dígitos (NUMROW-01, pedido do Yan, 2026-07-03), prepend na camada de letras
     * quando [Settings.keyboardNumberRowEnabled]. Mesma ordem/peso de [topRowDigits] (1234567890).
     */
    private fun numberRow(): KeyRow = KeyRow(topRowDigits.values.map { KeyDef(CharKey(it), it) })

    private fun lettersLayer(
        accents: AccentMap,
        includeEnye: Boolean,
        spaceLabel: String,
        numberRowEnabled: Boolean,
    ): KeyboardLayer {
        // Long-press de número na 1ª fileira só existe SEM a fileira dedicada (redundante com ela) —
        // pedido do Yan, 2026-07-03.
        val row1 = "qwertyuiop".map { letter ->
            val digit = if (numberRowEnabled) null else topRowDigits[letter.toString()]
            charKey(letter.toString(), accents, extraVariant = digit)
        }
        val row2Letters = "asdfghjkl" + if (includeEnye) "ñ" else ""
        val row2 = row2Letters.map { charKey(it.toString(), accents) }
        val row3 = listOf(KeyDef(KeyAction.Shift, "⇧", 1.5f)) +
            "zxcvbnm".map { charKey(it.toString(), accents) } +
            listOf(KeyDef(KeyAction.Backspace, "⌫", 1.5f))
        // 4ª fileira ao estilo Gboard: toggle ?123 + vírgula/espaço/ponto/enter — mic/config/colapsar
        // moraram pra faixa de sugestões (KeyFlowKeyboardView), então a action row de fora deixa de
        // existir no modo Canvas (decisão do Yan, 2026-07-01, testando no device físico).
        val row4 = listOf(
            KeyDef(KeyAction.Layer(LayerId.SYMBOLS), "?123", 1.5f),
            KeyDef(CharKey(","), ",", 1f),
            KeyDef(KeyAction.Space, spaceLabel, 4f),
            KeyDef(CharKey("."), ".", 1f),
            KeyDef(KeyAction.Enter, "⏎", 1.5f),
        )
        val rows = listOfNotNull(
            if (numberRowEnabled) numberRow() else null,
            KeyRow(row1),
            KeyRow(row2),
            KeyRow(row3),
            KeyRow(row4),
        )
        return KeyboardLayer(LayerId.LETTERS, rows)
    }

    private fun symbolsLayer(includeInvertedPunctuation: Boolean, spaceLabel: String): KeyboardLayer {
        val row1 = "1234567890".map { KeyDef(CharKey(it.toString()), it.toString()) }

        // row2/row3 têm a MESMA contagem de teclas (e pesos) da camada de letras correspondente — senão a
        // grade "pula" ao trocar de camada (bug relatado pelo Yan testando no device físico, 2026-07-01:
        // letras/símbolos tinham contagens diferentes por fileira, cada tecla ficava com largura diferente
        // e a coluna inteira realocava). O ES tem `ñ` a mais em letras.row2, então símbolos.row2 do ES
        // também ganha uma tecla a mais (mantém "/" aqui); PT/EN têm 1 a menos, então "/" muda pra row3.
        val row2Symbols = if (includeInvertedPunctuation) {
            listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/") // ES: 10, casa com "asdfghjklñ"
        } else {
            listOf("@", "#", "$", "_", "&", "-", "+", "(", ")") // PT/EN: 9, casa com "asdfghjkl"
        }
        val row2 = row2Symbols.map { KeyDef(CharKey(it), it) }

        // row3 das letras é sempre [1.5, 7×1, 1.5] (shift + 7 letras + backspace); espelha essa forma —
        // o slot largo inicial (onde letras tem o shift) leva "/" (PT/EN) ou "¿" (ES, junto com "¡" no
        // meio, mantendo `¿¡` na camada símbolos do ES).
        val (leading, middle) = if (includeInvertedPunctuation) {
            "¿" to listOf("\"", "'", ":", ";", "!", "?", "¡")
        } else {
            "/" to listOf("*", "\"", "'", ":", ";", "!", "?")
        }
        val row3 = listOf(KeyDef(CharKey(leading), leading, 1.5f)) +
            middle.map { KeyDef(CharKey(it), it) } +
            listOf(KeyDef(KeyAction.Backspace, "⌫", 1.5f))

        val row4 = listOf(
            KeyDef(KeyAction.Layer(LayerId.LETTERS), "ABC", 1.5f),
            KeyDef(CharKey(","), ",", 1f),
            KeyDef(KeyAction.Space, spaceLabel, 4f),
            KeyDef(CharKey("."), ".", 1f),
            KeyDef(KeyAction.Enter, "⏎", 1.5f),
        )
        return KeyboardLayer(LayerId.SYMBOLS, listOf(KeyRow(row1), KeyRow(row2), KeyRow(row3), KeyRow(row4)))
    }

    // Acentuação verificada (não fabricada): diacríticos padrão de cada idioma.
    private fun ptAccents() = AccentMap(
        mapOf(
            "a" to listOf("á", "à", "â", "ã", "ä"),
            "e" to listOf("é", "ê"),
            "i" to listOf("í"),
            "o" to listOf("ó", "ô", "õ"),
            "u" to listOf("ú", "ü"),
            "c" to listOf("ç"),
        ),
    )

    private fun esAccents() = AccentMap(
        mapOf(
            "a" to listOf("á"),
            "e" to listOf("é"),
            "i" to listOf("í"),
            "o" to listOf("ó"),
            "u" to listOf("ú", "ü"),
            "n" to listOf("ñ"),
        ),
    )

    private fun enAccents() = AccentMap(emptyMap())
}
