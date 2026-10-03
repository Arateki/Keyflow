package com.keyflow.ui.orb

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * O calendário dos eventos raros do mascote: piscadas, tremidas de orelha e olhares de lado. Função pura do tempo
 * (e do estado): o mesmo instante dá sempre o mesmo quadro, sem `Random()`, sem relógio de parede e sem estado entre
 * quadros — é o que deixa a galeria congelar a orbe e os goldens serem bit-estáveis. Mesma ideia do
 * [com.keyflow.ime.GlitchTimeline] e do [com.keyflow.ime.ShimmerTimeline] do teclado.
 *
 * Cada tipo de evento vive em **fatias** de duração própria (a duração de cada tipo é um número sem divisor comum com
 * a dos outros — piscar a cada ~3,2 s, mexer a orelha a cada ~5,2 s, olhar de lado a cada ~7,3 s —, então os três
 * nunca caem no mesmo compasso). Em cada fatia `k` um hash do índice decide **se** o evento acontece e **onde**
 * dentro da fatia ele começa: o intervalo entre dois eventos varia de uma fatia para a outra (nunca metrônomo), mas
 * é limitado — o evento começa entre 8% e 63% da fatia, então dois eventos seguidos ficam sempre a pelo menos 45% da
 * fatia de distância, e uma piscada nunca vaza para a fatia vizinha.
 *
 * O ritmo muda por estado ([OrbState]): o mascote pisca pouco quando escuta (olho arregalado), devagar e pesado
 * quando está sonolento, e vagueia o olhar sem parar quando "pensa" (transcrevendo).
 */
internal object MascotTimeline {

    // --- Piscadas ---

    /** Duração da fatia das piscadas: o mascote pisca em média uma vez por fatia (um pouco menos, ver [BLINK_CHANCE]). */
    fun blinkSlotMs(state: OrbState): Float = when (state) {
        OrbState.Idle -> 3170f
        OrbState.Listening -> 4390f // atento: pisca menos
        OrbState.Paused -> 6130f // sonolento: pisca raro, mas devagar
        OrbState.Transcribing -> 3730f
    }

    /** Quanto dura uma piscada (fechar rápido e abrir mais devagar). A do sonolento é lenta e pesada. */
    fun blinkMs(state: OrbState): Float = when (state) {
        OrbState.Idle -> 160f
        OrbState.Listening -> 120f
        OrbState.Paused -> 640f
        OrbState.Transcribing -> 150f
    }

    /** Chance de a fatia ter uma piscada: poucas ficam sem. */
    private const val BLINK_CHANCE = 0.9f

    /** Chance de a piscada vir dupla (só quando o mascote está desperto). */
    private const val DOUBLE_BLINK_CHANCE = 0.22f

    /** Tempo de olho aberto entre as duas piscadas de uma piscada dupla: nunca coladas. */
    const val DOUBLE_BLINK_GAP_MS = 150f

    /** A fatia [k] tem piscada? */
    fun hasBlink(k: Int, state: OrbState): Boolean = hash01(k, 11) < BLINK_CHANCE

    /** A piscada da fatia [k] é dupla? Nunca no sonolento (a piscada dele já é lenta). */
    fun isDoubleBlink(k: Int, state: OrbState): Boolean =
        state != OrbState.Paused && hash01(k, 12) < DOUBLE_BLINK_CHANCE

    /** Instante (ms) em que começa a (primeira) piscada da fatia [k]. */
    fun blinkStart(k: Int, state: OrbState): Float {
        val slot = blinkSlotMs(state)
        return k * slot + slot * (0.08f + 0.55f * hash01(k, 13))
    }

    /** Fim (ms) de tudo o que a fatia [k] pisca — a segunda piscada incluída. */
    fun blinkEnd(k: Int, state: OrbState): Float {
        val one = blinkMs(state)
        return blinkStart(k, state) + if (isDoubleBlink(k, state)) 2f * one + DOUBLE_BLINK_GAP_MS else one
    }

