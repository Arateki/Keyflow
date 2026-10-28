package com.keyflow.bancada.pontuacao

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Acha o JSON na resposta de um modelo, que raramente vem limpo: às vezes numa cerca ```json, às vezes com uma frase antes. Varredura
 * própria por colchetes balanceados (respeitando texto e escapes) em vez de confiar no parser: o `org.json` do Android e o da JVM
 * divergem no que fazem com lixo depois do valor, e a nota precisa ser a mesma nos dois.
 */
object ExtratorJson {
    enum class Forma {
        /** A resposta inteira é um valor JSON. */
        LIMPO,

        /** O valor está numa cerca de código (```), sozinho. */
        CERCA,

        /** O valor tem texto antes ou depois. */
        EM_VOLTA,

        /** Nenhum valor JSON na resposta. */
        NENHUM,
    }

    /** Os valores JSON de nível mais alto ([JSONObject] ou [JSONArray]), na ordem em que aparecem, e como vieram. */
    class Extracao(val valores: List<Any>, val forma: Forma, val truncada: Boolean = false) {
        val primeiroObjeto: JSONObject? get() = valores.firstOrNull() as? JSONObject
    }

    /** Aninhamento acima disto não é resposta de modelo, é ataque ao parser: a varredura desiste. */
    const val PROFUNDIDADE_MAXIMA = 64

    /** Uma saída de teste tem poucas centenas de tokens; acima disto o resto nem é lido (a varredura é quadrática no pior caso). */
    const val TAMANHO_MAXIMO = 20_000

    fun extrair(saida: String): Extracao {
        val inteiro = saida.trim()
        val truncada = inteiro.length > TAMANHO_MAXIMO
        val texto = inteiro.take(TAMANHO_MAXIMO)
        if (texto.isEmpty()) return Extracao(emptyList(), Forma.NENHUM)
        val achados = varrer(texto)
        if (achados.isEmpty()) return Extracao(emptyList(), Forma.NENHUM, truncada)
        val valores = achados.map { it.valor }
        val unico = achados.singleOrNull()
        val forma = when {
            unico != null && unico.inicio == 0 && unico.fim == texto.length && !truncada -> Forma.LIMPO
            unico != null && naCercaSozinho(texto, unico) -> Forma.CERCA
            else -> Forma.EM_VOLTA
        }
        return Extracao(valores, forma, truncada)
    }

    private class Achado(val valor: Any, val inicio: Int, val fim: Int)

    private fun varrer(texto: String): List<Achado> {
        val achados = ArrayList<Achado>()
        var i = 0
        while (i < texto.length) {
            val c = texto[i]
            if (c == '{' || c == '[') {
                val fim = fimBalanceado(texto, i)
                if (fim > 0) {
                    val valor = parse(texto.substring(i, fim))
                    if (valor != null) {
                        achados += Achado(valor, i, fim)
                        i = fim
                        continue
                    }
                }
            }
            i++
        }
        return achados
    }

    /** O índice logo depois do `}`/`]` que fecha o aberto em [de], ou -1 (não fecha, ou fundo demais). */
    private fun fimBalanceado(texto: String, de: Int): Int {
        var profundidade = 0
        var emTexto = false
        var escapado = false
        for (i in de until texto.length) {
            val c = texto[i]
            if (emTexto) {
                when {
                    escapado -> escapado = false
                    c == '\\' -> escapado = true
                    c == '"' -> emTexto = false
                }
                continue
            }
            when (c) {
                '"' -> emTexto = true
                '{', '[' -> {
                    profundidade++
                    if (profundidade > PROFUNDIDADE_MAXIMA) return -1
                }
                '}', ']' -> {
                    profundidade--
                    if (profundidade == 0) return i + 1
                    if (profundidade < 0) return -1
                }
            }
        }
        return -1
    }

    private fun parse(trecho: String): Any? = if (!JsonEstrito.valido(trecho)) null else try {
        if (trecho.startsWith("{")) JSONObject(trecho) else JSONArray(trecho)
    } catch (e: JSONException) {
        null
    }

    /** Antes do valor só há a abertura da cerca (``` ou ```json) e depois só o fechamento. */
    private fun naCercaSozinho(texto: String, achado: Achado): Boolean {
        val antes = texto.substring(0, achado.inicio).trim()
        val depois = texto.substring(achado.fim).trim()
        return antes.startsWith("```") && antes.removePrefix("```").trim().let { it.isEmpty() || it.all { c -> c.isLetter() } } &&
            depois == "```"
    }
}
