package com.keyflow.orbe

import kotlin.math.abs

/**
 * Contabiliza uma rajada de toques com relógio injetável (KBO-13).
 *
 * A janela ([janelaMs], padrão 250 ms de `Settings.tapBurstWindowMs`) é reiniciada a cada toque e a contagem só
 * resolve **uma única vez** quando o tempo decorrido desde o último toque atinge [janelaMs].
 */
class RajadaDeToques(
    private val relogio: () -> Long,
    private val janelaMs: () -> Long = { JANELA_PADRAO_MS },
) {
    var contagemPendente: Int = 0
        private set

    private var ultimoToqueEmMs: Long? = null

    /** Registra um toque na rajada e reinicia a janela a partir do instante atual de [relogio]. */
    fun registrarToque(): Int {
        contagemPendente += 1
        ultimoToqueEmMs = relogio()
        return contagemPendente
    }

    /** Tempo restante (ms) até a rajada assentar, ou `null` quando não há toque pendente. */
    fun tempoRestanteMs(): Long? {
        val ultimo = ultimoToqueEmMs ?: return null
        val decorrido = (relogio() - ultimo).coerceAtLeast(0L)
        return (janelaMs() - decorrido).coerceAtLeast(0L)
    }

    /**
     * Resolve a rajada se a janela desde o último toque já tiver assentado (`relogio() - ultimo >= janelaMs()`).
     *
     * Devolve a contagem de toques **uma vez** e limpa o estado interno; chamadas seguintes devolvem `null`.
     */
    fun assentarSePronta(): Int? {
        val ultimo = ultimoToqueEmMs ?: return null
        if (relogio() - ultimo < janelaMs()) return null
        val total = contagemPendente
        cancelar()
        return if (total > 0) total else null
    }

    /** Descarta a rajada pendente sem emitir contagem (ex.: quando segurar ou arrastar assume o gesto). */
    fun cancelar() {
        contagemPendente = 0
        ultimoToqueEmMs = null
    }

    companion object {
        const val JANELA_PADRAO_MS = 250L
    }
}

/**
 * Estado instantâneo das três direções durante o arrasto na orbe do teclado (KBO-13, KBO-18).
 */
data class ProximidadeDeArrasto(
    val emArrasto: Boolean,
    val cima: Float,
    val lado: Float,
    val baixo: Float,
    val direcaoDominante: Interacao?,
    val proximidadeDominante: Float,
)

/**
 * Classificador JVM puro de toques, segurar e arrastar da orbe do teclado (KBO-13, KBO-18, design §4).
 *
 * Limiares idênticos aos da orbe flutuante:
 * - segurar = `janela + 150 ms` ([OFFSET_SEGURAR_MS]);
 * - começar a arrastar = `10 px` ([LIMIAR_INICIO_ARRASTO_PX]);
 * - ativar zona de arrasto = `0,7 × diâmetroAtualPx` ([FRACAO_ATIVACAO_ARRASTO]);
 * - direção lateral "para o centro da tela" = sinal de `(centroDaTelaX - centroDaOrbeX)` no início do toque.
 */
