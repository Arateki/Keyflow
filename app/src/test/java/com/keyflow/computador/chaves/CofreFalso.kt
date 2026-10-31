package com.keyflow.computador.chaves

/** Cofre em memória para os testes (o de verdade é o `keyflow_auth`). */
class CofreFalso : CofreDeSegredos {
    val itens = linkedMapOf<String, String>()
    override fun ler(chave: String) = itens[chave]
    override fun gravar(chave: String, valor: String) { itens[chave] = valor }
    override fun apagar(chave: String) { itens.remove(chave) }
    override fun valores(): Collection<String> = itens.values.toList()
}
