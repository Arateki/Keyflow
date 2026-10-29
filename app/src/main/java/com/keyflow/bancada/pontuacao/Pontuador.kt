package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso

/**
 * Observações que a pontuação anota além da nota (BPT-01): não mudam a conta, explicam-na. O comparador e a tela de detalhe as
 * mostram ("por que 0,4?").
 */
enum class Marca {
    VAZIA,
    PREFIXO_DE_CONVERSA,
    CERCA_DE_CODIGO,
    TEXTO_EM_VOLTA,
    JSON_ILEGIVEL,
    INVENCAO,
    IDIOMA_ERRADO,
    IDIOMA_INCERTO,
    FORA_DO_LIMITE,
    RECUSA_CORRETA,
    RECUSA_INDEVIDA,
    CHAMADA_ESPURIA,
    MULTIPLAS_CHAMADAS,
    FERRAMENTA_ERRADA,
    ARGUMENTOS_ERRADOS,
    VALOR_AUSENTE,
    DISTRATOR_PRESENTE,
}

/** O resultado de pontuar uma saída: nota 0..1, o detalhe por critério (também 0..1) e as [marcas]. */
data class Pontuacao(val nota: Double, val criterios: Map<String, Double> = emptyMap(), val marcas: Set<Marca> = emptySet()) {
    init {
        require(nota in 0.0..1.0) { "nota fora de 0..1: $nota" }
    }

    companion object {
        /** Saída vazia ou inaproveitável: nota 0 com [marcas]. */
        fun zero(vararg marcas: Marca, criterios: Map<String, Double> = emptyMap()) = Pontuacao(0.0, criterios, marcas.toSet())

        /** A média ponderada de [criterios] (pesos em [pesos]; critério sem peso vale 0): soma dos pesos deve ser 1. */
        fun ponderada(criterios: Map<String, Double>, pesos: Map<String, Double>, marcas: Set<Marca> = emptySet()): Pontuacao {
            val nota = pesos.entries.sumOf { (nome, peso) -> peso * (criterios[nome] ?: 0.0) }
            return Pontuacao(nota.coerceIn(0.0, 1.0), criterios, marcas)
        }
    }
}

/**
 * Função pura `(caso, saída) → Pontuacao` (BPT-01): Kotlin sem Android, sem rede, sem modelo, sem juiz. A mesma entrada dá a mesma nota,
 * em qualquer aparelho e em qualquer execução.
 */
fun interface Pontuador {
    fun pontuar(caso: Caso, saida: String): Pontuacao
}
