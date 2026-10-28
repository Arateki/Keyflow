package com.keyflow.bancada.resultado

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Em que pé está uma execução gravada. [EM_ANDAMENTO] no disco sem ninguém rodando = o processo morreu no meio (BEX-10). */
enum class EstadoExecucao(val id: String) {
    EM_ANDAMENTO("em-andamento"),
    COMPLETA("completa"),
    INCOMPLETA("incompleta");

    companion object {
        fun deId(id: String): EstadoExecucao = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("estado de execução desconhecido: $id")
    }
}

/** Por que uma execução terminou `incompleta` (BEX-04/06/07, BEX-10). Entra no resultado e na tela. */
enum class MotivoIncompleta {
    CANCELADA,
    TERMICO,
    BATERIA,
    RATE_LIMIT,
    CREDENCIAL,
    MODELO_AUSENTE,
    INTERROMPIDA,
}

/** O destino de um caso (BEX-06). Só [OK] e [FALHA] entram na média; [INVALIDO] e [NAO_SE_APLICA] são contados à parte. */
enum class EstadoCaso { OK, FALHA, INVALIDO, NAO_SE_APLICA }

/** A causa de uma [EstadoCaso.FALHA]: falha real do modelo no aparelho, nota 0 com marca. */
enum class CausaFalha { OOM, QUEDA, TIMEOUT, ENGINE }

/** A causa de um [EstadoCaso.INVALIDO]: o caso não mediu o modelo, mediu a rede, o limite ou a credencial. */
enum class CausaInvalido { REDE, LIMITE, CREDENCIAL }

data class Aparelho(
    val fabricante: String,
    val modelo: String,
    val soc: String?,
    val abi: String,
    val ramTotalMb: Int,
    val sdk: Int,
)

data class AppInfo(val versionName: String, val versionCode: Int, val build: String, val commit: String?)

data class MotorInfo(val nome: String, val versao: String?)

data class ModeloInfo(
    val id: String,
    /** `local` ou o provedor (`openai`, `anthropic`, `google`, `custom`). */
    val origem: String,
    val sha256: String?,
    /** `CPU`, `GPU` ou `nuvem`. */
    val backend: String,
    val threads: Int?,
    val pensamento: Boolean,
)

/** O que o motor fez com as opções pedidas (BEX-02): nunca em silêncio. */
data class OpcoesRegistradas(val aplicadas: Map<String, String>, val ignoradas: Set<String>)

data class Cargas(val friaMs: Long?, val quenteMs: Long?, val ramPicoMb: Int?, val memDisponivelMb: Int?)

/** O resultado de um caso, gravado como o motor e a pontuação o viram. */
data class ResultadoCaso(
    val id: String,
    val estado: EstadoCaso,
    /** Nome da [CausaFalha] ou da [CausaInvalido], ou `null`. */
    val causa: String? = null,
    /** Texto de diagnóstico (nunca segredo; passa pelo filtro ao gravar). */
    val motivo: String? = null,
    /** Só [EstadoCaso.OK] e [EstadoCaso.FALHA] têm nota (a falha vale 0). */
    val nota: Double? = null,
    val criterios: Map<String, Double> = emptyMap(),
    val marcas: Set<String> = emptySet(),
    val saida: String? = null,
    val primeiroTrechoMs: Long? = null,
    val totalMs: Long? = null,
    val tokensEntrada: Int? = null,
    val tokensSaida: Int? = null,
    val tokensPorSegundo: Double? = null,
    /** Verdadeiro quando os tokens vieram de uma estimativa (caracteres ÷ 4) e não do motor (BEX-04). */
    val tokensEstimados: Boolean = false,
    val termico: Int? = null,
    val bateriaPct: Int? = null,
    /** Com `nao_deterministico`: as notas das repetições (a mediana é [nota]). */
    val notasRepeticoes: List<Double> = emptyList(),
)

