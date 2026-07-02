package com.keyflow.ime.keyboard.layout

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Modelo de layout data-driven (LAY-01..06, EXT-01). Lógica pura, sem Context. */
class LayoutsTest {

    private fun labelsOf(row: KeyRow): List<String> = row.keys.map { it.label }

    @Test
    fun `EN letters layer has the 4 QWERTY rows in Gboard order`() {
        val letters = Layouts.forLocale("en").layers.getValue(LayerId.LETTERS)
        assertThat(letters.rows).hasSize(4)

        assertThat(labelsOf(letters.rows[0])).containsExactly(
            "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
        ).inOrder()
        assertThat(labelsOf(letters.rows[1])).containsExactly(
            "a", "s", "d", "f", "g", "h", "j", "k", "l",
        ).inOrder()
    }

    @Test
    fun `EN letters row 3 has shift at start and backspace at end`() {
        val row3 = Layouts.forLocale("en").layers.getValue(LayerId.LETTERS).rows[2]
        assertThat(row3.keys.first().action).isEqualTo(KeyAction.Shift)
        assertThat(row3.keys.last().action).isEqualTo(KeyAction.Backspace)
        assertThat(labelsOf(row3).subList(1, row3.keys.size - 1)).containsExactly(
            "z", "x", "c", "v", "b", "n", "m",
        ).inOrder()
    }

    @Test
    fun `EN letters row 4 has the toggle plus comma, space, period and enter`() {
        // Mic/config/colapsar sobem pra faixa de sugestões (KeyFlowKeyboardView); a action row de fora
        // deixa de existir no modo Canvas — a 4ª fileira absorve vírgula/espaço/ponto/enter, igual Gboard
        // (decisão do Yan, 2026-07-01, testando no device físico).
        val row4 = Layouts.forLocale("en").layers.getValue(LayerId.LETTERS).rows[3]
        assertThat(labelsOf(row4)).containsExactly("?123", ",", "English", ".", "⏎").inOrder()
        assertThat(row4.keys[0].action).isEqualTo(KeyAction.Layer(LayerId.SYMBOLS))
        assertThat(row4.keys[1].action).isEqualTo(KeyAction.Char(","))
        assertThat(row4.keys[2].action).isEqualTo(KeyAction.Space)
        assertThat(row4.keys[3].action).isEqualTo(KeyAction.Char("."))
        assertThat(row4.keys[4].action).isEqualTo(KeyAction.Enter)
    }

    @Test
    fun `space key label is the language name, not the word 'espaco', on both layers`() {
        // Pedido do Yan, 2026-07-03: igual o teclado do Samsung mostra o idioma ativo na barra de espaço.
        assertThat(spaceLabelOf(Layouts.forLocale("pt"), LayerId.LETTERS)).isEqualTo("Português")
        assertThat(spaceLabelOf(Layouts.forLocale("en"), LayerId.LETTERS)).isEqualTo("English")
        assertThat(spaceLabelOf(Layouts.forLocale("es"), LayerId.LETTERS)).isEqualTo("Español")
        assertThat(spaceLabelOf(Layouts.forLocale("pt"), LayerId.SYMBOLS)).isEqualTo("Português")
        assertThat(spaceLabelOf(Layouts.forLocale("es"), LayerId.SYMBOLS)).isEqualTo("Español")
    }

    private fun spaceLabelOf(layout: KeyboardLayout, layerId: LayerId): String =
        layout.layers.getValue(layerId).rows.flatMap { it.keys }.first { it.action == KeyAction.Space }.label

    @Test
    fun `ES letters middle row adds nh after l`() {
        val row2 = Layouts.forLocale("es").layers.getValue(LayerId.LETTERS).rows[1]
        assertThat(labelsOf(row2)).containsExactly(
            "a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ",
        ).inOrder()
    }

    @Test
    fun `PT and EN letters middle row has no nh`() {
        assertThat(labelsOf(Layouts.forLocale("pt").layers.getValue(LayerId.LETTERS).rows[1])).doesNotContain("ñ")
        assertThat(labelsOf(Layouts.forLocale("en").layers.getValue(LayerId.LETTERS).rows[1])).doesNotContain("ñ")
    }

    @Test
    fun `unknown locale falls back to EN`() {
        assertThat(Layouts.forLocale("de")).isEqualTo(Layouts.forLocale("en"))
    }

