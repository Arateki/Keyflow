package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso
import org.json.JSONArray

/** Pontua uma lista de rótulos, um por linha ou separados por vírgula; desconhecidos contam contra a precisão. */
object PontuadorObjetos : Pontuador {
    override fun pontuar(caso: Caso, saida: String): Pontuacao {
        if (saida.isBlank()) return Pontuacao.zero(Marca.VAZIA)
        val esperados = caso.esperado as? JSONArray ?: error("caso ${caso.id}: objetos exige lista esperada")
        val sinonimos = caso.bruto.getJSONObject("sinonimos")
        val mapa = buildMap {
            for (chave in sinonimos.keys()) {
                val canonico = Normalizacao.texto(chave)
                put(canonico, canonico)
                val nomes = sinonimos.getJSONArray(chave)
                for (i in 0 until nomes.length()) put(Normalizacao.texto(nomes.getString(i)), canonico)
            }
        }
        val esperado = (0 until esperados.length()).map { Normalizacao.texto(esperados.getString(it)) }.toSet()
        val partes = if (saida.trim().startsWith("[")) {
            val lista = runCatching { JSONArray(saida.trim()) }.getOrNull()
            if (lista == null) listOf(saida) else (0 until lista.length()).map { lista.optString(it) }
        } else listOf(saida)
        // Reconhece também uma descrição curta ("um livro e um copo"); palavras de ligação não são rótulos.
        val ignorar = setOf("a", "as", "o", "os", "um", "uma", "uns", "umas", "e", "de", "do", "da", "na", "no",
            "imagem", "mostra", "contém", "contem", "vejo", "há", "ha", "objetos", "objeto", "dois", "duas",
            "the", "a", "an", "and", "of", "in", "image", "shows", "contains", "there", "is", "are", "two", "objects")
        val vocabulario = mapa.entries.map { it.key.split(' ') to it.value }.sortedByDescending { it.first.size }
        val palavras = partes.flatMap { Normalizacao.palavras(it) }
        val obtido = LinkedHashSet<String>()
        var indice = 0
        while (indice < palavras.size) {
            val encontrado = vocabulario.firstOrNull { (nome, _) ->
                indice + nome.size <= palavras.size && palavras.subList(indice, indice + nome.size) == nome
            }
            if (encontrado != null) {
                obtido += encontrado.second
                indice += encontrado.first.size
            } else {
                if (palavras[indice] !in ignorar) obtido += palavras[indice]
                indice++
            }
        }
        val acertos = obtido.intersect(esperado).size
        val precisao = if (obtido.isEmpty()) 0.0 else acertos.toDouble() / obtido.size
        val revocacao = if (esperado.isEmpty()) 0.0 else acertos.toDouble() / esperado.size
        val f1 = if (precisao + revocacao == 0.0) 0.0 else 2 * precisao * revocacao / (precisao + revocacao)
        return Pontuacao(f1, mapOf("precisao" to precisao, "revocacao" to revocacao))
    }
}
