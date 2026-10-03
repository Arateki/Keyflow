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

    /**
     * Os frames que a tela vai MOSTRAR nos próximos [windowFrames] frames de animação, a partir de [timeMs], quando ela atualiza a [refreshHz]: um
     * desenho a cada `1000 / refreshHz` ms, cada um com o frame de [frameIndex] no seu instante — em ordem e sem repetir, sem o frame de agora.
     * Numa tela mais lenta que a animação (120 fps a 60 Hz: um frame sim, outro não; a 90 Hz: três de cada quatro) os que a tela não mostra ficam
     * de fora, e é isso que a thread de fundo deixa de renderizar. Com `refreshHz` desconhecido (≤ 0) ou que dá conta de todos os frames
     * (≥ [fps]) devolve todos os [windowFrames] seguintes, como sempre.
     */
    fun framesShownAhead(timeMs: Float, fps: Int, frameCount: Int, refreshHz: Float, windowFrames: Int): List<Int> {
        if (frameCount <= 1 || windowFrames <= 0) return emptyList()
        val current = frameIndex(timeMs, fps, frameCount)
        if (refreshHz <= 0f || refreshHz >= fps) return (1..windowFrames).map { (current + it) % frameCount }
        val drawPeriodMs = 1000f / refreshHz
        val draws = (windowFrames * 1000f / fps / drawPeriodMs).toInt()
        val shown = LinkedHashSet<Int>()
        for (draw in 1..draws) {
            val index = frameIndex(timeMs + draw * drawPeriodMs, fps, frameCount)
            if (index != current) shown += index
        }
        return shown.toList()
    }

    private const val EPSILON = 1e-4
}
