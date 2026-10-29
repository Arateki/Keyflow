package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso

/**
 * `contexto` (BPT-07): uma pergunta sobre um documento longo, com o dado no começo, no meio ou no fim. Nota 1 se a resposta traz o
 * `valor` exato (normalizado, em fronteira de palavra) **e** nenhum dos `distratores` plantados no documento; senão 0. Sem nota parcial:
 * o valor está certo ou o modelo se perdeu no texto.
 */
object PontuadorContexto : Pontuador {
    const val DETALHE = "detalhe"

    override fun pontuar(caso: Caso, saida: String): Pontuacao {
        if (saida.isBlank()) return Pontuacao.zero(Marca.VAZIA)
        val valor = caso.texto("valor") ?: error("caso ${caso.id}: o pontuador `contexto` exige `valor`")
        val resposta = " ${Normalizacao.texto(saida)} "
        val temValor = " ${Normalizacao.texto(valor)} " in resposta
        val distrator = caso.lista("distratores").any { " ${Normalizacao.texto(it)} " in resposta }
        val marcas = LinkedHashSet<Marca>()
        if (!temValor) marcas += Marca.VALOR_AUSENTE
        if (distrator) marcas += Marca.DISTRATOR_PRESENTE
        val nota = if (temValor && !distrator) 1.0 else 0.0
        return Pontuacao(nota, mapOf(DETALHE to nota), marcas)
    }
}
