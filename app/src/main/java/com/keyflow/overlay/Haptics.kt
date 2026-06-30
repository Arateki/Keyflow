package com.keyflow.overlay

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * Retorno tátil configurável da orbe (e preview do slider na config). Intensidade 0..100.
 *
 * A intensidade vira **amplitude** (com piso, onde o aparelho suporta `hasAmplitudeControl`) **E**
 * duração — assim os níveis se distinguem mesmo em aparelhos sem controle de amplitude (ex.: muitos
 * Samsung), em que a amplitude é ignorada e só a duração muda a percepção.
 */
object Haptics {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    /** Pulso único. [minMs]/[maxMs] limitam a duração interpolada pela intensidade. 0 = nada. */
    fun tick(context: Context, intensityPercent: Int, minMs: Long = 22L, maxMs: Long = 75L) {
        val intensity = intensityPercent.coerceIn(0, 100)
        if (intensity <= 0) return
        val vib = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        val frac = intensity / 100f
        val ms = (minMs + frac * (maxMs - minMs)).toLong().coerceAtLeast(1L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = if (vib.hasAmplitudeControl()) {
                (60 + frac * 195).roundToInt().coerceIn(1, 255) // piso ~60 p/ não ficar imperceptível
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vib.vibrate(VibrationEffect.createOneShot(ms, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(ms)
        }
    }
}
