package com.keyflow.bancada.pontuacao

/** Os pontuadores de cada teste do corpus pelo id do teste. Teste sem pontuador (o Rápido não tem nota) devolve `null`. */
object Pontuadores {
    private val POR_TESTE: Map<String, Pontuador> = mapOf(
        "pontuacao" to PontuadorPontuacao,
        "reescrita" to PontuadorReescrita,
        "json" to PontuadorJson,
        "acoes" to PontuadorAcoes,
        "contexto" to PontuadorContexto,
        "ocr" to PontuadorOcr,
        "objetos" to PontuadorObjetos,
    )

    fun de(teste: String): Pontuador? = POR_TESTE[teste]

    /** Os ids dos testes com pontuador. */
    val testes: Set<String> get() = POR_TESTE.keys
}
