package com.keyflow.overlay

import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode

/** Intenção de alto nível derivada de um gesto + modo (REC-03/04, OVL-03/04). */
enum class GestureIntent { START, STOP, PAUSE, RESUME, REPOSITION_ONLY, KEYBOARD_PICKER, NONE }

/**
 * Sinais de toque consumidos pelo [RecordingGestureController].
 *
 * - `DOWN`/`UP` (crus) só importam no PUSH_TO_TALK, onde a gravação é gated pela pressão.
 * - `TAP`/`HOLD`/`TRIPLE_TAP`/`DRAG_*` vêm classificados do [GestureInterpreter] (modo TOGGLE).
 *
 * A fiação de produção (qual sinal alimentar por modo) fica na T22; aqui é só a lógica pura.
 */
enum class TouchSignal { DOWN, UP, TAP, HOLD, TRIPLE_TAP, DRAG_START, DRAG_END }

/**
 * Mapeia eventos de toque + modo de gravação para [GestureIntent], reusando o [ButtonStateMachine]
 * (transições de pausa adicionadas na T19) como fonte de verdade do estado do botão.
 *
 * Lógica pura e determinística — o `clock` injetável cobre a janela do tap-rápido do PTT (`Wptt`).
 */
class RecordingGestureController(
    private val mode: () -> RecordingMode,
    targetAvailable: () -> Boolean = { true },
    private val pttPauseWindowMs: Long = DEFAULT_PTT_PAUSE_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val machine = ButtonStateMachine(targetAvailable = targetAvailable)

    /** Início do release PTT pendente (RECORDING aguardando Stop ou tap-rápido→Pause). */
    private var pendingReleaseAt: Long? = null

    /** Marca um ciclo de arrasto em curso p/ suprimir TAP/HOLD acidental (anti-movimento). */
    private var draggingThisCycle = false

    val state: ButtonState get() = machine.state

    fun onSignal(signal: TouchSignal): GestureIntent = when (mode()) {
        RecordingMode.TOGGLE -> onToggle(signal)
        RecordingMode.PUSH_TO_TALK -> onPushToTalk(signal)
    }

    /**
     * Decorrida a janela `Wptt` após um release PTT sem tap-rápido: encerra de fato (→ STOP).
     * No-op se a janela já foi consumida (por um Pause) ou não houver release pendente.
     */
    fun onReleaseTimeout(): GestureIntent {
        val started = pendingReleaseAt ?: return GestureIntent.NONE
        if (clock() - started < pttPauseWindowMs) return GestureIntent.NONE
        pendingReleaseAt = null
        machine.onStop()
        return GestureIntent.STOP
    }

    // Passagens p/ o consumidor (T22) manter o estado coerente após a transcrição.
    fun onTargetChanged(available: Boolean) = machine.onTargetChanged(available)
    fun onTranscriptionSuccess() = machine.onTranscriptionSuccess()
    fun onTranscriptionError() = machine.onTranscriptionError()
    fun onErrorDismissed() = machine.onErrorDismissed()

    private fun onToggle(signal: TouchSignal): GestureIntent = when (signal) {
        TouchSignal.DRAG_START -> {
            draggingThisCycle = true
            GestureIntent.REPOSITION_ONLY
        }
        TouchSignal.DRAG_END -> {
            draggingThisCycle = false
            GestureIntent.NONE
        }
        TouchSignal.TRIPLE_TAP -> GestureIntent.KEYBOARD_PICKER
        TouchSignal.TAP -> when {
            draggingThisCycle -> GestureIntent.NONE
            machine.state.isIdle -> start()
            machine.state == ButtonState.RECORDING || machine.state == ButtonState.PAUSED -> stop()
            else -> GestureIntent.NONE
        }
        TouchSignal.HOLD -> when {
            draggingThisCycle -> GestureIntent.NONE
            machine.state == ButtonState.RECORDING -> pause()
            machine.state == ButtonState.PAUSED -> resume()
            else -> GestureIntent.NONE
        }
        // DOWN/UP não são usados no TOGGLE (gestos chegam já classificados).
        TouchSignal.DOWN, TouchSignal.UP -> GestureIntent.NONE
    }

    private fun onPushToTalk(signal: TouchSignal): GestureIntent = when (signal) {
        TouchSignal.DRAG_START -> GestureIntent.REPOSITION_ONLY // segue gravando se já estava
        TouchSignal.DRAG_END -> GestureIntent.NONE
        TouchSignal.TRIPLE_TAP -> GestureIntent.KEYBOARD_PICKER
        TouchSignal.DOWN -> when {
            // Tap-rápido dentro da janela pós-release → pausa em vez de parar (REC-04).
            isWithinReleaseWindow() -> { pendingReleaseAt = null; pause() }
            machine.state.isIdle -> start()
            machine.state == ButtonState.PAUSED -> resume() // segurar de novo retoma (Yan 2026-06-27)
            else -> GestureIntent.NONE
        }
        TouchSignal.UP -> {
            if (machine.state == ButtonState.RECORDING) {
                // Não decide já: abre a janela; Stop só no onReleaseTimeout.
                pendingReleaseAt = clock()
            }
            GestureIntent.NONE
        }
        // Classificados do GestureInterpreter não são usados no PTT (gating é por DOWN/UP).
        TouchSignal.TAP, TouchSignal.HOLD -> GestureIntent.NONE
    }

    private fun isWithinReleaseWindow(): Boolean {
        val started = pendingReleaseAt ?: return false
        return clock() - started <= pttPauseWindowMs
    }

    private fun start(): GestureIntent {
        machine.onStart()
        return GestureIntent.START
    }

    private fun stop(): GestureIntent {
        machine.onStop()
        return GestureIntent.STOP
    }

    private fun pause(): GestureIntent {
        machine.onPause()
        return GestureIntent.PAUSE
    }

    private fun resume(): GestureIntent {
        machine.onResume()
        return GestureIntent.RESUME
    }

    private companion object {
        const val DEFAULT_PTT_PAUSE_WINDOW_MS = 350L
    }
}

private val ButtonState.isIdle: Boolean
    get() = this == ButtonState.IDLE_READY || this == ButtonState.IDLE_NO_FIELD
