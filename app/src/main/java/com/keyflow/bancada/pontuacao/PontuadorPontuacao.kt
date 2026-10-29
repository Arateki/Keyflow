package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso

/**
 * `pontuacao` (BPT-02): o modelo recebe o ditado cru e devolve o texto pontuado. Nota = ½ **preservação das palavras** (1 − WER das
 * palavras normalizadas contra o esperado) + ½ **pontuação e maiúsculas** (média de dois F1: sinais `. , ? ! : ;` e palavras que
 * começam em maiúscula, cada um identificado por palavra + nº da ocorrência, para a conta não se perder com uma palavra a mais).
 */
object PontuadorPontuacao : Pontuador {
    const val PRESERVACAO = "preservacao"
    const val PONTUACAO = "pontuacao"
    const val MAIUSCULAS = "maiusculas"

    private val PESOS = mapOf(PRESERVACAO to 0.5, PONTUACAO to 0.25, MAIUSCULAS to 0.25)
    private val PREFIXOS = listOf("claro", "aqui está", "aqui esta", "sure", "here is", "here's", "certo", "por supuesto", "aquí está")

    override fun pontuar(caso: Caso, saida: String): Pontuacao {
        val esperado = caso.esperado as? String ?: error("caso ${caso.id}: o pontuador `pontuacao` exige `esperado` em texto")
        if (saida.isBlank()) return Pontuacao.zero(Marca.VAZIA)
        val fichasEsperado = Normalizacao.fichas(esperado)
        val fichasObtido = Normalizacao.fichas(saida)
        val criterios = mapOf(
            PRESERVACAO to Wer.nota(fichasEsperado.map { it.normalizada }, fichasObtido.map { it.normalizada }),
            PONTUACAO to F1.multiconjunto(sinais(fichasEsperado), sinais(fichasObtido)),
            MAIUSCULAS to F1.multiconjunto(maiusculas(fichasEsperado), maiusculas(fichasObtido)),
        )
        val marcas = if (comecaComConversa(saida) && !comecaComConversa(esperado)) setOf(Marca.PREFIXO_DE_CONVERSA) else emptySet()
        return Pontuacao.ponderada(criterios, PESOS, marcas)
    }

    /** `palavra#n:sinal` — o sinal que vem logo depois da n-ésima ocorrência da palavra. */
    private fun sinais(fichas: List<Normalizacao.Ficha>): List<String> =
        ocorrencias(fichas).mapNotNull { (ficha, n) -> ficha.sinal?.let { "${ficha.normalizada}#$n:$it" } }

    /** `palavra#n` das palavras que começam em maiúscula. */
    private fun maiusculas(fichas: List<Normalizacao.Ficha>): List<String> =
        ocorrencias(fichas).filter { it.first.maiuscula }.map { (ficha, n) -> "${ficha.normalizada}#$n" }

    private fun ocorrencias(fichas: List<Normalizacao.Ficha>): List<Pair<Normalizacao.Ficha, Int>> {
        val vistas = HashMap<String, Int>()
        return fichas.map { ficha -> ficha to vistas.merge(ficha.normalizada, 1, Int::plus)!! }
    }

    private fun comecaComConversa(texto: String): Boolean {
        val inicio = texto.trimStart().lowercase()
        return PREFIXOS.any { inicio.startsWith(it) }
    }
}
