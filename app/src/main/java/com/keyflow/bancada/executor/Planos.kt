package com.keyflow.bancada.executor

import com.keyflow.bancada.corpus.Corpus
import com.keyflow.bancada.resultado.Aparelho
import com.keyflow.bancada.resultado.AppInfo
import com.keyflow.bancada.resultado.EstadoCaso
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.MotorInfo
import com.keyflow.bancada.resultado.OpcoesRegistradas

/** Monta o [PlanoDeExecucao] de uma rodada: os testes pedidos do corpus, no perfil pedido, com o cabeçalho de quem roda. */
object Planos {
    fun montar(
        corpus: Corpus,
        testes: List<String>,
        perfil: Perfil,
        modelo: ModeloEscolhido,
        aparelho: Aparelho,
        app: AppInfo,
        motor: MotorInfo,
        id: String,
        iniciada: String,
    ): PlanoDeExecucao {
        val noPlano = testes.map { id0 ->
            val def = corpus.teste(id0) ?: throw IllegalArgumentException("o corpus v${corpus.versao} não tem o teste $id0")
            TesteNoPlano.de(def, perfil)
        }
        val cabecalho = Execucao(
            id = id, iniciada = iniciada, estado = EstadoExecucao.EM_ANDAMENTO, aparelho = aparelho, app = app, motor = motor,
            corpus = corpus.versao, perfil = perfil.id, modelo = modelo.info(), opcoes = OpcoesRegistradas(emptyMap(), emptySet()),
        )
        return PlanoDeExecucao(
            cabecalho = cabecalho,
            origem = if (modelo.online) Origem.ONLINE else Origem.LOCAL,
            testes = noPlano,
            janelaContextoTokens = modelo.contextTokens,
        )
    }
}

/**
 * O que a rodada vai custar, calculado ANTES de qualquer chamada (BON-01): nº de chamadas, tokens de entrada **estimados** (o `tokensEntrada` do
 * corpus, caracteres ÷ 4) e o teto de saída. Custo em dinheiro só existe se o usuário informar o preço por milhão de tokens (BON-04, P2): a
 * bancada não leva tabela de preços — então aqui não há campo de custo.
 */
data class EstimativaDeRodada(val chamadas: Int, val tokensEntradaEstimados: Int, val tetoDeTokensDeSaida: Int, val casosQueNaoSeAplicam: Int) {
    companion object {
        fun de(plano: PlanoDeExecucao): EstimativaDeRodada {
            val janela = plano.janelaContextoTokens
            var chamadas = 0
            var entrada = 0
            var saida = 0
            var naoSeAplica = 0
            for (teste in plano.testes) for (caso in teste.casos) {
                if (janela != null && caso.tokensEntrada + caso.maxTokensSaida > janela) {
                    naoSeAplica++
                    continue
                }
                chamadas++
                entrada += caso.tokensEntrada
                saida += caso.maxTokensSaida
            }
            return EstimativaDeRodada(chamadas, entrada, saida, naoSeAplica)
        }

        /**
         * Quanto deve durar [plano] (BNC-04), tirado da última execução completa do MESMO modelo e backend que tem cada teste: a soma do tempo
         * total medido dos casos, escalada pela razão entre os casos do plano e os que rodaram. `null` se algum teste nunca rodou — sem número
         * inventado.
         */
        fun duracaoMs(plano: PlanoDeExecucao, anteriores: List<Execucao>): Long? {
            var total = 0L
            for (teste in plano.testes) {
                val previa = anteriores.asSequence().filter { it.comparavel }
                    .mapNotNull { e -> e.testes.firstOrNull { it.teste == teste.def.id && it.n > 0 } }
                    .firstOrNull() ?: return null
                val medidos = previa.casos.filter { it.totalMs != null && (it.estado == EstadoCaso.OK || it.estado == EstadoCaso.FALHA) }
                if (medidos.isEmpty()) return null
                val media = medidos.sumOf { it.totalMs!! }.toDouble() / medidos.size
                total += (media * teste.casos.size).toLong()
            }
            return total
        }
    }
}
