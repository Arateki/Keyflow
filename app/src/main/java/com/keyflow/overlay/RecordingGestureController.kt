package com.keyflow.overlay

import com.keyflow.core.ButtonState
import com.keyflow.core.RecordingMode

/** Intenção de alto nível derivada de um gesto + modo (REC-03/04, OVL-03/04). */
enum class GestureIntent { START, STOP, PAUSE, RESUME, CANCEL_COMMIT, REPOSITION_ONLY, KEYBOARD_PICKER, NONE }

/**
 * Sinais de toque consumidos pelo [RecordingGestureController].
 *
 * - `DOWN`/`UP` (crus) só importam no PUSH_TO_TALK, onde a gravação é gated pela pressão.
 * - `TAP`/`HOLD`/`TRIPLE_TAP`/`DRAG_*` vêm classificados do [GestureInterpreter] (modo TOGGLE).
 */
enum class TouchSignal { DOWN, UP, TAP, HOLD, TRIPLE_TAP, DRAG_START, DRAG_END }

/**
 * Mapeia eventos de toque + modo de gravação para [GestureIntent].
 *
 * **Puramente decisório**: lê o estado atual via [currentState] (o `OverlayCoordinator` é o dono da
 * única [ButtonStateMachine]) e devolve a intenção; o consumidor aplica a transição. Assim não há
 * duas máquinas para sincronizar quando a transcrição termina de forma assíncrona.
 *
 * O único estado interno é do ciclo de gesto (janela `Wptt` do PTT e a marca de arrasto) — coberto
 * por unit com `clock` injetável.
 */
class RecordingGestureController(
    private val mode: () -> RecordingMode,
    private val currentState: () -> ButtonState,
    private val pttPauseWindowMs: Long = DEFAULT_PTT_PAUSE_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Início do release PTT pendente (RECORDING aguardando Stop ou tap-rápido→Pause). */
    private var pendingReleaseAt: Long? = null

    /** Marca um ciclo de arrasto em curso p/ suprimir TAP/HOLD acidental (anti-movimento). */
    private var draggingThisCycle = false

    fun onSignal(signal: TouchSignal): GestureIntent = when (mode()) {
        RecordingMode.TOGGLE -> onToggle(signal)
        RecordingMode.PUSH_TO_TALK -> onPushToTalk(signal)
    }

    /**
     * Decorrida a janela `Wptt` após um release PTT sem tap-rápido: encerra de fato (→ STOP).
     * No-op se a janela ainda não passou, se já foi consumida (Pause) ou se não há release pendente.
     */
    fun onReleaseTimeout(): GestureIntent {
        val started = pendingReleaseAt ?: return GestureIntent.NONE
        if (clock() - started < pttPauseWindowMs) return GestureIntent.NONE
        pendingReleaseAt = null
        return if (currentState() == ButtonState.RECORDING) GestureIntent.STOP else GestureIntent.NONE
    }

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
            currentState().isIdle -> GestureIntent.START
            currentState().isRecordingOrPaused -> GestureIntent.STOP
            // Toque durante o cancelamento conclui na hora (descarta), sem esperar a janela de desfazer.
            currentState() == ButtonState.CANCELLING -> GestureIntent.CANCEL_COMMIT
            else -> GestureIntent.NONE
        }
        TouchSignal.HOLD -> when {
            draggingThisCycle -> GestureIntent.NONE
            currentState() == ButtonState.RECORDING -> GestureIntent.PAUSE
            currentState() == ButtonState.PAUSED -> GestureIntent.RESUME
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
            isWithinReleaseWindow() -> { pendingReleaseAt = null; GestureIntent.PAUSE }
            currentState().isIdle -> GestureIntent.START
            currentState() == ButtonState.PAUSED -> GestureIntent.RESUME // segurar de novo retoma (Yan)
            else -> GestureIntent.NONE
        }
        TouchSignal.UP -> {
            // Não decide já: abre a janela; Stop só no onReleaseTimeout.
            if (currentState() == ButtonState.RECORDING) pendingReleaseAt = clock()
            GestureIntent.NONE
        }
        // Toque durante o cancelamento conclui na hora, igual ao TOGGLE.
        TouchSignal.TAP -> if (currentState() == ButtonState.CANCELLING) GestureIntent.CANCEL_COMMIT else GestureIntent.NONE
        // Demais classificados do GestureInterpreter não são usados no PTT (gating é por DOWN/UP).
        TouchSignal.HOLD -> GestureIntent.NONE
    }

    private fun isWithinReleaseWindow(): Boolean {
        val started = pendingReleaseAt ?: return false
        return currentState() == ButtonState.RECORDING && clock() - started <= pttPauseWindowMs
    }

    private companion object {
        const val DEFAULT_PTT_PAUSE_WINDOW_MS = 350L
    }
}

private val ButtonState.isIdle: Boolean
    get() = this == ButtonState.IDLE_READY || this == ButtonState.IDLE_NO_FIELD

private val ButtonState.isRecordingOrPaused: Boolean
    get() = this == ButtonState.RECORDING || this == ButtonState.PAUSED
