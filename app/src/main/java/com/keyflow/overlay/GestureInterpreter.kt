package com.keyflow.overlay

import kotlin.math.abs

enum class GestureEvent { TAP, TRIPLE_TAP, HOLD, DRAG_START, DRAG_END }

class GestureInterpreter(
    private val tripleTapWindowMs: Long = 300L,
    private val holdThresholdMs: Long = 500L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var tapCount = 0
    private var firstTapTime = 0L
    private var pressStartTime = 0L
    private var isDragging = false

    fun onDown(): GestureEvent? {
        pressStartTime = clock()
        isDragging = false
        val now = pressStartTime

        if (tapCount == 0 || now - firstTapTime > tripleTapWindowMs) {
            tapCount = 1
            firstTapTime = now
        } else {
            tapCount++
        }

        return null
    }

    fun onMove(dx: Float, dy: Float): GestureEvent? {
        if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
            isDragging = true
            return GestureEvent.DRAG_START
        }

        return null
    }

    fun onUp(): GestureEvent? {
        val heldMs = clock() - pressStartTime
        return when {
            isDragging -> {
                isDragging = false
                GestureEvent.DRAG_END
            }
            heldMs >= holdThresholdMs -> {
                tapCount = 0
                GestureEvent.HOLD
            }
            tapCount >= 3 -> {
                tapCount = 0
                GestureEvent.TRIPLE_TAP
            }
            else -> GestureEvent.TAP
        }
    }

    private companion object {
        const val DRAG_THRESHOLD_PX = 10f
    }
}
