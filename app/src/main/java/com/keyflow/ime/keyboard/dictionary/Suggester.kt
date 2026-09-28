package com.keyflow.ime.keyboard.dictionary

import kotlin.math.abs

/** O que a faixa mostra para a palavra digitada, e por qual trocar no espaço (`null` = não trocar). */
data class Suggestions(val words: List<String>, val autoCorrect: String?) {
    companion object {
        val EMPTY = Suggestions(emptyList(), null)
    }
}

/**
 * Corretor sobre um [WordList] (Q9): correções por distância de edição — tecla vizinha custa metade —,
 * acento e maiúscula que faltaram, e complementos pelo prefixo. Puro (JVM).
 *
 * Troca no espaço só com confiança alta: palavra que não está no dicionário e uma candidata que ganha
 * com folga — acento/maiúscula que faltou, ou 1 erro numa palavra frequente; 2 erros só em palavra
 * longa e muito comum. Na dúvida, só sugere.
 *
 * Não é thread-safe (reaproveita buffers da distância): use de uma thread só.
 */
class Suggester(private val words: WordList) {

    fun suggest(typed: String): Suggestions {
        if (typed.isEmpty() || typed.any { !it.isLetter() && it != '\'' && it != '-' }) return Suggestions.EMPTY
        val lower = typed.lowercase()
        val key = WordList.fold(typed)

        val sameLetters = words.foldEquals(key) // mesmas letras: acento ou maiúscula podem diferir
        val inDictionary = sameLetters.any { words.word(it) == lower || words.word(it) == typed }
        val corrections = if (key.length >= 2) corrections(key) else emptyList()
        val completions = if (key.length >= 2) completions(key) else emptyList()

        val autoCorrect = when {
            inDictionary || typed.length < 2 -> null
            // Mesmas letras, só faltou acento ou maiúscula ("nao" → "não", "brasil" → "Brasil").
            sameLetters.isNotEmpty() -> words.word(sameLetters.first())
            else -> confident(corrections, key)?.let { words.word(it.index) }
        }
        val typedFreq = sameLetters.firstOrNull { words.word(it) == lower }?.let(words::freq) ?: 0

        val ordered = buildList {
            autoCorrect?.let { add(it) }
            // Palavra válida: a forma acentuada mais usada ainda aparece ("esta" → "está").
            sameLetters.filter { words.freq(it) > typedFreq }.forEach { add(words.word(it)) }
            if (!inDictionary) corrections.forEach { add(words.word(it.index)) }
            completions.forEach { add(words.word(it)) }
        }.map { applyCase(typed, it) }
            .distinctBy { it.lowercase() }
            .filter { !it.equals(typed, ignoreCase = false) }
            .take(MAX_SUGGESTIONS)
            .toMutableList()
        // Vai trocar: a palavra como foi digitada fica na faixa, para quem quiser mantê-la.
        if (autoCorrect != null && ordered.none { it == typed }) {
            if (ordered.size == MAX_SUGGESTIONS) ordered.removeAt(ordered.lastIndex)
            ordered += typed
        }
        return Suggestions(ordered, autoCorrect?.let { applyCase(typed, it) })
    }

    private class Candidate(val index: Int, val cost: Int, val score: Int)

    /**
     * Candidatas a até 1 erro (palavras curtas) ou 2, em custo de meia-edição, da melhor para a pior. Só
     * entre as que começam pela 1ª letra digitada, por uma vizinha dela ou pela 2ª (letras trocadas): erro
     * na 1ª letra é o mais raro, e comparar com todas as palavras do tamanho levava ~5× mais tempo.
     */
    private fun corrections(key: String): List<Candidate> {
        val maxEdits = if (key.length <= 4) 1 else 2
        val limit = maxEdits * FULL
        val firsts = buildSet {
            add(key[0])
            addAll(Keyboard.neighbors(key[0]))
            if (key.length > 1) add(key[1])
        }
        val found = ArrayList<Candidate>()
        for (length in (key.length - maxEdits)..(key.length + maxEdits)) {
            if (length < 1) continue
            for (first in firsts) for (i in words.withFoldedLengthAndFirst(length, first)) {
                if (words.isOffensive(i)) continue
                val cost = distance(key, i, limit)
                if (cost == 0 || cost > limit) continue // 0 = mesmas letras, tratado à parte
                found += Candidate(i, cost, words.freq(i) - cost * COST_WEIGHT)
            }
        }
        return found.sortedByDescending { it.score }.take(MAX_SUGGESTIONS)
    }

