package com.keyflow.fluxos

/** Cria um fluxo vazio com nome/id livres; quem persiste aplica isto à configuração atual da transação. */
fun novoFluxo(config: ConfigFluxos, nomeBase: String, idNovo: () -> String = Ids::fluxo): Fluxo? {
    if (config.fluxos.size >= LIMITES.FLUXOS) return null
    val nomes = config.fluxos.map { it.nome }.toSet()
    val base = nomeBase.take(LIMITES.NOME)
    var nome = base
    var n = 2
    while (nome in nomes) {
        val sufixo = " ${n++}"
        nome = base.take(LIMITES.NOME - sufixo.length) + sufixo
    }
    val ids = config.fluxos.map { it.id }.toSet()
    var id = idNovo()
    n = 1
    while (id in ids) id = idNovo() + n++
    return Fluxo(id, nome, etapas = emptyList())
}
