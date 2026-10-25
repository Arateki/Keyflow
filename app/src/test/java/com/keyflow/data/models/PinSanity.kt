package com.keyflow.data.models

/**
 * Heurísticas para reconhecer um commit ou SHA-256 **digitado à mão** em vez de lido da API do Hugging Face.
 *
 * Em 2026-10-24 achou-se 15 entradas do catálogo com pins assim (`1a2b3c4d5e6f…`, `a5f6e7d8c9b0…`, `c1b2a3d4e5f6…`,
 * `777779ff…` copiando o fim do SHA-256 do Qwen3). Hash de verdade é aleatório: não tem corrida de um mesmo
 * caractere, nem contagem de 1 em 1 (ou de 2 em 2) no alfabeto hexadecimal, nem dígito e letra alternados por dezenas
 * de caracteres, nem repetição periódica. Quem inventa um hash costuma cair em um desses padrões.
 *
 * É uma rede de segurança sem rede: não prova que o pin existe — quem prova é `scripts/verify-model-catalog.py`, que
 * confere cada URL, tamanho e SHA-256 na API do HF.
 */
internal object PinSanity {

    private const val ALPHABET = "0123456789abcdef"

    /** Mais que isto do mesmo caractere seguido: 6 seguidos aparecem em ~1 de cada 18.000 hashes aleatórios (5 seguidos, em 1 de 1.100: comum demais). */
    private const val MAX_REPEAT = 5

    /** Passos seguidos de +1 (ou -1): 6 passos = 7 caracteres, ~1 em 60.000 por hash aleatório. */
    private const val MAX_SEQUENCE_STEPS = 5

    /** Dígito e letra alternados: num hash aleatório, 20 seguidos aparecem em ~1 de cada 30.000. */
    private const val MAX_ALTERNATION = 19

    /** Fração de posições iguais à posição `lag` à frente: ~6% num hash aleatório. */
    private const val MAX_AUTOCORRELATION = 0.5

    /** O motivo de [hex] parecer inventado, ou `null` se não tem cara de digitado à mão. */
    fun suspicion(hex: String): String? {
        require(hex.all { it in ALPHABET }) { "não é hexadecimal minúsculo: $hex" }
        val repeat = longestRepeat(hex)
        if (repeat > MAX_REPEAT) return "o mesmo caractere $repeat vezes seguidas"
        for (stride in 1..2) {
            val steps = longestSequence(hex, stride)
            if (steps > MAX_SEQUENCE_STEPS) return "contagem de 1 em 1 por $steps passos (de $stride em $stride caracteres)"
        }
        val alternation = longestAlternation(hex)
        if (alternation > MAX_ALTERNATION) return "dígito e letra alternados por $alternation caracteres"
        val lag = (1..8).firstOrNull { autocorrelation(hex, it) > MAX_AUTOCORRELATION }
        if (lag != null) return "padrão que se repete a cada $lag caracteres"
        return null
    }

    /** O commit de uma URL `…/resolve/<commit>/<arquivo>`, ou `null` se a URL não tem esse formato. */
    fun commitOf(url: String): String? = COMMIT_URL.matchEntire(url)?.groupValues?.get(1)

    private val COMMIT_URL = Regex("https://huggingface\\.co/[^/]+/[^/]+/resolve/([0-9a-f]{40})/.+")

    /** Quantos caracteres iguais [a] e [b] têm em comum no começo. */
    fun sharedPrefix(a: String, b: String): Int = a.commonPrefixWith(b).length

    /** Quantos caracteres iguais [a] e [b] têm em comum no fim. */
    fun sharedSuffix(a: String, b: String): Int = a.commonSuffixWith(b).length

    private fun longestRepeat(hex: String): Int {
        var best = 1
        var run = 1
        for (i in 1 until hex.length) {
            run = if (hex[i] == hex[i - 1]) run + 1 else 1
            best = maxOf(best, run)
        }
        return best
    }

    /** Maior número de passos consecutivos de +1 ou de -1, olhando de [stride] em [stride] caracteres. */
    private fun longestSequence(hex: String, stride: Int): Int {
        var best = 0
        for (direction in intArrayOf(1, -1)) {
            for (start in 0 until stride) {
                var run = 0
                var i = start
                while (i + stride < hex.length) {
                    run = if (isStep(hex[i], hex[i + stride], direction)) run + 1 else 0
                    best = maxOf(best, run)
                    i += stride
                }
            }
        }
        return best
    }

    /**
     * Um passo na ordem do alfabeto hexadecimal (`…8 9 a b…`) ou na de cada classe (dígitos 0-9 dão a volta, letras
     * a-f dão a volta): quem inventa conta `a5 f6 e7 d8` com as letras para trás e os dígitos para a frente.
     */
    private fun isStep(from: Char, to: Char, direction: Int): Boolean {
        val a = ALPHABET.indexOf(from)
        val b = ALPHABET.indexOf(to)
        if (Math.floorMod(b - a, 16) == Math.floorMod(direction, 16)) return true
        val sameClass = (a < 10) == (b < 10)
        if (!sameClass) return false
        return if (a < 10) Math.floorMod(b - a, 10) == Math.floorMod(direction, 10)
        else Math.floorMod(b - a, 6) == Math.floorMod(direction, 6)
    }

    private fun longestAlternation(hex: String): Int {
        var best = 1
        var run = 1
        for (i in 1 until hex.length) {
            run = if (hex[i].isDigit() != hex[i - 1].isDigit()) run + 1 else 1
            best = maxOf(best, run)
        }
        return best
    }

    private fun autocorrelation(hex: String, lag: Int): Double {
        val pairs = hex.length - lag
        if (pairs <= 0) return 0.0
        return (0 until pairs).count { hex[it] == hex[it + lag] }.toDouble() / pairs
    }
}
