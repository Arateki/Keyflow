package com.keyflow.bancada.executor

import com.keyflow.bancada.corpus.Caso
import com.keyflow.bancada.corpus.TesteDef
import com.keyflow.bancada.pontuacao.Pontuador
import com.keyflow.bancada.pontuacao.Pontuadores
import com.keyflow.bancada.resultado.CausaFalha
import com.keyflow.bancada.resultado.CausaInvalido
import com.keyflow.bancada.resultado.EstadoCaso
import com.keyflow.bancada.resultado.EstadoExecucao
import com.keyflow.bancada.resultado.Execucao
import com.keyflow.bancada.resultado.MotivoIncompleta
import com.keyflow.bancada.resultado.OpcoesRegistradas
import com.keyflow.bancada.resultado.ResultadoCaso
import com.keyflow.bancada.resultado.ResultadoTeste
import com.keyflow.reasoning.GenerationOptions
import com.keyflow.reasoning.ReasoningError
import com.keyflow.reasoning.ReasoningEvent
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * O executor único da bancada (BEX-01): roda os casos de cada teste em sequência por um [MotorDeCapacidade] e mede. Não sabe qual provedor
 * está por trás, não pontua (chama o [Pontuador] do teste) e não grava (devolve cada passo por `aoMudar`). Tudo do ambiente entra por
 * parâmetro — relógio, térmico, bateria, pausa — para o executor inteiro rodar na JVM com relógio virtual.
 *
 * ```
 * Preparar → [Determinismo, só local] → (conferir ambiente → Caso → Pontuar → Gravar)* → Concluir
 * ```
 *
 * **Estados de um caso** (BEX-06): `OK`; `FALHA` (OOM, queda do `:reasoning`, timeout — local —, erro do motor: nota 0 com marca);
 * `INVALIDO` (rede, limite, credencial — online —: fora da média, contado); `NAO_SE_APLICA` (janela menor que o caso). O limite de uso
 * (`RATE_LIMIT`) e a credencial recusada encerram a execução como `incompleta`. **Cancelar** propaga ao motor (a chamada HTTP cai de verdade,
 * BON-05) e entrega o parcial `incompleta`/`CANCELADA` antes de repassar o cancelamento.
 */
