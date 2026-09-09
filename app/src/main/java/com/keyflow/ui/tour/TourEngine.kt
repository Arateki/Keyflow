package com.keyflow.ui.tour

import androidx.compose.ui.geometry.Offset
import com.keyflow.core.ButtonState
import com.keyflow.ui.orb.OrbState

/**
 * O que desenhar num instante do roteiro (TOUR-02/05).
 *
 * [reaction] é a reação **vigente**, não a do passo: cada passo declara só o que muda, e o que veio
 * antes continua valendo — senão a orbe voltaria ao estado inicial a cada keyframe.
 */
data class TourFrame(
    val stepIndex: Int,
    val position: Offset,
    val action: FingerAction,
    /** 0..1 dentro do passo corrente — é daqui que saem o ripple do tap e o preenchimento do PRESS. */
    val actionProgress: Float,
    val tapCount: Int,
    val reaction: SceneReaction,
    /** Caminho já percorrido num arrasto (vazio nas demais ações). */
    val trail: List<Offset>,
    /** O roteiro chegou ao fim. Quem decide o que fazer com isso (repetir, avançar) é o player. */
    val finished: Boolean,
)

/** Duração total do roteiro — soma dos passos. */
val GestureScript.totalDurationMs: Int get() = steps.sumOf { it.durationMs }

/**
 * Interpolação pura keyframes → frame. Sem Compose e sem relógio próprio: o tempo entra por
 * parâmetro, então com o clock congelado (galeria/goldens) o mesmo `timeMs` rende sempre o mesmo
 * frame, e a interpolação inteira é testável na JVM.
 *
 * Tempo antes do começo é tratado como 0 e depois do fim é preso no último frame, com [TourFrame.finished].
 */
fun tourFrameAt(script: GestureScript, timeMs: Int): TourFrame {
    val steps = script.steps
    if (steps.isEmpty()) {
        return TourFrame(
            stepIndex = -1,
            position = Offset.Zero,
            action = FingerAction.MOVE,
            actionProgress = 1f,
            tapCount = 1,
            reaction = SceneReaction(),
            trail = emptyList(),
            finished = true,
        )
    }

    val total = script.totalDurationMs
    val clamped = timeMs.coerceIn(0, total)

    var index = 0
    var startedAt = 0
    while (index < steps.lastIndex && startedAt + steps[index].durationMs <= clamped) {
        startedAt += steps[index].durationMs
        index++
    }
    val step = steps[index]
    val progress =
        if (step.durationMs <= 0) 1f else ((clamped - startedAt).toFloat() / step.durationMs).coerceIn(0f, 1f)

    // Onde o dedo estava ao entrar neste passo: o alvo do passo anterior. No primeiro passo ele já
    // nasce no lugar, senão viria deslizando do canto da cena.
    val from = if (index == 0) step.at else steps[index - 1].at

    val position: Offset
    val trail: List<Offset>
    when (step.action) {
        FingerAction.DRAG -> {
            if (step.dragPath.size >= 2) {
                position = pointAlong(step.dragPath, progress)
                trail = walkedSoFar(step.dragPath, progress, position)
            } else {
                position = lerp(from, step.at, progress)
                trail = listOf(from, position)
            }
        }
        FingerAction.MOVE -> {
            position = lerp(from, step.at, progress)
            trail = emptyList()
        }
        // Tocar/segurar/soltar acontecem parados: mover aqui daria a impressão de um gesto que não é.
        else -> {
            position = step.at
            trail = emptyList()
        }
    }

    return TourFrame(
        stepIndex = index,
        position = position,
        action = step.action,
        actionProgress = progress,
        tapCount = step.tapCount,
        reaction = reactionUpTo(steps, index),
        trail = trail,
        finished = timeMs >= total,
    )
}

/** Reação vigente: o último valor declarado para cada campo até [index], inclusive. */
private fun reactionUpTo(steps: List<GestureStep>, index: Int): SceneReaction {
    var orbState: OrbState? = null
    var orbAt: Offset? = null
    var micState: ButtonState? = null
    var keyboardExpanded: Boolean? = null
    var overlayHint: TourOverlayHint? = null
    for (i in 0..index) {
        val r = steps[i].reaction
        r.orbState?.let { orbState = it }
        r.orbAt?.let { orbAt = it }
        r.micState?.let { micState = it }
        r.keyboardExpanded?.let { keyboardExpanded = it }
        r.overlayHint?.let { overlayHint = if (it == TourOverlayHint.NONE) null else it }
    }
    return SceneReaction(orbState, orbAt, micState, keyboardExpanded, overlayHint)
}

private fun lerp(from: Offset, to: Offset, t: Float) =
    Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)

/** Ponto a [progress] (0..1) do comprimento total da polilinha. */
private fun pointAlong(path: List<Offset>, progress: Float): Offset {
    val lengths = path.zipWithNext { a, b -> distance(a, b) }
    val total = lengths.sum()
    if (total <= 0f) return path.last()

    var remaining = total * progress
    for ((i, len) in lengths.withIndex()) {
        if (remaining <= len || i == lengths.lastIndex) {
            val t = if (len <= 0f) 1f else (remaining / len).coerceIn(0f, 1f)
            return lerp(path[i], path[i + 1], t)
        }
        remaining -= len
    }
    return path.last()
}

/** Vértices já deixados para trás, mais a posição atual — é o que a trilha esmaecida desenha. */
private fun walkedSoFar(path: List<Offset>, progress: Float, current: Offset): List<Offset> {
    val lengths = path.zipWithNext { a, b -> distance(a, b) }
    val total = lengths.sum()
    if (total <= 0f) return listOf(path.first(), current)

    val walked = total * progress
    val points = mutableListOf(path.first())
    var accumulated = 0f
    for ((i, len) in lengths.withIndex()) {
        accumulated += len
        if (accumulated >= walked) break
        points += path[i + 1]
    }
    points += current
    return points
}

private fun distance(a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