    @Test
    fun `symbols layer has a digits row and a lone way back to letters`() {
        val symbols = Layouts.forLocale("en").layers.getValue(LayerId.SYMBOLS)
        assertThat(labelsOf(symbols.rows[0])).containsExactly(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        ).inOrder()
        val row4 = symbols.rows.last()
        assertThat(labelsOf(row4)).containsExactly("ABC", ",", "English", ".", "⏎").inOrder()
        assertThat(row4.keys[0].action).isEqualTo(KeyAction.Layer(LayerId.LETTERS))
    }

    @Test
    fun `ES symbols layer includes inverted punctuation`() {
        val symbolLabels = Layouts.forLocale("es").layers.getValue(LayerId.SYMBOLS).rows.flatMap(::labelsOf)
        assertThat(symbolLabels).containsAtLeast("¿", "¡")
    }

    @Test
    fun `EN symbols layer has no inverted punctuation`() {
        val symbolLabels = Layouts.forLocale("en").layers.getValue(LayerId.SYMBOLS).rows.flatMap(::labelsOf)
        assertThat(symbolLabels).containsNoneOf("¿", "¡")
    }

    @Test
    fun `EN top row keys carry the Gboard-style digit as the first long-press variant`() {
        val row1 = Layouts.forLocale("en").layers.getValue(LayerId.LETTERS).rows[0]
        val digitsInOrder = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        row1.keys.forEachIndexed { index, key ->
            val action = key.action as KeyAction.Char
            assertThat(action.variants.first()).isEqualTo(digitsInOrder[index])
        }
    }

    @Test
    fun `PT top row merges the digit before the language accents`() {
        val row1 = Layouts.forLocale("pt").layers.getValue(LayerId.LETTERS).rows[0]
        val eKey = row1.keys[2].action as KeyAction.Char // q w e -> índice 2
        assertThat(eKey.text).isEqualTo("e")
        assertThat(eKey.variants).containsExactly("3", "é", "ê").inOrder()
    }

    @Test
    fun `PT accents cover a and c`() {
        val accents = Layouts.forLocale("pt").accents
        assertThat(accents.variantsFor("a")).containsExactly("á", "à", "â", "ã", "ä").inOrder()
        assertThat(accents.variantsFor("c")).containsExactly("ç")
    }

    @Test
    fun `ES accents include nh variant even though it also has a dedicated key`() {
        assertThat(Layouts.forLocale("es").accents.variantsFor("n")).containsExactly("ñ")
    }

    @Test
    fun `EN accents are minimal (no variants for plain letters)`() {
        assertThat(Layouts.forLocale("en").accents.variantsFor("a")).isEmpty()
    }

    @Test
    fun `accents for a letter with no mapped variants is empty, not a crash`() {
        assertThat(Layouts.forLocale("en").accents.variantsFor("k")).isEmpty()
    }

    @Test
    fun `withLanguageKey false keeps row 4 without a globe`() {
        val layout = Layouts.forLocale("en").withLanguageKey(show = false)
        val row4 = layout.layers.getValue(LayerId.LETTERS).rows[3]
        assertThat(row4.keys).hasSize(5)
        assertThat(row4.keys[0].action).isEqualTo(KeyAction.Layer(LayerId.SYMBOLS))
    }

    @Test
    fun `withLanguageKey true prepends a globe key to row 4 of every layer`() {
        val layout = Layouts.forLocale("en").withLanguageKey(show = true)

        val lettersRow4 = layout.layers.getValue(LayerId.LETTERS).rows[3]
        assertThat(lettersRow4.keys).hasSize(6)
        assertThat(lettersRow4.keys[0].action).isEqualTo(KeyAction.Language)
        assertThat(lettersRow4.keys[1].action).isEqualTo(KeyAction.Layer(LayerId.SYMBOLS))

        val symbolsRow4 = layout.layers.getValue(LayerId.SYMBOLS).rows[3]
        assertThat(symbolsRow4.keys).hasSize(6)
        assertThat(symbolsRow4.keys[0].action).isEqualTo(KeyAction.Language)
        assertThat(symbolsRow4.keys[1].action).isEqualTo(KeyAction.Layer(LayerId.LETTERS))
    }

    @Test
    fun `withLanguageKey does not mutate the cached base layout`() {
        Layouts.forLocale("en").withLanguageKey(show = true)

        val untouched = Layouts.forLocale("en").layers.getValue(LayerId.LETTERS).rows[3]
        assertThat(untouched.keys).hasSize(5)
    }