    /** O quanto a pálpebra está fechada em [t] (ms): 0 aberto, 1 fechado. Fecha rápido e abre devagar. */
    fun blink(t: Float, state: OrbState): Float {
        val slot = blinkSlotMs(state)
        val k = floor(t / slot).toInt()
        if (!hasBlink(k, state)) return 0f
        val one = blinkMs(state)
        val dt = t - blinkStart(k, state)
        if (dt < 0f) return 0f
        if (dt < one) return lid(dt / one)
        if (isDoubleBlink(k, state)) {
            val dt2 = dt - one - DOUBLE_BLINK_GAP_MS
            if (dt2 in 0f..one) return lid(dt2 / one)
        }
        return 0f
    }

    /** Perfil da pálpebra numa piscada, de 0 a 1 do começo ao fim: fecha em 35% do tempo e abre nos 65% restantes. */
    private fun lid(u: Float): Float =
        if (u < 0.35f) smooth(u / 0.35f) else 1f - smooth((u - 0.35f) / 0.65f)

    // --- Orelhas ---

    /** Duração da fatia das tremidas de orelha. */
    fun flickSlotMs(state: OrbState): Float = when (state) {
        OrbState.Idle -> 5230f // uma micro-tremida de vez em quando
        OrbState.Listening -> 2870f // escutando, as orelhas mexem mais
        OrbState.Paused -> 9100f
        OrbState.Transcribing -> 4100f
    }

    private fun flickChance(state: OrbState): Float = when (state) {
        OrbState.Idle -> 0.65f
        OrbState.Listening -> 0.8f
        OrbState.Paused -> 0.3f
        OrbState.Transcribing -> 0.35f
    }

    /** Quanto dura uma tremida de orelha (oscilação amortecida). */
    const val FLICK_MS = 560f

    /** A fatia [k] tem tremida? */
    fun hasFlick(k: Int, state: OrbState): Boolean = hash01(k, 21) < flickChance(state)

    /** Instante (ms) em que começa a tremida da fatia [k]. */
    fun flickStart(k: Int, state: OrbState): Float {
        val slot = flickSlotMs(state)
        return k * slot + slot * (0.08f + 0.55f * hash01(k, 22))
    }

    /** Qual orelha treme na fatia [k]: -1 a esquerda, +1 a direita, 0 as duas (15% das vezes). */
    fun flickSide(k: Int): Int {
        val h = hash01(k, 23)
        return when {
            h < 0.15f -> 0
            h < 0.575f -> -1
            else -> 1
        }
    }

    /**
     * A tremida da orelha, em graus a somar ao ângulo dela em [t] (ms): um estalo que oscila e amortece
     * (≈ 190 ms por ida e volta, perdendo ~1/e a cada 130 ms), pequeno o bastante para ser "uma tremidinha".
     */
    fun earFlickDeg(t: Float, state: OrbState, rightEar: Boolean): Float {
        val slot = flickSlotMs(state)
        val k = floor(t / slot).toInt()
        if (!hasFlick(k, state)) return 0f
        val side = flickSide(k)
        if (side != 0 && (side > 0) != rightEar) return 0f
        val dt = t - flickStart(k, state)
        if (dt < 0f || dt > FLICK_MS) return 0f
        val amp = 11f * (0.7f + 0.3f * hash01(k, 24))
        // A orelha direita gira para o lado oposto: as duas "abrem" ou "fecham" juntas quando tremem as duas.
        return (if (rightEar) -1f else 1f) * amp * exp(-dt / 130f) * sin(2f * PI.toFloat() * dt / 190f)
    }

    // --- Olhares ---

    /** Duração da fatia dos olhares de lado: o "pensando" (transcrevendo) troca de alvo a cada fatia curta. */
    fun glanceSlotMs(state: OrbState): Float = when (state) {
        OrbState.Idle -> 7310f
        OrbState.Listening -> 5170f
        OrbState.Paused -> 11_000f
        OrbState.Transcribing -> 1550f
    }

    private fun glanceChance(state: OrbState): Float = when (state) {
        OrbState.Idle -> 0.6f
        OrbState.Listening -> 0.5f
        OrbState.Paused -> 0f // sonolento: não olha para lado nenhum
        OrbState.Transcribing -> 1f // pensando: o olhar vagueia sem parar
    }

