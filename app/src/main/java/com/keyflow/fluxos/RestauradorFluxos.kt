package com.keyflow.fluxos

/**
 * O que o "Restaurar fluxos padrão" vai fazer, para a confirmação (FLX-41). [recriar]: padrões apagados (nome do catálogo).
 * [reescrever]: padrões que existem e diferem do catálogo (nome atual do fluxo). [semEspaco]: padrões apagados que **não cabem**
 * porque a lista já está no limite de [LIMITES.FLUXOS]; o restaurar não os recria nem tira nada da pessoa.
 */
data class PreviaRestaurar(val recriar: List<String>, val reescrever: List<String>, val semEspaco: List<String>) {
    /** Restaurar não mudaria nada: não há o que recriar nem reescrever. A confirmação não grava (o botão fica desligado). */
    val nadaAGravar: Boolean get() = recriar.isEmpty() && reescrever.isEmpty()

    /**
     * Os padrão estão **como no original**: nada a gravar **e** nenhum faltando por falta de espaço. Só então a confirmação diz
     * "Os fluxos padrão já estão como no original"; com [semEspaco] a tela diz quais não couberam.
     */
    val vazia: Boolean get() = nadaAGravar && semEspaco.isEmpty()
}

/**
 * "Restaurar fluxos padrão" (FLX-42..44), puro e idempotente. Age **por padrão do catálogo**, identificado pelo `padrao` do
 * fluxo (o `builtinId`), nunca pelo nome:
 * - não existe → é **recriado** (nome e etapas do catálogo, "da aba"), **sem gatilho**, com **id novo**, logo depois do último
 *   padrão que o antecede no catálogo (ou no início);
 * - existe e difere (nome, etapas, modelo, instrução) → nome e etapas voltam ao catálogo; **id, posição e `padrao` ficam**, então
 *   os gatilhos que apontam para ele continuam apontando;
 * - existe e é igual → nada;
 * - fluxo sem `padrao` (da pessoa) → nunca é lido nem alterado, nem a posição relativa dele.
 *
 * Os gatilhos passam **pela mesma lista**: o restaurar nunca cria, move, apaga nem altera um (FLX-43). Não toca em `Settings`,
 * barra do teclado nem cofre.
 *
 * Decisão da implementação (a spec não diz): com a lista no limite de 20, um padrão apagado só volta se couber. Recriar além do
 * limite faria o codec cortar, na leitura seguinte, fluxos da pessoa — perda de dado pior do que não recriar. Os que ficam de fora
 * aparecem em [PreviaRestaurar.semEspaco].
 */
object RestauradorFluxos {

    fun restaurar(config: ConfigFluxos, idNovo: () -> String = Ids::fluxo): ConfigFluxos = planejar(config, idNovo).config

    fun previa(config: ConfigFluxos): PreviaRestaurar = planejar(config, sequencia()).previa

    private class Plano(val config: ConfigFluxos, val previa: PreviaRestaurar)

    private fun planejar(config: ConfigFluxos, idNovo: () -> String): Plano {
        val lista = config.fluxos.toMutableList()
        val usados = lista.mapTo(HashSet()) { it.id }
        val recriar = mutableListOf<String>()
        val reescrever = mutableListOf<String>()
        val semEspaco = mutableListOf<String>()

        PadroesFluxo.CATALOGO.forEachIndexed { k, padrao ->
            val i = lista.indexOfFirst { it.padrao == padrao.builtinId }
            when {
                i >= 0 -> {
                    val atual = lista[i]
                    if (atual.nome != padrao.nome || atual.etapas != padrao.etapas) {
                        reescrever += atual.nome
                        lista[i] = atual.copy(nome = padrao.nome, etapas = padrao.etapas)
                    }
                }
                lista.size >= LIMITES.FLUXOS -> semEspaco += padrao.nome
                else -> {
                    val antecedem = PadroesFluxo.CATALOGO.take(k).mapTo(HashSet()) { it.builtinId }
                    val depoisDo = lista.indexOfLast { it.padrao in antecedem } // -1 → o início
                    val id = idLivre(idNovo, usados)
                    usados += id
                    lista.add(depoisDo + 1, Fluxo(id = id, nome = padrao.nome, padrao = padrao.builtinId, etapas = padrao.etapas))
                    recriar += padrao.nome
                }
            }
        }
        // `gatilhos` é a mesma lista de entrada, não uma cópia: o restaurar não encosta nela.
        return Plano(config.copy(fluxos = lista), PreviaRestaurar(recriar, reescrever, semEspaco))
    }

    /** Um id que nenhum fluxo usa: se o gerador repetir um, acrescenta um contador até ficar único. */
    private fun idLivre(idNovo: () -> String, usados: Set<String>): String {
        var id = idNovo()
        var n = 1
        while (id in usados) id = idNovo() + (n++)
        return id
    }

    /** Ids só para a prévia (nunca vão a lugar nenhum): determinísticos, sem sortear. */
    private fun sequencia(): () -> String {
        var n = 0
        return { "previa-${n++}" }
    }
}
