package com.keyflow.bancada.resultado

/**
 * O que precisa ser igual para duas notas poderem ser comparadas (BHS-05, BCP-06): corpus, teste e versão do teste, perfil (um perfil
 * reduzido roda outros casos), opções **aplicadas**, backend e pensamento. O motor e o aparelho ficam de fora de propósito: são as variáveis
 * que a régua compara (BRG-01). O desenho do `design.md` §4 não lista o perfil; ele entra porque nota de 2 casos não se compara com nota
 * de 12 (`licoes/2026-10-28-bancada-p1.md`).
 */
data class ChaveDeComparabilidade(
    val corpus: Int,
    val teste: String,
    val versaoTeste: Int,
    val perfil: String,
    /** `nome → valor` ordenado por nome. */
    val opcoesAplicadas: Map<String, String>,
    val backend: String,
    val pensamento: Boolean,
) {
    /** O nome do primeiro campo em que esta chave difere de [outra], ou `null` se são comparáveis — o que o comparador diz ao recusar. */
    fun diferenca(outra: ChaveDeComparabilidade): String? = when {
        corpus != outra.corpus -> "corpus"
        teste != outra.teste -> "teste"
        versaoTeste != outra.versaoTeste -> "versaoTeste"
        perfil != outra.perfil -> "perfil"
        opcoesAplicadas != outra.opcoesAplicadas -> "opcoes"
        backend != outra.backend -> "backend"
        pensamento != outra.pensamento -> "pensamento"
        else -> null
    }

    fun comparavelCom(outra: ChaveDeComparabilidade): Boolean = diferenca(outra) == null

    companion object {
        fun de(execucao: Execucao, teste: ResultadoTeste) = ChaveDeComparabilidade(
            corpus = execucao.corpus,
            teste = teste.teste,
            versaoTeste = teste.versaoTeste,
            perfil = execucao.perfil,
            opcoesAplicadas = execucao.opcoes.aplicadas.toSortedMap(),
            backend = execucao.modelo.backend,
            pensamento = execucao.modelo.pensamento,
        )
    }
}

/** A comparação do resultado atual com o anterior comparável (BNC-02): quanto subiu ou desceu. */
data class Variacao(val anterior: ResultadoTeste, val delta: Double)

object Historico {
    /**
     * O resultado anterior **comparável** de [teste] para comparar com [atual]: o mais recente entre [anteriores] (do mais novo ao mais
     * antigo) que está completo e tem a mesma chave. `null` se não há — a tela não mostra variação. Execução incompleta nunca entra, nem como anterior nem como atual (BHS-06).
     */
    fun variacao(atual: Execucao, teste: String, anteriores: List<Execucao>): Variacao? {
        if (!atual.comparavel) return null // a atual incompleta (cancelada, térmica…) tem nota parcial: não se compara (BHS-06)
        val resultado = atual.testes.firstOrNull { it.teste == teste } ?: return null
        val nota = resultado.nota ?: return null
        val chave = ChaveDeComparabilidade.de(atual, resultado)
        for (execucao in anteriores) {
            if (!execucao.comparavel || execucao.id == atual.id) continue
            val anterior = execucao.testes.firstOrNull { it.teste == teste } ?: continue
            val notaAnterior = anterior.nota ?: continue
            if (ChaveDeComparabilidade.de(execucao, anterior).comparavelCom(chave)) return Variacao(anterior, nota - notaAnterior)
        }
        return null
    }
}
