package com.keyflow.ui

/**
 * Distingue retornos reais ao app dos callbacks espúrios que o Android pode disparar logo após abrir
 * uma tela do sistema. O overlay só deve processar um retorno por evento; já o teclado precisa poder
 * continuar em `onWindowFocusChanged`, mesmo quando `onResume` processou o mesmo retorno primeiro.
 */
internal class ForegroundReturnGate(private val debounceMs: Long) {
    private var lastExternalSurfaceOpenedAtMs: Long? = null
    private var lastForegroundProcessedAtMs: Long? = null

    fun markExternalSurfaceOpened(nowMs: Long) {
        lastExternalSurfaceOpenedAtMs = nowMs
    }

    fun shouldProcessForegroundReturn(nowMs: Long): Boolean {
        if (isImmediatelyAfterExternalSurfaceOpened(nowMs)) return false
        val lastProcessed = lastForegroundProcessedAtMs
        if (lastProcessed != null && nowMs - lastProcessed < debounceMs) return false
        lastForegroundProcessedAtMs = nowMs
        return true
    }

    fun shouldRunFocusSensitiveContinuation(nowMs: Long): Boolean =
        !isImmediatelyAfterExternalSurfaceOpened(nowMs)

    private fun isImmediatelyAfterExternalSurfaceOpened(nowMs: Long): Boolean =
        lastExternalSurfaceOpenedAtMs?.let { nowMs - it < debounceMs } == true
}
