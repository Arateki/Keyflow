package com.keyflow.fluxos

/**
 * Move a etapa de [de] para [para] (posição final), como `reorderedButtons` das barras. Índices fora da lista ou iguais
 * devolvem a mesma lista; nunca perde nem duplica etapa. Puro.
 */
fun <T> reordenar(etapas: List<T>, de: Int, para: Int): List<T> {
    if (de == para || de !in etapas.indices || para !in etapas.indices) return etapas
    val lista = etapas.toMutableList()
    lista.add(para, lista.removeAt(de))
    return lista
}

/** A etapa nova do seletor "+ Etapa", com o modelo "da aba" e os textos vazios. */
fun etapaNova(tipo: TipoEtapa): Etapa? = when (tipo) {
    TipoEtapa.DITAR -> Etapa.Ditar()
    TipoEtapa.CAPTURAR -> Etapa.Capturar
    TipoEtapa.VISAO -> Etapa.Visao()
    TipoEtapa.PERGUNTAR -> Etapa.Perguntar()
    TipoEtapa.FALAR -> Etapa.Falar()
    TipoEtapa.INSERIR -> Etapa.Inserir
    TipoEtapa.AGENTE -> Etapa.Agente()
    TipoEtapa.DESCONHECIDA -> null
}

/** A mesma etapa com [modelo] (nulo = "Da aba"); as que não usam modelo voltam como estão. */
fun Etapa.comModelo(modelo: ModeloRef?): Etapa = when (this) {
    is Etapa.Ditar -> copy(modelo = modelo)
    is Etapa.Visao -> copy(modelo = modelo)
    is Etapa.Perguntar -> copy(modelo = modelo)
    is Etapa.Falar -> copy(modelo = modelo)
    is Etapa.Agente -> copy(modelo = modelo)
    Etapa.Capturar, Etapa.Inserir, is Etapa.Desconhecida -> this
}

/** O campo próprio da etapa (instrução da Visão, sistema do Perguntar), se ela tem; [texto] é cortado em [LIMITES.TEXTO_DA_ETAPA]. */
fun Etapa.comTexto(texto: String): Etapa {
    val limpo = texto.take(LIMITES.TEXTO_DA_ETAPA).ifBlank { null }
    return when (this) {
        is Etapa.Visao -> copy(instrucao = limpo)
        is Etapa.Perguntar -> copy(sistema = limpo)
        else -> this
    }
}

/** O texto do campo próprio da etapa; nulo se ela não tem campo. */
val Etapa.textoProprio: String? get() = when (this) {
    is Etapa.Visao -> instrucao.orEmpty()
    is Etapa.Perguntar -> sistema.orEmpty()
    else -> null
}
