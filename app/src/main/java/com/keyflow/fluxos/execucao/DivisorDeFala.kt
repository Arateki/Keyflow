package com.keyflow.fluxos.execucao

import java.text.BreakIterator
import java.util.Locale

/**
 * Divide o texto longo da etapa Falar em trechos para falar em sequência (FLX-22): por frase (`.`, `!`, `?`, `…` e quebra de
 * linha), juntando frases vizinhas enquanto couberem no limite — texto curto é **um** trecho, e nenhum motor recebe mais do que
 * [MAX_PADRAO] caracteres de uma vez.
 *
 * Frase maior que o limite é cortada no último espaço que cabe (sem partir palavra); sem espaço, na última fronteira de caractere
 * que cabe — nunca no meio de um emoji (par substituto) nem entre a letra e o acento combinado. Não perde nem repete caractere: só
 * o espaço das pontas de cada trecho sai. JVM puro.
 */
object DivisorDeFala {
    const val MAX_PADRAO = 800

    /** O que fica colado ao fim da frase: "Vamos!” (ele disse)." */
    private const val FECHAMENTOS = "\"”’»)]}"
    private const val TERMINADORES = ".!?…"

    fun dividir(texto: String, max: Int = MAX_PADRAO): List<String> {
        require(max > 0) { "max deve ser positivo: $max" }
        val trechos = ArrayList<String>()
        var inicio = -1 // o trecho em montagem é texto[inicio, fim)
        var fim = -1
        for ((a, b) in frases(texto)) {
            when {
                b - a > max -> {
                    if (inicio >= 0) trechos += texto.substring(inicio, fim)
                    inicio = -1
                    trechos += cortar(texto.substring(a, b), max)
                }
                inicio < 0 -> { inicio = a; fim = b }
                b - inicio <= max -> fim = b
                else -> { trechos += texto.substring(inicio, fim); inicio = a; fim = b }
            }
        }
        if (inicio >= 0) trechos += texto.substring(inicio, fim)
        return trechos
    }

    /** As frases como faixas `[início, fim)` do texto, sem o espaço das pontas. */
    private fun frases(t: String): List<Pair<Int, Int>> {
        val saida = ArrayList<Pair<Int, Int>>()
        fun acrescentar(de: Int, ate: Int) {
            var a = de
            var b = ate
            while (a < b && t[a].isWhitespace()) a++
            while (b > a && t[b - 1].isWhitespace()) b--
            if (a < b) saida += a to b
        }
        var inicio = 0
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '\n' || c == '\r') {
                acrescentar(inicio, i)
                inicio = i + 1
                i++
            } else if (c in TERMINADORES) {
                var j = i + 1
                while (j < t.length && t[j] in TERMINADORES) j++
                while (j < t.length && t[j] in FECHAMENTOS) j++
                // "3.14" e "2.0.1" não terminam frase: depois do ponto vem espaço (ou o fim), não letra ou dígito.
                if (j >= t.length || t[j].isWhitespace()) {
                    acrescentar(inicio, j)
                    inicio = j
                }
                i = j
            } else {
                i++
            }
        }
        acrescentar(inicio, t.length)
        return saida
    }

    private fun cortar(frase: String, max: Int): List<String> {
        val saida = ArrayList<String>()
        var resto = frase
        while (resto.length > max) {
            val corte = pontoDeCorte(resto, max)
            saida += resto.substring(0, corte).trimEnd()
            resto = resto.substring(corte).trimStart()
        }
        if (resto.isNotEmpty()) saida += resto
        return saida
    }

    /** Onde cortar [s] (`s.length > max`, sem espaço na ponta): o último espaço dentro do limite; senão a última fronteira de caractere. */
    private fun pontoDeCorte(s: String, max: Int): Int {
        for (i in max downTo 1) if (s[i].isWhitespace()) return i
        val janela = s.substring(0, minOf(s.length, max + 32))
        val fronteiras = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(janela) }
        val b = fronteiras.preceding(max + 1)
        if (b != BreakIterator.DONE && b > 0) return b
        // Um caractere maior que o limite: corta mesmo assim, mas sem partir o par substituto.
        return when {
            max >= 2 && s[max].isLowSurrogate() && s[max - 1].isHighSurrogate() -> max - 1
            max == 1 && s[0].isHighSurrogate() -> 2
            else -> max
        }
    }
}
