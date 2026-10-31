package com.keyflow.computador

/** Onde a confiança nos servidores fica (arquivo `known_hosts` no Android; fake nos testes). */
interface RepositorioDeConfianca {
    /** Chaves conhecidas para exatamente este `host:porta` (porta diferente é outro servidor). */
    fun chavesConhecidas(host: String, porta: Int): List<ChaveDoServidor>

    /** Grava [chave] como conhecida; se já havia uma do **mesmo algoritmo**, ela é substituída. */
    fun confiar(host: String, porta: Int, chave: ChaveDoServidor)

    /** Esquece todas as chaves de `host:porta`, sem tocar os demais. */
    fun esquecer(host: String, porta: Int)
}

/** O que fazer com a chave que o servidor apresentou (CMP-18). */
sealed interface VereditoDeConfianca {
    /** Já conhecida: segue sem perguntar. */
    data object Conhecida : VereditoDeConfianca

    /** Primeira vez: mostrar a impressão e só seguir com "Confiar e continuar". [outrosTipos] = algoritmos que já eram conhecidos aqui. */
    data class Nova(val impressao: ImpressaoDigital, val outrosTipos: List<String> = emptyList()) : VereditoDeConfianca

    /** Mesmo `host:porta` e mesmo algoritmo, chave diferente: bloqueia (nunca é `Nova`). */
    data class Mudou(val antiga: ImpressaoDigital, val nova: ImpressaoDigital) : VereditoDeConfianca
}

object PoliticaDeConfianca {
    fun avaliar(host: String, porta: Int, chave: ChaveDoServidor, repositorio: RepositorioDeConfianca): VereditoDeConfianca {
        val conhecidas = repositorio.chavesConhecidas(host, porta)
        val doMesmoTipo = conhecidas.filter { it.algoritmo == chave.algoritmo }
        if (doMesmoTipo.any { it == chave }) return VereditoDeConfianca.Conhecida
        // Qualquer chave diferente do mesmo tipo é troca de identidade, mesmo que outra do tipo igual coincida com a anunciada.
        if (doMesmoTipo.isNotEmpty()) return VereditoDeConfianca.Mudou(doMesmoTipo.first().impressao, chave.impressao)
        return VereditoDeConfianca.Nova(chave.impressao, conhecidas.map { it.algoritmo }.distinct())
    }
}

/** Resultado da rotação de chaves anunciada pelo servidor (`hostkeys-00@openssh.com`). */
sealed interface ResultadoRotacao {
    data class Aceita(val adicionadas: List<ChaveDoServidor>) : ResultadoRotacao
    data object Recusada : ResultadoRotacao
}

/** CMP-19: o servidor só pode acrescentar chaves quando a chave com que **já se autenticou** é uma que já era conhecida. */
object AtualizadorDeChaves {
    fun aplicar(host: String, porta: Int, autenticada: ChaveDoServidor, anunciadas: List<ChaveDoServidor>, repositorio: RepositorioDeConfianca): ResultadoRotacao {
        if (autenticada !in repositorio.chavesConhecidas(host, porta)) return ResultadoRotacao.Recusada
        val novas = anunciadas.distinct().filter { a ->
            // Só acrescenta tipos ainda desconhecidos; trocar chave de tipo conhecido pede a confirmação do usuário (Mudou).
            repositorio.chavesConhecidas(host, porta).none { it.algoritmo == a.algoritmo }
        }
        novas.forEach { repositorio.confiar(host, porta, it) }
        return ResultadoRotacao.Aceita(novas)
    }
}