    @Test
    fun `number layout is dialer-style 4x4 (digits + a right column of symbols), locked`() {
        // Espelha o teclado numérico do Samsung (pedido do Yan, 2026-07-01): discador 3x3 + uma 4ª
        // coluna à direita com símbolos comuns de valor/telefone, além das duas teclas em volta do 0.
        val layer = Layouts.numberLayout().layers.getValue(LayerId.NUMBER)
        assertThat(layer.rows).hasSize(4)
        assertThat(labelsOf(layer.rows[0])).containsExactly("1", "2", "3", "+").inOrder()
        assertThat(labelsOf(layer.rows[1])).containsExactly("4", "5", "6", "-").inOrder()
        assertThat(labelsOf(layer.rows[2])).containsExactly("7", "8", "9", ".").inOrder()
        assertThat(labelsOf(layer.rows[3])).containsExactly("⌫", "0", "⏎", ",").inOrder()

        val allActions = layer.rows.flatMap { it.keys.map(KeyDef::action) }
        assertThat(allActions).doesNotContain(KeyAction.Shift)
        assertThat(allActions.filterIsInstance<KeyAction.Layer>()).isEmpty()
    }

    @Test
    fun `number layout only has the NUMBER layer`() {
        assertThat(Layouts.numberLayout().layers.keys).containsExactly(LayerId.NUMBER)
    }

    @Test
    fun `EN top row digit hint mirrors the long-press variant when the number row is off`() {
        // Estilo Gboard (pedido do Yan, 2026-07-03): número pequeno no canto da tecla, só quando o
        // long-press ainda é o único jeito de digitar o dígito (sem a fileira dedicada).
        val row1 = Layouts.forLocale("en", numberRowEnabled = false).layers.getValue(LayerId.LETTERS).rows[0]
        val digitsInOrder = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        row1.keys.forEachIndexed { index, key ->
            val action = key.action as KeyAction.Char
            assertThat(action.digitHint).isEqualTo(digitsInOrder[index])
        }
    }

    @Test
    fun `numberRowEnabled prepends a dedicated digit row before qwertyuiop, letters only`() {
        val layout = Layouts.forLocale("en", numberRowEnabled = true)

        val letters = layout.layers.getValue(LayerId.LETTERS)
        assertThat(letters.rows).hasSize(5)
        assertThat(labelsOf(letters.rows[0])).containsExactly(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        ).inOrder()
        assertThat(labelsOf(letters.rows[1])).containsExactly(
            "q", "w", "e", "r", "t", "y", "u", "i", "o", "p",
        ).inOrder()

        // Símbolos já tem números na 1ª fileira — não ganha uma 2ª fileira redundante.
        val symbols = layout.layers.getValue(LayerId.SYMBOLS)
        assertThat(symbols.rows).hasSize(4)
    }

    @Test
    fun `numberRowEnabled disables the long-press digit on the qwertyuiop row (redundant with the new row)`() {
        val row1 = Layouts.forLocale("en", numberRowEnabled = true).layers.getValue(LayerId.LETTERS).rows[1]
        row1.keys.forEach { key ->
            val action = key.action as KeyAction.Char
            assertThat(action.digitHint).isNull()
            assertThat(action.variants).isEmpty()
        }
    }

    @Test
    fun `numberRowEnabled false is the default and keeps the original 4-row letters layer`() {
        assertThat(Layouts.forLocale("en")).isEqualTo(Layouts.forLocale("en", numberRowEnabled = false))
    }

    @Test
    fun `letters and symbols layers share the same row shape, so switching does not reflow keys`() {
        // Bug relatado pelo Yan testando no device físico (2026-07-01): row2/row3 tinham contagens
        // diferentes de teclas entre letras e símbolos, então as colunas realocavam ao trocar de camada.
        for (locale in listOf("pt", "en", "es")) {
            val layout = Layouts.forLocale(locale)
            val letters = layout.layers.getValue(LayerId.LETTERS)
            val symbols = layout.layers.getValue(LayerId.SYMBOLS)

            assertThat(symbols.rows).hasSize(letters.rows.size)
            letters.rows.indices.forEach { i ->
                val lettersWeights = letters.rows[i].keys.map { it.widthWeight }
                val symbolsWeights = symbols.rows[i].keys.map { it.widthWeight }
                assertWithMessage("locale=$locale row=$i").that(symbolsWeights).isEqualTo(lettersWeights)
            }
        }
    }
}