    fun hasGlance(k: Int, state: OrbState): Boolean = hash01(k, 31) < glanceChance(state)

    /** Instante (ms) em que o olhar da fatia [k] começa a sair do centro. */
    fun glanceStart(k: Int, state: OrbState): Float {
        val slot = glanceSlotMs(state)
        return k * slot + slot * (if (state == OrbState.Transcribing) 0.03f else 0.05f + 0.30f * hash01(k, 32))
    }

    /** Quanto o olhar fica fora do centro (ida e volta incluídas). */
    fun glanceMs(k: Int, state: OrbState): Float = when (state) {
        OrbState.Transcribing -> 1150f
        else -> 1100f + 900f * hash01(k, 33)
    }

    /** O quanto o olhar está fora do centro em [t] (0 no centro, 1 no alvo): sobe em 150 ms, segura e volta em 260 ms. */
    fun glanceAmount(t: Float, state: OrbState): Float {
        val k = floor(t / glanceSlotMs(state)).toInt()
        if (!hasGlance(k, state)) return 0f
        val dt = t - glanceStart(k, state)
        val total = glanceMs(k, state)
        if (dt < 0f || dt > total) return 0f
        return when {
            dt < GLANCE_IN_MS -> smooth(dt / GLANCE_IN_MS)
            dt > total - GLANCE_OUT_MS -> smooth((total - dt) / GLANCE_OUT_MS)
            else -> 1f
        }
    }

    private const val GLANCE_IN_MS = 150f
    private const val GLANCE_OUT_MS = 260f

    /** Alvo horizontal do olhar da fatia que contém [t] (-1 esquerda, +1 direita). Pensando, alterna de lado a cada fatia. */
    fun glanceTargetX(t: Float, state: OrbState): Float {
        val k = floor(t / glanceSlotMs(state)).toInt()
        if (state == OrbState.Transcribing) return if (k % 2 == 0) -0.85f else 0.85f
        val h = hash01(k, 34)
        val side = if (h < 0.5f) -1f else 1f
        return side * (0.55f + 0.45f * hash01(k, 35))
    }

    /** Alvo vertical do olhar da fatia que contém [t] (-1 para cima, +1 para baixo). Pensando, sempre para cima. */
    fun glanceTargetY(t: Float, state: OrbState): Float {
        val k = floor(t / glanceSlotMs(state)).toInt()
        if (state == OrbState.Transcribing) return -0.75f
        return -0.35f + 0.6f * (hash01(k, 36) - 0.35f)
    }

    // --- Rabo ---

    /** Duração da fatia do abano do rabo. */
    const val WAG_SLOT_MS = 8230f

    /** Duração de um abano (ms). */
    const val WAG_MS = 1500f

    fun hasWag(k: Int): Boolean = hash01(k, 41) < 0.55f

    fun wagStart(k: Int): Float = k * WAG_SLOT_MS + WAG_SLOT_MS * (0.05f + 0.45f * hash01(k, 42))

    /** Envelope do abano em [t] (0 parado, 1 no auge): sobe e desce suave, no meio do evento. Só para o mascote em repouso. */
    fun wag(t: Float): Float {
        val k = floor(t / WAG_SLOT_MS).toInt()
        if (!hasWag(k)) return 0f
        val u = (t - wagStart(k)) / WAG_MS
        return if (u in 0f..1f) sin(PI.toFloat() * u) else 0f
    }

    // --- Utilidades ---

    /** Suavização 0..1 (ease in-out cúbico, 3u² − 2u³), com a entrada limitada ao intervalo. */
    fun smooth(u: Float): Float {
        val x = u.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Número estável em 0..1 a partir de dois inteiros (o mesmo par dá sempre o mesmo valor), por mistura de bits —
     * não por `sin`, que depende da precisão do ponto flutuante de cada aparelho.
     */
    fun hash01(a: Int, b: Int): Float {
        var h = a * -0x61c88647 + b * -0x7a143589
        h = (h xor (h ushr 15)) * -0x7b6b0e0f
        h = (h xor (h ushr 13)) * -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h ushr 8) / 16_777_216f
    }
}