class ClassificadorDeGesto(
    private val relogio: () -> Long,
    private val janelaRajadaMs: () -> Long = { RajadaDeToques.JANELA_PADRAO_MS },
) {
    val rajada: RajadaDeToques = RajadaDeToques(relogio = relogio, janelaMs = janelaRajadaMs)

    private var pressionado: Boolean = false
    private var inicioPressaoMs: Long = 0L
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var diametroAtualPx: Float = 0f
    private var sinalLateralParaCentro: Float = 1f
    private var emArrasto: Boolean = false
    private var segurarEmitido: Boolean = false
    private var ultimaProximidade: ProximidadeDeArrasto = ProximidadeDeArrasto(
        emArrasto = false,
        cima = 0f,
        lado = 0f,
        baixo = 0f,
        direcaoDominante = null,
        proximidadeDominante = 0f,
    )

    /** Limiar de segurar (janela da rajada + 150 ms — KBO-13). */
    fun limiarSegurarMs(): Long = janelaRajadaMs() + OFFSET_SEGURAR_MS

    /** Distância em pixels para ativar uma direção de arrasto (`0,7 × diâmetroAtualPx` — KBO-13). */
    fun distanciaAtivacaoArrastoPx(diametroPx: Float = diametroAtualPx): Float =
        diametroPx * FRACAO_ATIVACAO_ARRASTO

    /**
     * Inicia um toque em `(x, y)` com o [diametroDaOrbePx] atual e a geometria horizontal para calcular o sentido
     * "para o centro da tela" (`centroDaTelaX - centroDaOrbeX`).
     */
    fun aoPressionar(
        x: Float,
        y: Float,
        diametroDaOrbePx: Float,
        centroDaOrbeX: Float = x,
        centroDaTelaX: Float = x + 1f,
    ) {
        pressionado = true
        inicioPressaoMs = relogio()
        downX = x
        downY = y
        diametroAtualPx = diametroDaOrbePx
        sinalLateralParaCentro = sinalParaCentroDaTela(centroDaOrbeX = centroDaOrbeX, centroDaTelaX = centroDaTelaX)
        emArrasto = false
        segurarEmitido = false
        ultimaProximidade = ProximidadeDeArrasto(
            emArrasto = false,
            cima = 0f,
            lado = 0f,
            baixo = 0f,
            direcaoDominante = null,
            proximidadeDominante = 0f,
        )
    }

    /**
     * Avalia se o dedo permaneceu pressionado sem arrastar pelo limiar de segurar (`janela + 150 ms`).
     *
     * Quando atingido, cancela qualquer rajada de toques pendente e devolve [Interacao.SEGURAR] **uma única vez**
     * naquele ciclo de pressão.
     */
    fun avaliarSegurar(): Interacao? {
        if (!pressionado || emArrasto || segurarEmitido) return null
        if (relogio() - inicioPressaoMs < limiarSegurarMs()) return null
        segurarEmitido = true
        rajada.cancelar()
        return Interacao.SEGURAR
    }

    /**
     * Atualiza o deslocamento do dedo desde [aoPressionar] e devolve a proximidade das três direções (`0f..1f`).
     */
    fun aoMover(x: Float, y: Float, enviando: Boolean = false): ProximidadeDeArrasto {
        if (!pressionado) return ultimaProximidade
        val dx = x - downX
        val dy = y - downY
        if (!emArrasto && (abs(dx) > LIMIAR_INICIO_ARRASTO_PX || abs(dy) > LIMIAR_INICIO_ARRASTO_PX)) {
            emArrasto = true
            rajada.cancelar()
        }
        val ativacao = distanciaAtivacaoArrastoPx(diametroAtualPx).coerceAtLeast(1f)
        val cima = if (enviando) 0f else (-dy / ativacao).coerceIn(0f, 1f)
        val baixo = if (enviando) 0f else (dy / ativacao).coerceIn(0f, 1f)
        val lado = ((dx * sinalLateralParaCentro) / ativacao).coerceIn(0f, 1f)

        val (direcao, prox) = when {
            cima <= 0f && lado <= 0f && baixo <= 0f -> null to 0f
            cima >= lado && cima >= baixo -> Interacao.ARRASTAR_CIMA to cima
            baixo >= lado && baixo >= cima -> Interacao.ARRASTAR_BAIXO to baixo
            else -> Interacao.ARRASTAR_LADO to lado
        }
        ultimaProximidade = ProximidadeDeArrasto(
            emArrasto = emArrasto,
            cima = cima,
            lado = lado,
            baixo = baixo,
            direcaoDominante = direcao,
            proximidadeDominante = prox,
        )
        return ultimaProximidade
    }

    /**
     * Finaliza o ciclo ao soltar o dedo:
     * - se entrou em arrasto e cruzou `0,7 × diâmetro` (`proximidadeDominante >= 1f`), devolve a direção de arrasto;
     * - se entrou em arrasto mas soltou antes da zona (`< 1f`), devolve `null` (não conta como toque);
     * - se segurou por `>= janela + 150 ms` sem que [avaliarSegurar] já tivesse emitido, devolve [Interacao.SEGURAR];
     * - se foi um toque curto, registra na [rajada] e devolve `null` (a resolução ocorre em [assentarContagemDeToques]).
     */
    fun aoSoltar(): Interacao? {
        if (!pressionado) return null
        pressionado = false
        val tempoSeguradoMs = relogio() - inicioPressaoMs

        if (emArrasto) {
            val res = if (ultimaProximidade.proximidadeDominante >= 1f) {
                ultimaProximidade.direcaoDominante
            } else {
                null
            }
            emArrasto = false
            segurarEmitido = false
            return res
        }

        if (segurarEmitido) {
            segurarEmitido = false
            return null
        }

        if (tempoSeguradoMs >= limiarSegurarMs()) {
            rajada.cancelar()
            return Interacao.SEGURAR
        }

        rajada.registrarToque()
        return null
    }

    /** Cancela o ciclo atual (`ACTION_CANCEL`), limpando segurar/arrasto em curso. */
    fun aoCancelar() {
        pressionado = false
        emArrasto = false
        segurarEmitido = false
    }

    /** Atalho para [RajadaDeToques.assentarSePronta]. */
    fun assentarContagemDeToques(): Int? = rajada.assentarSePronta()

    companion object {
        const val OFFSET_SEGURAR_MS = 150L
        const val FRACAO_ATIVACAO_ARRASTO = 0.7f
        const val LIMIAR_INICIO_ARRASTO_PX = 10f

        /**
         * Sinal (+1 para a direita, -1 para a esquerda) que aponta do centro da orbe para o centro da tela (KBO-18, A5).
         */
        fun sinalParaCentroDaTela(centroDaOrbeX: Float, centroDaTelaX: Float): Float =
            if (centroDaTelaX >= centroDaOrbeX) 1f else -1f
    }
}
