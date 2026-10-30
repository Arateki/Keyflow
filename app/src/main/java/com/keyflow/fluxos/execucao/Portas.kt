package com.keyflow.fluxos.execucao

import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Tipo
import com.keyflow.fluxos.TipoEtapa

/**
 * O que passa de uma etapa à seguinte. JVM puro: a imagem é um [ImagemHandle], que o adaptador Android embrulha em um `Bitmap`.
 * Nada disto vai a disco (FLX-30).
 */
sealed interface Valor {
    /** O tipo que a cadeia de etapas enxerga ([Etapa.entrada]/[Etapa.saida]). */
    val tipo: Tipo

    data object Nada : Valor {
        override val tipo get() = Tipo.NADA
    }

    data class Texto(val texto: String) : Valor {
        override val tipo get() = Tipo.TEXTO
    }

    /** Igualdade por identidade: duas imagens são coisas diferentes mesmo com o mesmo handle de teste. */
    class Imagem(val handle: ImagemHandle) : Valor {
        override val tipo get() = Tipo.IMAGEM
    }
}

/** Dono de uma imagem que o fluxo carrega. O executor chama [liberar] exatamente uma vez, em todo fim (FLX-19, FLX-28). */
interface ImagemHandle {
    fun liberar()
}

/** O resultado de uma etapa: segue, para o fluxo (falha) ou foi cancelada (não é erro — FLX-29). */
sealed interface ResultadoEtapa<out T> {
    data class Ok<T>(val valor: T) : ResultadoEtapa<T>

    data class Falha(val erro: ErroEtapa) : ResultadoEtapa<Nothing>

    data object Cancelada : ResultadoEtapa<Nothing>
}

/** Por que uma etapa falhou. [mensagem] já vem traduzida (`tr`), curta, sem corpo cru de resposta; [destino] é a aba que resolve. */
data class ErroEtapa(val causa: CausaErro, val mensagem: String, val destino: DestinoAba? = null)

/** As causas da tabela "Erros" da spec (FLX-27). */
enum class CausaErro { INDISPONIVEL, REDE, AUTENTICACAO, LIMITE, MEMORIA, VAZIO, TEMPO, MOTOR }

/** A aba para onde o aviso de falha leva ao toque. */
enum class DestinoAba { TRANSCRICAO, VISAO, RACIOCINIO, VOZ }

/**
 * Uma etapa de verdade: uma porta fina sobre o motor que a aba da capacidade já usa. [executar] recebe a [etapa] (para o modelo, a
 * instrução e o sistema) e a entrada já do tipo certo; coopera com o cancelamento da coroutine (cancelar a `Job` corta a chamada em
 * curso, o som e a gravação) e devolve [ResultadoEtapa.Falha] em vez de lançar, quando sabe a causa.
 */
interface PortaEtapa {
    suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor>
}

/** As portas disponíveis, por tipo de etapa. `null` = esta versão não sabe executar o tipo. */
interface Portas {
    fun para(tipo: TipoEtapa): PortaEtapa?
}
