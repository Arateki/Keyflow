package com.keyflow.ime.keyboard.layout

/**
 * Aceleração do repeat-on-hold (RND-04, hoje só o backspace): começa devagar e acelera até um piso,
 * como o repeat nativo de teclados. Pura — a `KeyboardGridView` só encadeia `Handler.postDelayed` com
 * este valor a cada tique.
 */
object RepeatSchedule {
    const val MIN_DELAY_MS = 40L
    private const val INITIAL_DELAY_MS = 400L
    private const val STEP_MS = 40L

    fun delayForTick(tick: Int): Long = (INITIAL_DELAY_MS - tick * STEP_MS).coerceAtLeast(MIN_DELAY_MS)
}
