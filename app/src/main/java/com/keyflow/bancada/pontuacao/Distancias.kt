package com.keyflow.bancada.pontuacao

import kotlin.math.max
import kotlin.math.min

/** Distância de edição (Levenshtein) sobre qualquer sequência: palavras para o WER, caracteres para o CER. */
object Levenshtein {
    fun <T> distancia(a: List<T>, b: List<T>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var anterior = IntArray(b.size + 1) { it }
        var atual = IntArray(b.size + 1)
        for (i in 1..a.size) {
            atual[0] = i
            for (j in 1..b.size) {
                val custo = if (a[i - 1] == b[j - 1]) 0 else 1
                atual[j] = min(min(atual[j - 1] + 1, anterior[j] + 1), anterior[j - 1] + custo)
            }
            val troca = anterior
            anterior = atual
            atual = troca
        }
        return anterior[b.size]
    }
}

/**
 * Taxa de erro de palavras: distância ÷ nº de palavras do esperado. Pode passar de 1 (muita inserção); a nota é `1 − taxa` limitada
 * a [0, 1]. Esperado vazio: 0 se a saída também é vazia, senão 1.
 */
object Wer {
    fun taxa(esperado: List<String>, obtido: List<String>): Double {
        if (esperado.isEmpty()) return if (obtido.isEmpty()) 0.0 else 1.0
        return Levenshtein.distancia(esperado, obtido).toDouble() / esperado.size
    }

    fun nota(esperado: List<String>, obtido: List<String>): Double = (1.0 - taxa(esperado, obtido)).coerceIn(0.0, 1.0)
}

/** Taxa de erro de caracteres (o `ocr` e a `fala`): sobre o texto normalizado, espaços colapsados. */
object Cer {
    fun taxa(esperado: String, obtido: String): Double {
        val e = Normalizacao.texto(esperado).toList()
        val o = Normalizacao.texto(obtido).toList()
        if (e.isEmpty()) return if (o.isEmpty()) 0.0 else 1.0
        return Levenshtein.distancia(e, o).toDouble() / e.size
    }

    fun nota(esperado: String, obtido: String): Double = (1.0 - taxa(esperado, obtido)).coerceIn(0.0, 1.0)
}

/** F1 sobre multiconjuntos: precisão e revocação pela interseção contada. Os dois vazios valem 1; um só vazio vale 0. */
object F1 {
    fun <T> multiconjunto(esperado: Collection<T>, obtido: Collection<T>): Double {
        if (esperado.isEmpty() && obtido.isEmpty()) return 1.0
        if (esperado.isEmpty() || obtido.isEmpty()) return 0.0
        val contagemEsperado = esperado.groupingBy { it }.eachCount()
        val contagemObtido = obtido.groupingBy { it }.eachCount()
        val verdadeiros = contagemObtido.entries.sumOf { (k, n) -> min(n, contagemEsperado[k] ?: 0) }
        if (verdadeiros == 0) return 0.0
        val precisao = verdadeiros.toDouble() / obtido.size
        val revocacao = verdadeiros.toDouble() / esperado.size
        return 2 * precisao * revocacao / (precisao + revocacao)
    }
}

/**
 * chrF (Popović, 2015): F-beta (β = 2, a revocação pesa mais) sobre n-gramas de caracteres de 1 a 6, espaços ignorados, médias de
 * precisão e revocação sobre as ordens. Idênticos valem 1; sem nenhum n-grama em comum, 0. Usado pela `traducao` (P2).
 */
object ChrF {
    const val ORDEM_MAXIMA = 6
    private const val BETA = 2.0

    fun nota(referencia: String, hipotese: String): Double {
        val r = referencia.filterNot { it.isWhitespace() }
        val h = hipotese.filterNot { it.isWhitespace() }
        if (r.isEmpty() && h.isEmpty()) return 1.0
        if (r.isEmpty() || h.isEmpty()) return 0.0
        var somaPrecisao = 0.0
        var somaRevocacao = 0.0
        var ordens = 0
        for (n in 1..ORDEM_MAXIMA) {
            val ngramasR = ngramas(r, n)
            val ngramasH = ngramas(h, n)
            if (ngramasR.isEmpty() || ngramasH.isEmpty()) continue
            val comuns = ngramasH.entries.sumOf { (g, c) -> min(c, ngramasR[g] ?: 0) }
            somaPrecisao += comuns.toDouble() / ngramasH.values.sum()
            somaRevocacao += comuns.toDouble() / ngramasR.values.sum()
            ordens++
        }
        if (ordens == 0) return 0.0
        val precisao = somaPrecisao / ordens
        val revocacao = somaRevocacao / ordens
        if (precisao + revocacao == 0.0) return 0.0
        val b2 = BETA * BETA
        return max(0.0, (1 + b2) * precisao * revocacao / (b2 * precisao + revocacao)).coerceAtMost(1.0)
    }

    private fun ngramas(texto: String, n: Int): Map<String, Int> {
        if (texto.length < n) return emptyMap()
        val contagem = HashMap<String, Int>()
        for (i in 0..texto.length - n) contagem.merge(texto.substring(i, i + n), 1, Int::plus)
        return contagem
    }
}
