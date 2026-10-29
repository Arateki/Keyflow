package com.keyflow.speech.frontend

/**
 * O mínimo que o VITS português precisa antes do frontend de caracteres (FLOC-03).
 * O `tokens.txt` não tem dígitos: um inteiro simples vira a palavra em português, e uma sigla que
 * contenha caractere fora do vocabulário é soletrada. O resto do texto passa. Não é um normalizador geral.
 */
object PortugueseSpeechNormalizer {

    private val sigla = Regex("(?<![\\p{L}\\p{N}])(?=\\p{Lu})[\\p{Lu}\\p{N}]{2,}(?![\\p{L}\\p{N}])")
    private val digitos = Regex("\\d+")

    private val unidades = listOf("zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove")
    private val dezADezenove = listOf("dez", "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove")
    private val dezenas = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val centenas = listOf(
        "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos",
    )

    /**
     * [conhecido] diz se o frontend vai manter o caractere. Sigla cujas letras já estão no vocabulário
     * (ONU) não é soletrada: o modelo de caracteres lê as letras em sequência, como o sherpa.
     */
    fun normalize(text: String, conhecido: (Char) -> Boolean = { it !in '0'..'9' }): String {
        val soletrada = sigla.replace(text) { match ->
            val token = match.value
            if (token.all { conhecido(it) }) token else token.toList().joinToString(" ")
        }
        return digitos.replace(soletrada) { match ->
            val cru = match.value
            val palavras = if ((cru.length > 1 && cru[0] == '0') || cru.length > 6) soletrar(cru) else cardinal(cru.toInt())
            val inicio = match.range.first
            val fim = match.range.last + 1
            val antes = if (inicio > 0 && !soletrada[inicio - 1].isWhitespace()) " " else ""
            val depois = if (fim < soletrada.length && soletrada[fim].isLetter()) " " else ""
            antes + palavras + depois
        }
    }

    private fun soletrar(digitos: String): String = digitos.map { unidades[it - '0'] }.joinToString(" ")

    /** Cardinal de 0 a 999.999, pt-BR (quatorze, dezesseis). 100 é "cem"; 101 é "cento e um". */
    internal fun cardinal(n: Int): String {
        require(n in 0..999_999)
        if (n < 1000) return grupo(n)
        val milhares = n / 1000
        val resto = n % 1000
        val esquerda = if (milhares == 1) "mil" else grupo(milhares) + " mil"
        if (resto == 0) return esquerda
        val direita = grupo(resto)
        return if (resto < 100 || resto % 100 == 0) "$esquerda e $direita" else "$esquerda $direita"
    }

    private fun grupo(n: Int): String {
        if (n == 0) return "zero"
        if (n == 100) return "cem"
        val partes = ArrayList<String>(3)
        val c = n / 100
        val resto = n % 100
        if (c > 0) partes += centenas[c]
        if (resto in 10..19) {
            partes += dezADezenove[resto - 10]
        } else {
            val d = resto / 10
            val u = resto % 10
            if (d > 0) partes += dezenas[d]
            if (u > 0) partes += unidades[u]
        }
        return partes.joinToString(" e ")
    }
}