class ExecutorBancada(
    private val motor: MotorDeCapacidade,
    private val relogio: () -> Long,
    private val config: ConfigExecutor,
    private val ambiente: AmbienteDoAparelho = AmbienteDoAparelho(),
    private val pausa: suspend (Long) -> Unit = { delay(it) },
    private val pontuadores: (String) -> Pontuador? = Pontuadores::de,
) {
    suspend fun executar(
        plano: PlanoDeExecucao,
        aoMudar: suspend (Execucao) -> Unit = {},
        aoProgredir: (Progresso) -> Unit = {},
    ): Execucao {
        for (teste in plano.testes) {
            require(teste.def.capacidade == motor.capacidade) { "o teste ${teste.def.id} pede ${teste.def.capacidade}; o motor é ${motor.capacidade}" }
            require(pontuadores(teste.def.id) != null) { "o teste ${teste.def.id} não tem pontuador" }
        }
        return Corrida(plano, aoMudar, aoProgredir).rodar()
    }

    /** Sai do laço de testes com a execução `incompleta` por este motivo. Sem pilha: é fluxo de controle. */
    private class EncerrarExecucao(val motivo: MotivoIncompleta) : Exception(motivo.name, null, false, false)

    private enum class Problema { TERMICO, BATERIA }

    /** O resultado de um caso e, se ele obriga a parar, por quê. */
    private class Desfecho(val resultado: ResultadoCaso, val encerrar: MotivoIncompleta? = null)

    private sealed interface Tentativa {
        val texto: String
        val primeiroMs: Long?
        val totalMs: Long
        val metricas: ReasoningEvent.Metrics?

        class Concluiu(override val texto: String, override val primeiroMs: Long?, override val totalMs: Long, override val metricas: ReasoningEvent.Metrics?) : Tentativa
        class Falhou(
            val tipo: ReasoningError,
            val mensagem: String?,
            override val texto: String,
            override val primeiroMs: Long?,
            override val totalMs: Long,
            override val metricas: ReasoningEvent.Metrics?,
        ) : Tentativa
        class Estourou(override val texto: String, override val primeiroMs: Long?, override val totalMs: Long) : Tentativa {
            override val metricas: ReasoningEvent.Metrics? = null
        }
    }

    private inner class Corrida(
        val plano: PlanoDeExecucao,
        val aoMudar: suspend (Execucao) -> Unit,
        val aoProgredir: (Progresso) -> Unit,
    ) {
        private val concluidos = ArrayList<ResultadoTeste>()
        private var emAndamento: ResultadoTeste? = null
        private val pedidas = LinkedHashSet<String>()
        private val aplicadas = LinkedHashMap<String, String>()
        private val ignoradasPeloMotor = LinkedHashSet<String>()
        private var naoDeterministico = false
        private val previos = HashMap<String, List<ResultadoCaso>>()
        private var progressoAtual = Progresso("", 0, plano.testes.size, 0, 0)

        private fun opcoesRegistradas() = OpcoesRegistradas(
            aplicadas = aplicadas.toMap(),
            ignoradas = (pedidas - aplicadas.keys) + ignoradasPeloMotor,
        )

        private fun instantaneo(estado: EstadoExecucao, motivo: MotivoIncompleta?): Execucao = plano.cabecalho.copy(
            estado = estado,
            motivo = motivo,
            opcoes = opcoesRegistradas(),
            testes = concluidos + listOfNotNull(emAndamento),
        )

        suspend fun rodar(): Execucao {
            try {
                aoMudar(instantaneo(EstadoExecucao.EM_ANDAMENTO, null))
                val motivo = try {
                    corpo()
                    null
                } catch (e: EncerrarExecucao) {
                    e.motivo
                }
                val final = instantaneo(if (motivo == null) EstadoExecucao.COMPLETA else EstadoExecucao.INCOMPLETA, motivo)
                aoMudar(final)
                return final
            } catch (e: CancellationException) {
                // Parar: o motor já foi cancelado pela propagação; o que foi medido até aqui é entregue como `incompleta` (BEX-04).
                withContext(NonCancellable) { aoMudar(instantaneo(EstadoExecucao.INCOMPLETA, MotivoIncompleta.CANCELADA)) }
                throw e
            }
        }

        private suspend fun corpo() {
            if (plano.origem == Origem.LOCAL) verificarDeterminismo()
            plano.testes.forEachIndexed { i, teste ->
                val total = teste.casos.size
                progressoAtual = Progresso(teste.def.id, i + 1, plano.testes.size, 0, total)
                conferirAmbiente()
                val casos = ArrayList<ResultadoCaso>()
                teste.casos.forEachIndexed { k, caso ->
                    if (k > 0 && k % config.conferirACadaCasos == 0) conferirAmbiente()
                    progressoAtual = Progresso(teste.def.id, i + 1, plano.testes.size, k + 1, total)
                    aoProgredir(progressoAtual)
                    val desfecho = rodarCaso(teste.def, caso)
                    casos += desfecho.resultado
                    emAndamento = ResultadoTeste.agregar(teste.def.id, teste.def.versao, naoDeterministico, casos)
                    aoMudar(instantaneo(EstadoExecucao.EM_ANDAMENTO, null))
                    desfecho.encerrar?.let { throw EncerrarExecucao(it) }
                }
                concluidos += ResultadoTeste.agregar(teste.def.id, teste.def.versao, naoDeterministico, casos)
                emAndamento = null
            }
        }

        // ── determinismo (BEX-03) ────────────────────────────────────────────────────────

        /** Repete 2 casos fixos (os 2 primeiros aplicáveis do 1º teste); saída diferente liga `nao_deterministico` e a mediana de 3. */
        private suspend fun verificarDeterminismo() {
            val primeiro = plano.testes.firstOrNull() ?: return
            for (caso in primeiro.casos.filter(::aplicavel).take(2)) {
                progressoAtual = Progresso(primeiro.def.id, 1, plano.testes.size, 0, primeiro.casos.size, Progresso.Fase.DETERMINISMO)
                aoProgredir(progressoAtual)
                conferirAmbiente()
                val a = umaExecucao(primeiro.def, caso)
                val b = if (a.encerrar == null) umaExecucao(primeiro.def, caso) else a
                previos[caso.id] = listOf(a.resultado, b.resultado)
                if (a.resultado.estado == EstadoCaso.OK && b.resultado.estado == EstadoCaso.OK && a.resultado.saida != b.resultado.saida) {
                    naoDeterministico = true
                }
                (a.encerrar ?: b.encerrar)?.let { throw EncerrarExecucao(it) }
            }
        }

        // ── ambiente (BEX-07) ────────────────────────────────────────────────────────────

        private fun problemaDoAmbiente(): Problema? {
            ambiente.termico()?.let { if (it >= config.termicoDePausa) return Problema.TERMICO }
            ambiente.bateria()?.let { if (it.pct < config.bateriaMinimaPct && !it.carregando) return Problema.BATERIA }
            return null
        }

        /** Pausa enquanto o aparelho está quente ou sem bateria, até [ConfigExecutor.pausaMaximaMs]; passado o prazo, encerra `incompleta`. */
        private suspend fun conferirAmbiente() {
            var esperado = 0L
            while (true) {
                val problema = problemaDoAmbiente() ?: return
                if (esperado >= config.pausaMaximaMs) {
                    throw EncerrarExecucao(if (problema == Problema.TERMICO) MotivoIncompleta.TERMICO else MotivoIncompleta.BATERIA)
                }
                aoProgredir(
                    progressoAtual.copy(fase = if (problema == Problema.TERMICO) Progresso.Fase.PAUSADO_TERMICO else Progresso.Fase.PAUSADO_BATERIA),
                )
                pausa(config.passoDaPausaMs)
                esperado += config.passoDaPausaMs
            }
        }

        // ── caso ─────────────────────────────────────────────────────────────────────────

        private fun aplicavel(caso: Caso): Boolean {
            val janela = plano.janelaContextoTokens ?: return true
            return caso.tokensEntrada + caso.maxTokensSaida <= janela
        }

        private suspend fun rodarCaso(def: TesteDef, caso: Caso): Desfecho {
            if (!aplicavel(caso)) {
                return Desfecho(ResultadoCaso(caso.id, EstadoCaso.NAO_SE_APLICA, motivo = "a janela de contexto do modelo é menor que este caso"))
            }
            val repeticoes = if (naoDeterministico) config.repeticoesSeNaoDeterministico else 1
            val feitos = ArrayList<ResultadoCaso>(previos[caso.id].orEmpty().take(repeticoes))
            var encerrar: MotivoIncompleta? = null
            while (feitos.size < repeticoes && encerrar == null) {
                val desfecho = umaExecucao(def, caso)
                feitos += desfecho.resultado
                encerrar = desfecho.encerrar
            }
            return Desfecho(combinar(feitos), encerrar)
        }

        /** Uma repetição vale por si; várias (não determinístico) valem pela **mediana** das que contaram. */
        private fun combinar(feitos: List<ResultadoCaso>): ResultadoCaso {
            if (feitos.size == 1) return feitos.first()
            val contam = feitos.filter { it.estado == EstadoCaso.OK || it.estado == EstadoCaso.FALHA }
            if (contam.isEmpty()) return feitos.first()
            val ordenadas = contam.sortedBy { it.nota ?: 0.0 }
            return ordenadas[(ordenadas.size - 1) / 2].copy(notasRepeticoes = contam.mapNotNull { it.nota })
        }

        private fun opcoesDoCaso(caso: Caso): GenerationOptions = plano.opcoesBase.copy(maxOutputTokens = caso.maxTokensSaida)

        /** Uma execução do caso, com as tentativas extras do online (BEX-06). */
        private suspend fun umaExecucao(def: TesteDef, caso: Caso): Desfecho {
            val pontuador = pontuadores(def.id) ?: error("o teste ${def.id} não tem pontuador")
            val opcoes = opcoesDoCaso(caso)
            pedidas += opcoes.requestedNames()
            val online = plano.origem == Origem.ONLINE
            var tentativas = 0
            while (true) {
                val termico = ambiente.termico()
                val bateria = ambiente.bateria()?.pct
                val tentativa = tentar(def, caso, opcoes)
                tentativa.metricas?.let {
                    for ((nome, valor) in it.applied) aplicadas.putIfAbsent(nome, valor)
                    ignoradasPeloMotor += it.ignored
                }
                fun base(estado: EstadoCaso, causa: String? = null, motivo: String? = null) = ResultadoCaso(
                    id = caso.id,
                    estado = estado,
                    causa = causa,
                    motivo = motivo,
                    saida = tentativa.texto.ifEmpty { null },
                    primeiroTrechoMs = tentativa.primeiroMs,
                    totalMs = tentativa.totalMs,
                    termico = termico,
                    bateriaPct = bateria,
                )
                when (tentativa) {
                    is Tentativa.Concluiu -> return Desfecho(medidas(base(EstadoCaso.OK), tentativa, caso).let { comNota(it, pontuador, caso, tentativa.texto) })
                    is Tentativa.Estourou -> {
                        if (online) {
                            if (++tentativas < config.tentativasOnline) continue
                            return Desfecho(base(EstadoCaso.INVALIDO, CausaInvalido.REDE.name, "o provedor não respondeu em ${config.timeoutCasoMs / 1000} s"))
                        }
                        return Desfecho(base(EstadoCaso.FALHA, CausaFalha.TIMEOUT.name, "passou de ${config.timeoutCasoMs / 1000} s").comNota0())
                    }
                    is Tentativa.Falhou -> {
                        val motivo = tentativa.mensagem
                        if (online) {
                            when (tentativa.tipo) {
                                ReasoningError.NETWORK -> {
                                    if (++tentativas < config.tentativasOnline) continue
                                    return Desfecho(base(EstadoCaso.INVALIDO, CausaInvalido.REDE.name, motivo))
                                }
                                ReasoningError.RATE_LIMIT ->
                                    return Desfecho(base(EstadoCaso.INVALIDO, CausaInvalido.LIMITE.name, motivo), MotivoIncompleta.RATE_LIMIT)
                                ReasoningError.UNAUTHORIZED, ReasoningError.NOT_ELIGIBLE ->
                                    return Desfecho(base(EstadoCaso.INVALIDO, CausaInvalido.CREDENCIAL.name, motivo), MotivoIncompleta.CREDENCIAL)
                                else -> return Desfecho(base(EstadoCaso.FALHA, CausaFalha.ENGINE.name, motivo).comNota0())
                            }
                        }
                        return when (tentativa.tipo) {
                            ReasoningError.OOM -> Desfecho(base(EstadoCaso.FALHA, CausaFalha.OOM.name, motivo).comNota0())
                            ReasoningError.LOCAL -> Desfecho(base(EstadoCaso.FALHA, CausaFalha.QUEDA.name, motivo).comNota0())
                            ReasoningError.MODEL_MISSING ->
                                Desfecho(base(EstadoCaso.FALHA, CausaFalha.ENGINE.name, motivo).comNota0(), MotivoIncompleta.MODELO_AUSENTE)
                            else -> Desfecho(base(EstadoCaso.FALHA, CausaFalha.ENGINE.name, motivo).comNota0())
                        }
                    }
                }
            }
        }

        private fun ResultadoCaso.comNota0() = copy(nota = 0.0)

        private fun comNota(r: ResultadoCaso, pontuador: Pontuador, caso: Caso, saida: String): ResultadoCaso {
            val pontuacao = pontuador.pontuar(caso, saida)
            return r.copy(nota = pontuacao.nota, criterios = pontuacao.criterios, marcas = pontuacao.marcas.map { it.name }.toSet())
        }

        /** Tokens e tokens/s: os do motor quando ele os dá; senão uma estimativa (caracteres ÷ 4) marcada como tal (BEX-04). */
        private fun medidas(r: ResultadoCaso, t: Tentativa.Concluiu, caso: Caso): ResultadoCaso {
            val m = t.metricas
            val tokensSaida = m?.outputTokens ?: ceil(t.texto.length / 4.0).toInt()
            val estimados = m?.outputTokens == null
            val corrido = t.primeiroMs?.let { t.totalMs - it }
            val porSegundo = m?.tokensPerSecond
                ?: if (corrido != null && corrido > 0 && tokensSaida > 1) (tokensSaida - 1) * 1000.0 / corrido else null
            return r.copy(
                tokensEntrada = m?.inputTokens ?: caso.tokensEntrada,
                tokensSaida = tokensSaida,
                tokensPorSegundo = porSegundo?.takeIf { it.isFinite() },
                tokensEstimados = estimados || m?.inputTokens == null,
            )
        }

        /** Uma chamada ao motor, com o prazo do caso. Cancelar propaga (a exceção sobe); o resto vira [Tentativa]. */
        private suspend fun tentar(def: TesteDef, caso: Caso, opcoes: GenerationOptions): Tentativa {
            val entrada = EntradaDoMotor(def.montar(caso), opcoes)
            val inicio = relogio()
            val texto = StringBuilder()
            var primeiro: Long? = null
            var metricas: ReasoningEvent.Metrics? = null
            var fim: ReasoningEvent? = null
            var erroInterno: String? = null
            val terminou = withTimeoutOrNull(config.timeoutCasoMs) {
                try {
                    motor.executar(entrada)
                        .transformWhile { emit(it); it !is ReasoningEvent.Done && it !is ReasoningEvent.Error }
                        .collect { evento ->
                            when (evento) {
                                is ReasoningEvent.Delta -> {
                                    if (primeiro == null) primeiro = relogio() - inicio
                                    texto.append(evento.text)
                                }
                                is ReasoningEvent.Metrics -> metricas = evento
                                ReasoningEvent.Done, is ReasoningEvent.Error -> fim = evento
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Um motor que lança (cofre, cabeçalho inválido, bug) é erro do motor, não derruba a bancada. Só o nome da classe: a mensagem pode ecoar um segredo.
                    erroInterno = e.javaClass.simpleName
                }
                true
            }
            val total = relogio() - inicio
            val parcial = texto.toString()
            val final = fim
            return when {
                terminou == null -> Tentativa.Estourou(parcial, primeiro, total)
                erroInterno != null -> Tentativa.Falhou(ReasoningError.ENGINE, "o motor lançou $erroInterno", parcial, primeiro, total, metricas)
                final is ReasoningEvent.Error -> Tentativa.Falhou(final.kind, final.message, parcial, primeiro, total, metricas)
                final is ReasoningEvent.Done -> Tentativa.Concluiu(parcial, primeiro, total, metricas)
                else -> Tentativa.Falhou(ReasoningError.ENGINE, "o fluxo terminou sem Done nem Error", parcial, primeiro, total, metricas)
            }
        }
    }
}
