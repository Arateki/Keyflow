package com.keyflow.skins

import kotlin.math.floor

/** O tempo da orbe de pacote. Puro (JVM): o relógio e o desenho ficam de fora, só a conta do frame. */
object OrbTiming {

    /**
     * Qual frame de [frameCount] está no ar em [timeMs], a [fps] quadros por segundo, em loop: `floor(tempoMs · fps / 1000) mod n` (SKP-ORB-01).
     * Determinística — o mesmo tempo dá sempre o mesmo frame (é o que fixa os goldens com o relógio congelado). Um tempo negativo conta
     * "para trás" sem sair do intervalo; um erro de arredondamento do `Float` exatamente numa fronteira (83,333… ms a 12 fps) não adianta
     * nem atrasa o frame: a conta soma uma folga de 1e-4 quadro (a 60 fps, 1,7 µs — invisível).
     */
    fun frameIndex(timeMs: Float, fps: Int, frameCount: Int): Int {
        if (frameCount <= 1 || fps <= 0) return 0
        val frames = floor(timeMs.toDouble() * fps / 1000.0 + EPSILON).toLong()
        return Math.floorMod(frames, frameCount.toLong()).toInt()
    }

    private const val EPSILON = 1e-4
}