/** O resultado de um teste: a nota média dos casos que contam e as medidas agregadas. */
data class ResultadoTeste(
    val teste: String,
    val versaoTeste: Int,
    /** Média dos casos [EstadoCaso.OK] e [EstadoCaso.FALHA]; `null` se nenhum contou. */
    val nota: Double?,
    /** Quantos casos contaram na média. */
    val n: Int,
    val invalidos: Int,
    val naoSeAplica: Int,
    val naoDeterministico: Boolean,
    val primeiroTrechoMsP50: Long?,
    val totalMsP50: Long?,
    val tokensPorSegundoMedia: Double?,
    val casos: List<ResultadoCaso>,
) {
    companion object {
        /** Agrega [casos] (BHS-01): média, contagens, mediana dos tempos e média de tokens/s — só dos casos que contaram. */
        fun agregar(teste: String, versaoTeste: Int, naoDeterministico: Boolean, casos: List<ResultadoCaso>): ResultadoTeste {
            val contam = casos.filter { it.estado == EstadoCaso.OK || it.estado == EstadoCaso.FALHA }
            val medidos = casos.filter { it.estado == EstadoCaso.OK }
            return ResultadoTeste(
                teste = teste,
                versaoTeste = versaoTeste,
                nota = contam.mapNotNull { it.nota }.takeIf { it.isNotEmpty() }?.average(),
                n = contam.size,
                invalidos = casos.count { it.estado == EstadoCaso.INVALIDO },
                naoSeAplica = casos.count { it.estado == EstadoCaso.NAO_SE_APLICA },
                naoDeterministico = naoDeterministico,
                primeiroTrechoMsP50 = mediana(medidos.mapNotNull { it.primeiroTrechoMs }),
                totalMsP50 = mediana(medidos.mapNotNull { it.totalMs }),
                tokensPorSegundoMedia = medidos.mapNotNull { it.tokensPorSegundo }.takeIf { it.isNotEmpty() }?.average(),
                casos = casos.toList(), // cópia: quem chama continua acrescentando casos à sua lista
            )
        }

        /** A mediana (o elemento do meio da lista ordenada; nos pares, o inferior — sem inventar um valor que não foi medido). */
        fun mediana(valores: List<Long>): Long? = valores.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }
    }
}

/**
 * Uma execução da bancada (BHS-01): quem rodou (aparelho, app, motor, modelo), com o quê (corpus, opções) e o que deu. Sem serial, Android ID,
 * conta, chave ou cabeçalho (BHS-02) — nenhum campo daqui aceita isso.
 */
data class Execucao(
    val id: String,
    /** ISO-8601 em UTC (`2026-10-28T14:03:11Z`). */
    val iniciada: String,
    val estado: EstadoExecucao,
    val motivo: MotivoIncompleta? = null,
    val aparelho: Aparelho,
    val app: AppInfo,
    val motor: MotorInfo,
    val corpus: Int,
    /** `fumaca` (1–2 casos por teste) ou `completo`. */
    val perfil: String,
    val modelo: ModeloInfo,
    val opcoes: OpcoesRegistradas,
    val cargas: Cargas? = null,
    val testes: List<ResultadoTeste> = emptyList(),
) {
    /** O resumo agregado de `verification/bancada/` (BAU-06): o mesmo resultado sem a saída do modelo nem os critérios por caso. */
    fun semSaidas(): Execucao = copy(testes = testes.map { t -> t.copy(casos = t.casos.map { it.copy(saida = null, criterios = emptyMap()) }) })

    /** A chave de comparabilidade de [teste] nesta execução, ou `null` se o teste não está nela. */
    fun chaveDe(teste: String): ChaveDeComparabilidade? = testes.firstOrNull { it.teste == teste }?.let { ChaveDeComparabilidade.de(this, it) }

    /** Entra em comparação (BHS-06): só execução completa. */
    val comparavel: Boolean get() = estado == EstadoExecucao.COMPLETA
}

/** A data de [epochMs] como ISO-8601 UTC, sem `java.time` (o `minSdk` é 24). */
fun isoUtc(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))

/** O inverso de [isoUtc]; `null` se [iso] não está nesse formato. */
fun epochDeIso(iso: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(iso)?.time
} catch (e: java.text.ParseException) {
    null
}
