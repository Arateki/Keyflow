package com.keyflow.ime.bars

import com.keyflow.core.shortcuts.GridCell
import com.keyflow.core.shortcuts.ShortcutGrid
import kotlin.math.abs
import kotlin.math.hypot

/** Direção em que a grade efêmera se estende a partir do dedo (GRD-01). */
enum class GridAnchor { UP, DOWN, LEFT, RIGHT }

/** Retângulo puro em px de tela (sem `android.graphics.Rect`, cujos métodos são stubs em JVM). */
data class GridFrame(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * Máquina de estados pura da interação press-drag-release da grade efêmera (GRD-01..06 — lógica; o
 * rendering/PopupWindow fica na `BarView`). Uma sessão nasce no DOWN sobre o gatilho e morre no UP:
 *
 * - **Pending**: dedo ainda perto da origem; o 1º deslocamento além de [anchorThresholdPx] ancora a grade
 *   na direção dominante do gesto (decisão (a) do Yan, 2026-07-02) — o dedo entra pela borda de origem e o
 *   retângulo é clampado aos limites da tela (edge case da spec).
 * - **Active**: rastreia a célula sob o dedo ([Snapshot.cellChanged] dispara a háptica) e o reveal do modo
 *   aprendizado: parado ≥ [revealDelayMs] revela a grade inteira; mover além de [moveEpsilonPx] oculta.
 * - **onUp**: solta sobre célula atribuída → [Result.Emit]; sobre o gatilho, fora da grade, célula vazia
 *   ou antes de ancorar → [Result.Cancel] (nada é emitido).
 */
class GridSession(
    private val grid: ShortcutGrid,
    private val cellWidthPx: Float,
    private val cellHeightPx: Float,
    private val screenWidthPx: Float,
    private val screenHeightPx: Float,
    private val originX: Float,
    private val originY: Float,
    /** Retângulo do gatilho em coordenadas de tela — soltar de volta sobre ele cancela (GRD-05). */
    private val triggerFrame: GridFrame? = null,
    private val anchorThresholdPx: Float,
    private val moveEpsilonPx: Float = anchorThresholdPx / 3f,
    private val revealDelayMs: Long = REVEAL_DELAY_MS,
) {

    sealed interface State {
        data object Pending : State
        data class Active(
            val anchor: GridAnchor,
            val frame: GridFrame,
            /** Índice `row * cols + col` da célula sob o dedo, ou null fora da grade. */
            val cell: Int?,
            /** Parado ≥ [revealDelayMs]: grade inteira revelada (modo aprendizado, GRD-06). */
            val revealed: Boolean,
        ) : State
    }

    /** Estado após um evento + se a célula mudou desde o último (gancho da háptica — GRD-03). */
    data class Snapshot(val state: State, val cellChanged: Boolean)

    sealed interface Result {
        data class Emit(val cell: GridCell) : Result
        data object Cancel : Result
    }

    private var anchor: GridAnchor? = null
    private var frame: GridFrame? = null
    private var lastCell: Int? = null
    private var lastX = originX
    private var lastY = originY
    private var lastMovementMs = Long.MIN_VALUE

    fun onMove(x: Float, y: Float, nowMs: Long): Snapshot {
        val currentFrame = frame ?: return maybeAnchor(x, y, nowMs)
        if (hypot(x - lastX, y - lastY) > moveEpsilonPx) {
            lastMovementMs = nowMs
            lastX = x
            lastY = y
        }
        val cell = cellAt(currentFrame, x, y)
        val changed = cell != lastCell
        lastCell = cell
        return Snapshot(activeState(currentFrame, cell, nowMs), cellChanged = changed)
    }

    /** Reavaliação sem movimento (tick agendado pela view) — é como o reveal dispara com o dedo parado. */
    fun snapshot(nowMs: Long): Snapshot {
        val currentFrame = frame ?: return Snapshot(State.Pending, cellChanged = false)
        return Snapshot(activeState(currentFrame, lastCell, nowMs), cellChanged = false)
    }

    fun onUp(x: Float, y: Float): Result {
        val currentFrame = frame ?: return Result.Cancel
        if (triggerFrame?.contains(x, y) == true) return Result.Cancel
        val index = cellAt(currentFrame, x, y) ?: return Result.Cancel
        return grid.cells[index]?.let { Result.Emit(it) } ?: Result.Cancel
    }

    // ── internos ─────────────────────────────────────────────────────────────

    private fun maybeAnchor(x: Float, y: Float, nowMs: Long): Snapshot {
        val dx = x - originX
        val dy = y - originY
        if (hypot(dx, dy) < anchorThresholdPx) return Snapshot(State.Pending, cellChanged = false)

        val direction = if (abs(dx) >= abs(dy)) {
            if (dx > 0) GridAnchor.RIGHT else GridAnchor.LEFT
        } else {
            if (dy > 0) GridAnchor.DOWN else GridAnchor.UP
        }
        anchor = direction
        frame = frameFor(direction, x, y)
        lastMovementMs = nowMs
        lastX = x
        lastY = y
        val cell = cellAt(frame!!, x, y)
        lastCell = cell
        return Snapshot(activeState(frame!!, cell, nowMs), cellChanged = cell != null)
    }

    /** Grade nasce encostada no dedo, estendendo-se na direção; clamp nos dois eixos (edge case). */
    private fun frameFor(direction: GridAnchor, x: Float, y: Float): GridFrame {
        val w = grid.cols * cellWidthPx
        val h = grid.rows * cellHeightPx
        val centeredLeft = clamp(x - w / 2f, 0f, screenWidthPx - w)
        val centeredTop = clamp(y - h / 2f, 0f, screenHeightPx - h)
        return when (direction) {
            GridAnchor.UP -> GridFrame(centeredLeft, clamp(y - h, 0f, screenHeightPx - h), w, h)
            GridAnchor.DOWN -> GridFrame(centeredLeft, clamp(y, 0f, screenHeightPx - h), w, h)
            GridAnchor.LEFT -> GridFrame(clamp(x - w, 0f, screenWidthPx - w), centeredTop, w, h)
            GridAnchor.RIGHT -> GridFrame(clamp(x, 0f, screenWidthPx - w), centeredTop, w, h)
        }
    }

    private fun activeState(currentFrame: GridFrame, cell: Int?, nowMs: Long): State.Active = State.Active(
        anchor = anchor!!,
        frame = currentFrame,
        cell = cell,
        revealed = nowMs - lastMovementMs >= revealDelayMs,
    )

    private fun cellAt(currentFrame: GridFrame, x: Float, y: Float): Int? {
        if (!currentFrame.contains(x, y)) return null
        val col = ((x - currentFrame.left) / cellWidthPx).toInt().coerceIn(0, grid.cols - 1)
        val row = ((y - currentFrame.top) / cellHeightPx).toInt().coerceIn(0, grid.rows - 1)
        return row * grid.cols + col
    }

    private fun clamp(v: Float, min: Float, max: Float): Float = v.coerceIn(min, maxOf(min, max))

    companion object {
        /** Dedo parado por este tempo revela a grade inteira (modo aprendizado — GRD-06). */
        const val REVEAL_DELAY_MS = 400L
    }
}