    /** Uma candidata que ganha com folga, com frequência bastante para o número de erros. */
    private fun confident(candidates: List<Candidate>, key: String): Candidate? {
        val best = candidates.firstOrNull() ?: return null
        val margin = best.score - (candidates.getOrNull(1)?.score ?: Int.MIN_VALUE / 2)
        return when {
            best.cost <= FULL -> best.takeIf { words.freq(it.index) >= 40 && margin >= 15 }
            else -> best.takeIf { key.length >= 6 && words.freq(it.index) >= 90 && margin >= 30 }
        }
    }

    /** Complementos pelo prefixo, mais frequentes primeiro. */
    private fun completions(key: String): List<Int> =
        words.withFoldedPrefix(key)
            .filter { words.foldedLength(it) > key.length && !words.isOffensive(it) }
            .sortedByDescending { words.freq(it) }
            .take(MAX_SUGGESTIONS)
            .toList()

    // Linhas da tabela da distância, reaproveitadas entre as dezenas de milhares de comparações de uma
    // sugestão (alocar três vetores por palavra comparada pesava no A32). Um Suggester por thread.
    private var prev2 = IntArray(64)
    private var prev = IntArray(64)
    private var cur = IntArray(64)

    /**
     * Damerau–Levenshtein em meias-edições ([FULL] = 1 edição; trocar por tecla vizinha custa [HALF]),
     * parando assim que passa de [limit]. Lê a forma dobrada direto de [WordList.folded].
     */
    private fun distance(key: String, i: Int, limit: Int): Int {
        val folded = words.folded
        val start = words.foldedStarts[i]
        val n = key.length
        val m = words.foldedStarts[i + 1] - start
        if (abs(n - m) * FULL > limit) return limit + 1
        if (prev.size < m + 1) {
            prev2 = IntArray(m + 1)
            prev = IntArray(m + 1)
            cur = IntArray(m + 1)
        }
        var p2 = prev2
        var p1 = prev
        var c = cur
        for (b in 0..m) p1[b] = b * FULL
        for (a in 1..n) {
            c[0] = a * FULL
            var rowMin = c[0]
            val ca = key[a - 1]
            for (b in 1..m) {
                val cb = folded[start + b - 1]
                val sub = if (ca == cb) 0 else if (Keyboard.adjacent(ca, cb)) HALF else FULL
                var best = minOf(p1[b] + FULL, c[b - 1] + FULL, p1[b - 1] + sub)
                if (a > 1 && b > 1 && ca == folded[start + b - 2] && key[a - 2] == cb) {
                    best = minOf(best, p2[b - 2] + FULL)
                }
                c[b] = best
                if (best < rowMin) rowMin = best
            }
            if (rowMin > limit) return limit + 1
            val recycled = p2
            p2 = p1
            p1 = c
            c = recycled
        }
        return p1[m]
    }

    /** QWERTY: letras vizinhas na mesma fileira ou na de cima/de baixo — tabela 26×26 pré-calculada. */
    private object Keyboard {
        private val table = BooleanArray(26 * 26).also { table ->
            val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
            val position = HashMap<Char, Pair<Int, Float>>()
            rows.forEachIndexed { row, (letters, offset) ->
                letters.forEachIndexed { col, c -> position[c] = row to col + offset }
            }
            for ((a, pa) in position) for ((b, pb) in position) {
                if (a != b && abs(pa.first - pb.first) <= 1 && abs(pa.second - pb.second) <= 1f) {
                    table[(a - 'a') * 26 + (b - 'a')] = true
                }
            }
        }

        fun neighbors(c: Char): List<Char> = ('a'..'z').filter { adjacent(c, it) }

        fun adjacent(a: Char, b: Char): Boolean =
            a in 'a'..'z' && b in 'a'..'z' && table[(a - 'a') * 26 + (b - 'a')]
    }

    private companion object {
        const val MAX_SUGGESTIONS = 3
        const val FULL = 2
        const val HALF = 1
        // Peso de cada meia-edição contra a frequência (1..255): uma edição inteira "custa" 90.
        const val COST_WEIGHT = 45

        /** Maiúsculas como foram digitadas: tudo maiúsculo, só a inicial, ou como o dicionário tem. */
        fun applyCase(typed: String, word: String): String = when {
            typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
