package com.keyflow.overlay

import android.os.SystemClock
import kotlin.math.abs

enum class GestureEvent { TAP, TRIPLE_TAP, HOLD, DRAG_START, DRAG_END }

class GestureInterpreter(
    private val tripleTapWindowMs: Long = 300L,
    private val holdThresholdMs: Long = 500L,
    // MONOTÔNICO por padrão: o hold é agendado com `handler.postDelayed` (uptimeMillis) e conferido
    // aqui contra o MESMO limiar. Com o relógio de parede as medidas divergiam e o gesto perdia o
    // modo-arrasto (medido no A32: elapsed=269 contra threshold=270).
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    private var tapCount = 0
    private var firstTapTime = 0L
    private var pressStartTime = 0L
    private var isDragging = false
    private var isPressed = false
    private var holdEmitted = false // HOLD já emitido em andamento → não repete no release

    fun onDown(): GestureEvent? {
        pressStartTime = clock()
        isDragging = false
        isPressed = true
        holdEmitted = false
        val now = pressStartTime

        if (tapCount == 0 || now - firstTapTime > tripleTapWindowMs) {
            tapCount = 1
            firstTapTime = now
        } else {
            tapCount++
        }

        return null
    }

    /** Detecta o HOLD enquanto ainda pressionado (para "segurar trava" entrar no modo-arrasto). */
    fun onHoldElapsed(): GestureEvent? {
        if (!isPressed || isDragging || holdEmitted) return null
        if (clock() - pressStartTime < holdThresholdMs) return null
        holdEmitted = true
        tapCount = 0
        return GestureEvent.HOLD
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
        isPressed = false
        // HOLD já tratado em andamento (onHoldElapsed): o release não repete nada.
        if (holdEmitted) {
            return if (isDragging) { isDragging = false; GestureEvent.DRAG_END } else null
        }
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
