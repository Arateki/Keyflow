package com.keyflow.ime.keyboard.dictionary

import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.BitSet

/**
 * Dicionário do corretor em memória, no formato dos `assets/dict/<idioma>.tsv` (ver
 * `scripts/build-dictionaries.py`): uma palavra por linha, `palavra<TAB>freq[<TAB>o]`. Puro (JVM).
 *
 * Tudo em arrays contíguos (texto de todas as palavras num `CharArray` só, mais os índices), e não em
 * 100 mil `String`s e mapas: roda no processo do teclado, que divide memória com o resto do app.
 * A busca é pela forma "dobrada" ([fold]: minúscula e sem acento), que é como o dedo digita.
 */
class WordList private constructor(
    private val text: CharArray,
    private val starts: IntArray,
    /** Formas dobradas de todas as palavras, em sequência — o [Suggester] lê direto daqui no laço quente. */
    internal val folded: CharArray,
    /** Onde começa a forma dobrada de cada palavra em [folded] (e, em `i + 1`, onde termina). */
    internal val foldedStarts: IntArray,
    private val freqs: IntArray,
    private val offensive: BitSet,
    /** Índices das palavras em ordem alfabética da forma dobrada (busca exata e por prefixo). */
    private val byFolded: IntArray,
    /** Índices por tamanho e 1ª letra da forma dobrada ([bucketOf]). */
    private val byLengthAndFirst: Array<IntArray>,
    /** O arquivo já veio em ordem alfabética da forma dobrada (os assets vêm; ver [parse]). */
    internal val arrivedSorted: Boolean,
) {
    val size: Int get() = freqs.size

    fun word(i: Int): String = String(text, starts[i], starts[i + 1] - starts[i])

    fun foldedWord(i: Int): String = String(folded, foldedStarts[i], foldedLength(i))

    fun foldedLength(i: Int): Int = foldedStarts[i + 1] - foldedStarts[i]

    fun foldedChar(i: Int, at: Int): Char = folded[foldedStarts[i] + at]

    fun freq(i: Int): Int = freqs[i]

    /** A lista marca a palavra como possivelmente ofensiva: vale como palavra, mas não é oferecida. */
    fun isOffensive(i: Int): Boolean = offensive[i]

    /** Palavras com o tamanho dobrado [length] que começam por [first]. */
    fun withFoldedLengthAndFirst(length: Int, first: Char): IntArray =
        byLengthAndFirst.getOrNull(bucketOf(length, first)) ?: EMPTY

    /** Palavras cuja forma dobrada é exatamente [key], da mais frequente para a menos. */
    fun foldEquals(key: String): List<Int> {
        val range = prefixRange(key)
        return range.map { byFolded[it] }.filter { foldedLength(it) == key.length }.sortedByDescending { freqs[it] }
    }

    /** Palavras cuja forma dobrada começa com [prefix], em ordem alfabética. */
    fun withFoldedPrefix(prefix: String): Sequence<Int> = prefixRange(prefix).asSequence().map { byFolded[it] }

    private fun prefixRange(prefix: String): IntRange {
        val first = lowerBound(prefix, prefixOnly = false)
        val end = lowerBound(prefix, prefixOnly = true)
        return first until end
    }

    /**
     * Primeira posição de [byFolded] cuja forma dobrada é ≥ [key]; com [prefixOnly], a primeira que já não
     * começa com [key] (fim da faixa do prefixo).
     */
    private fun lowerBound(key: String, prefixOnly: Boolean): Int {
        var lo = 0
        var hi = byFolded.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val cmp = comparePrefix(byFolded[mid], key)
            val goRight = if (prefixOnly) cmp <= 0 else cmp < 0
            if (goRight) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Compara a forma dobrada de [i] com [key], tratando "começa com [key]" como igual (0). */
    private fun comparePrefix(i: Int, key: String): Int {
        val start = foldedStarts[i]
        val length = foldedLength(i)
        for (j in key.indices) {
            if (j >= length) return -1
            val diff = folded[start + j] - key[j]
            if (diff != 0) return diff
        }
        return 0
    }

    companion object {
        private val EMPTY = IntArray(0)
        private const val LETTERS = 27 // a–z e "outra"

        private fun bucketOf(length: Int, first: Char): Int =
            length * LETTERS + if (first in 'a'..'z') first - 'a' else LETTERS - 1

        /** Minúscula e sem acento/cedilha/til — como a palavra sai do dedo sem as teclas de acento. */
        fun fold(word: String): String {
            // A maioria das palavras já é a–z minúsculo: nada a fazer.
            if (word.all { it in 'a'..'z' }) return word
            val decomposed = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD)
            val out = StringBuilder(decomposed.length)
            for (c in decomposed) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) out.append(c)
            return out.toString()
        }

        /**
         * Lê as linhas `palavra<TAB>freq[<TAB>o]`. Os assets vêm em ordem alfabética da forma dobrada
         * (`scripts/build-dictionaries.py`); fora dela (ex.: um dicionário de teste), ordena aqui.
         */
        fun parse(lines: Sequence<String>): WordList {
            val text = StringBuilder(1 shl 20)
            val foldedText = StringBuilder(1 shl 20)
            var starts = IntArray(1024)
            var foldedStarts = IntArray(1024)
            var freqs = IntArray(1024)
            val offensive = BitSet()
            var count = 0
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val tab2 = line.indexOf('\t', tab + 1)
                val freq = line.substring(tab + 1, if (tab2 < 0) line.length else tab2).toIntOrNull() ?: continue
                if (count + 1 >= starts.size) {
                    starts = starts.copyOf(starts.size * 2)
                    foldedStarts = foldedStarts.copyOf(foldedStarts.size * 2)
                    freqs = freqs.copyOf(freqs.size * 2)
                }
                if (tab2 > 0 && line.regionMatches(tab2 + 1, "o", 0, 1)) offensive.set(count)
                val word = line.substring(0, tab)
                starts[count] = text.length
                text.append(word)
                foldedStarts[count] = foldedText.length
                foldedText.append(fold(word))
                freqs[count] = freq
                count++
            }
            starts[count] = text.length
            foldedStarts[count] = foldedText.length
            return build(
                text = CharArray(text.length) { text[it] },
                starts = starts.copyOf(count + 1),
                folded = CharArray(foldedText.length) { foldedText[it] },
                fStarts = foldedStarts.copyOf(count + 1),
                freqs = freqs.copyOf(count),
                offensive = offensive,
                knownSorted = false,
            )
        }

        /**
         * Lê o `assets/dict/<idioma>.kfd` que o `scripts/build-dictionaries.py` gera: blocos contíguos
         * (texto em Latin-1, tamanhos e frequências em bytes), já em ordem alfabética da forma dobrada.
         * Copiar blocos no lugar de interpretar 100 mil linhas: a carga no A32 ia de 1,2 a 6 s.
         */
        fun readBinary(bytes: ByteArray): WordList {
            require(bytes.size >= HEADER_BYTES && String(bytes, 0, 4, Charsets.US_ASCII) == MAGIC) { "não é um .kfd" }
            val header = ByteBuffer.wrap(bytes, 4, 12)
            val n = header.int
            val textBytes = header.int
            val foldedBytes = header.int
            var pos = HEADER_BYTES
            val text = String(bytes, pos, textBytes, Charsets.ISO_8859_1).toCharArray()
            pos += textBytes
            val folded = String(bytes, pos, foldedBytes, Charsets.ISO_8859_1).toCharArray()
            pos += foldedBytes
            val starts = IntArray(n + 1)
            for (i in 0 until n) starts[i + 1] = starts[i] + (bytes[pos + i].toInt() and 0xFF)
            pos += n
            val fStarts = IntArray(n + 1)
            for (i in 0 until n) fStarts[i + 1] = fStarts[i] + (bytes[pos + i].toInt() and 0xFF)
            pos += n
            val freqs = IntArray(n) { bytes[pos + it].toInt() and 0xFF }
            pos += n
            val offensive = BitSet.valueOf(bytes.copyOfRange(pos, pos + (n + 7) / 8))
            return build(text, starts, folded, fStarts, freqs, offensive, knownSorted = true)
        }

        private const val MAGIC = "KFD1"
        private const val HEADER_BYTES = 16

        private fun build(
            text: CharArray,
            starts: IntArray,
            folded: CharArray,
            fStarts: IntArray,
            freqs: IntArray,
            offensive: BitSet,
            knownSorted: Boolean,
        ): WordList {
            val count = freqs.size

            fun compare(a: Int, b: Int): Int {
                val la = fStarts[a + 1] - fStarts[a]
                val lb = fStarts[b + 1] - fStarts[b]
                for (j in 0 until minOf(la, lb)) {
                    val diff = folded[fStarts[a] + j] - folded[fStarts[b] + j]
                    if (diff != 0) return diff
                }
                return la - lb
            }
            val alreadySorted = knownSorted || (1 until count).all { compare(it - 1, it) <= 0 }
            val byFolded = if (alreadySorted) {
                IntArray(count) { it }
            } else {
                (0 until count).sortedWith { a, b -> compare(a, b) }.toIntArray()
            }

            // Baldes por tamanho e 1ª letra: conta, depois preenche — sem listas de Integer.
            var maxLength = 0
            for (i in 0 until count) maxLength = maxOf(maxLength, fStarts[i + 1] - fStarts[i])
            val sizes = IntArray((maxLength + 1) * LETTERS)
            for (i in 0 until count) {
                val length = fStarts[i + 1] - fStarts[i]
                if (length > 0) sizes[bucketOf(length, folded[fStarts[i]])]++
            }
            val buckets = Array(sizes.size) { IntArray(sizes[it]) }
            val filled = IntArray(sizes.size)
            for (i in 0 until count) {
                val length = fStarts[i + 1] - fStarts[i]
                if (length == 0) continue
                val bucket = bucketOf(length, folded[fStarts[i]])
                buckets[bucket][filled[bucket]++] = i
            }
            return WordList(
                text = text,
                starts = starts,
                folded = folded,
                foldedStarts = fStarts,
                freqs = freqs,
                offensive = offensive,
                byFolded = byFolded,
                byLengthAndFirst = buckets,
                arrivedSorted = alreadySorted,
            )
        }
    }
}
