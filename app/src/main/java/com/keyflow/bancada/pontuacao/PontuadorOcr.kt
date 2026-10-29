package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso

/** Compara a transcrição do OCR com a referência usando erro de caracteres normalizados. */
object PontuadorOcr : Pontuador {
    override fun pontuar(caso: Caso, saida: String): Pontuacao {
        val esperado = caso.esperado as? String ?: error("caso ${caso.id}: OCR exige texto esperado")
        if (saida.isBlank()) return Pontuacao.zero(Marca.VAZIA)
        val nota = Cer.nota(esperado, saida)
        return Pontuacao(nota, mapOf("cer" to nota))
    }
}
