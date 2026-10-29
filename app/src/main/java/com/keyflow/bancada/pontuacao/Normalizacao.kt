package com.keyflow.bancada.pontuacao

import java.text.Normalizer
import java.util.Locale

/**
 * A normalização única dos pontuadores (design §3): NFC, minúsculas, pontuação trocada por espaço, espaços repetidos colapsados,
 * apóstrofo preservado dentro da palavra e separadores de número (`.` `,` `:` `/` `-`) preservados entre dois dígitos (`1.500,50`,
 * `14:30`, `2026-10-28`). Mesmas regras para o esperado e para a saída — é a comparação entre as duas que importa.
 */
object Normalizacao {
    private val APOSTROFOS = Regex("[‘’ʼ`´]")

    /** O texto normalizado: palavras separadas por um espaço. */
    fun texto(entrada: String): String = palavras(entrada).joinToString(" ")

    /** As palavras normalizadas, na ordem. */
    fun palavras(entrada: String): List<String> = fichas(entrada).map { it.normalizada }

    /**
     * Uma palavra do texto: a forma [original] (como escrita) e a [normalizada]; o [sinal] que vem logo depois dela (`. , ? ! : ;`, ou
     * `null`); se [maiuscula] (a 1ª letra); e se abre uma frase ([inicioDeFrase]: a 1ª do texto, a que segue `. ! ?` ou uma quebra de linha).
     */
    class Ficha(
        val original: String,
        val normalizada: String,
        val sinal: Char?,
        val maiuscula: Boolean,
        val inicioDeFrase: Boolean,
    )

    /** As palavras do texto, com o que o `pontuacao` e a `reescrita` precisam saber de cada uma. */
    fun fichas(entrada: String): List<Ficha> {
        val texto = Normalizer.normalize(entrada, Normalizer.Form.NFC).replace(APOSTROFOS, "'")
        val fichas = ArrayList<Ficha>()
        var i = 0
        var fimAnterior = -1
        var sinalAnterior: Char? = null
        while (i < texto.length) {
            if (!ehParteDePalavra(texto[i])) {
                i++
                continue
            }
            val inicio = i
            while (i < texto.length && (ehParteDePalavra(texto[i]) || ehLigacao(texto, i))) i++
            val original = texto.substring(inicio, i).trim('\'')
            if (original.isEmpty()) continue
            val sinal = sinalApos(texto, i)
            val intervalo = if (fimAnterior < 0) "" else texto.substring(fimAnterior, inicio)
            val inicioDeFrase = fimAnterior < 0 || (sinalAnterior != null && sinalAnterior in SINAIS_DE_FIM) || '\n' in intervalo
            fichas += Ficha(original, original.lowercase(Locale.ROOT), sinal, original.first().isUpperCase(), inicioDeFrase)
            fimAnterior = i
            sinalAnterior = sinal
        }
        return fichas
    }

    /** `. , ? ! : ;` logo depois da palavra, com espaços no meio tolerados (`olá , tudo`); `null` se a próxima coisa é outra palavra. */
    private fun sinalApos(texto: String, de: Int): Char? {
        var j = de
        while (j < texto.length && texto[j] == ' ') j++
        return texto.getOrNull(j)?.takeIf { it in SINAIS }
    }

    private const val SINAIS = ".,?!:;"
    private const val SINAIS_DE_FIM = ".!?"

    private fun ehParteDePalavra(c: Char): Boolean = c.isLetterOrDigit()

    /** Apóstrofo entre letras (`d'água`, `don't`) ou `. , : / -` entre dois dígitos (`1.500`, `14:30`). */
    private fun ehLigacao(texto: String, i: Int): Boolean {
        val c = texto[i]
        val antes = texto.getOrNull(i - 1) ?: return false
        val depois = texto.getOrNull(i + 1) ?: return false
        return when (c) {
            '\'' -> antes.isLetter() && depois.isLetter()
            '.', ',', ':', '/', '-' -> antes.isDigit() && depois.isDigit()
            else -> false
        }
    }
}
