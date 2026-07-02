package com.keyflow.ime.bars

import android.view.KeyEvent
import com.keyflow.ime.keyboard.layout.RepeatSchedule
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lógica pura da alavanca direcional (JOY-01..04): a `BarView` só traduz `MotionEvent` em
 * `onMove(dx, dy)` (deslocamento desde o centro do manche) e agenda ticks com [nextDueMs].
 *
 * - Cruzar o [thresholdPx] numa direção dispara a seta **imediatamente** (JOY-01); manter puxado repete
 *   com aceleração progressiva — [schedule] é o `RepeatSchedule` do backspace por padrão (JOY-02).
 * - Trocar o eixo do puxão sem soltar reinicia o repeat na nova direção, sem disparo espúrio da antiga
 *   na transição (JOY-03).
 * - Voltar ao raio-morto (ou soltar) para imediatamente (JOY-04); um flick curto emite exatamente uma
 *   seta (o disparo imediato do cruzamento — soltar logo depois não gera mais nada).
 */
class JoystickController(
    private val thresholdPx: Float,
    private val schedule: (Int) -> Long = RepeatSchedule::delayForTick,
) {

    /** 4 direções (sem diagonais — decisão do Yan); keycodes DPAD é o que terminais tratam como setas. */
    enum class Direction(val keyCode: Int) {
        UP(KeyEvent.KEYCODE_DPAD_UP),
        DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
        LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
        RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
    }

    var currentDirection: Direction? = null
        private set

    private var tick = 0
    private var nextDueMs = 0L

    /** Deslocamento atual do manche desde o centro; devolve as setas devidas até [nowMs] (0, 1 ou mais). */
    fun onMove(dx: Float, dy: Float, nowMs: Long): List<Direction> {
        val newDirection = directionOf(dx, dy)
        if (newDirection != currentDirection) {
            currentDirection = newDirection
            if (newDirection == null) return emptyList() // voltou ao raio-morto: para (JOY-04)
            tick = 0
            nextDueMs = nowMs + schedule(0)
            return listOf(newDirection) // 1º disparo imediato ao cruzar (JOY-01)
        }
        return drain(nowMs)
    }

    /** Tick agendado pela view (dedo parado puxando): setas devidas até [nowMs]. */
    fun onTick(nowMs: Long): List<Direction> = drain(nowMs)

    /** Quando a view deve agendar o próximo tick; null com o manche no centro/solto. */
    fun nextDueMs(): Long? = currentDirection?.let { nextDueMs }

    /** Dedo solto: para tudo (JOY-04). */
    fun onUp() {
        currentDirection = null
    }

    private fun drain(nowMs: Long): List<Direction> {
        val direction = currentDirection ?: return emptyList()
        val fires = mutableListOf<Direction>()
        while (nowMs >= nextDueMs) {
            fires += direction
            tick++
            nextDueMs += schedule(tick)
        }
        return fires
    }

    private fun directionOf(dx: Float, dy: Float): Direction? {
        if (hypot(dx, dy) < thresholdPx) return null
        return if (abs(dx) >= abs(dy)) {
            if (dx > 0) Direction.RIGHT else Direction.LEFT
        } else {
            if (dy > 0) Direction.DOWN else Direction.UP
        }
    }
}
