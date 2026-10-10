package com.keyflow.skins

/**
 * A política de memória do cache de bitmaps da orbe de pacote (SKP-ORB-06). Pura (JVM): as contas do orçamento, sem Android — quem pergunta ao
 * aparelho quanto ele aguenta é [OrbDeviceMemory], e quem usa o orçamento é o [PackOrb].
 *
 * Um frame custa `lado² × 4` bytes (ARGB_8888), então o que um estado ocupa é `lado² × 4 × nFrames`:
 *
 * | lado    | 1 frame  | 120 frames | 240 frames |
 * | ------- | -------- | ---------- | ---------- |
 * | 110 px  | 47 KB    | 5,5 MB     | 11,1 MB    |
 * | 156 px  | 95 KB    | 11,1 MB    | 22,3 MB    |
 * | 195 px  | 149 KB   | 17,4 MB    | 34,8 MB    |
 * | 280 px  | 306 KB   | 35,9 MB    | 71,8 MB    |
 *
 * O orçamento sempre foi **16 MB** ([BASE_BYTES]) e continua sendo para todo pacote que cabe nos limites de antes (fps ≤ 60 e até 64 frames
 * por estado) e para todo pacote novo cujo estado cabe nele. Só quem PEDE mais — o estado que toca, no lado atual, passa de 16 MB — ganha
 * mais, e só até o menor de dois tetos: o absoluto ([ABSOLUTE_MAX_BYTES]) e a fração do que o aparelho aguenta ([deviceCeiling]).
 */
object OrbMemoryPolicy {

    /** O orçamento de sempre: 16 MB. Nenhum pacote dentro dos limites antigos sai dele, e nenhum aparelho fica abaixo dele. */
    const val BASE_BYTES = 16L * 1024 * 1024

    /**
     * O teto absoluto, em qualquer aparelho: 72 MB = `280² × 4 × 240` (71,8 MB), o maior estado que o formato aceita ([SkinPackReader.MAX_FRAMES_PER_STATE])
     * no maior lado do slider ([MAX_SIDE_PX]). Um estado do tamanho máximo cabe inteiro num aparelho folgado, e nenhum pacote válido pede mais que isto.
     */
    const val ABSOLUTE_MAX_BYTES = 72L * 1024 * 1024

    /** O maior lado da orbe, em px: o máximo do slider da aba Sobrepor (`valueRange = 110f..280f`). É com ele que o import conta a memória de um estado. */
    const val MAX_SIDE_PX = 280

    /** Bytes por pixel do bitmap de um frame (ARGB_8888). */
    const val BYTES_PER_PIXEL = 4

    /**
     * Que parte da classe de memória do aparelho ([android.app.ActivityManager.getMemoryClass], o limite "de bom comportamento" por app, em MB) o
     * cache da orbe pode usar: 1/4. Com 256 MB de classe (o A32) são 64 MB; com 192 (o emulador), 48 MB; 512 MB ou mais de classe, só o teto absoluto.
     * É mais folgado que o 1/8 clássico das receitas de cache de imagem porque o orçamento só cresce quando o pacote pede, e volta aos 16 MB ao sair do estado.
     */
    const val MEMORY_CLASS_DIVISOR = 4L

    /** Os bytes que um estado de [frames] frames ocupa numa orbe de [sidePx] px de lado. */
    fun stateBytes(sidePx: Int, frames: Int): Long = sidePx.toLong() * sidePx * BYTES_PER_PIXEL * frames

    /**
     * Até onde o orçamento pode crescer neste aparelho: `memoryClass / 4` entre [BASE_BYTES] e [ABSOLUTE_MAX_BYTES]. Aparelho de pouca RAM
     * ([lowRam], `ActivityManager.isLowRamDevice`) não cresce: fica nos 16 MB de sempre.
     */
    fun deviceCeiling(memoryClassMb: Int, lowRam: Boolean): Long {
        if (lowRam) return BASE_BYTES
        val fraction = memoryClassMb.coerceAtLeast(0) * 1024L * 1024L / MEMORY_CLASS_DIVISOR
        return fraction.coerceIn(BASE_BYTES, ABSOLUTE_MAX_BYTES)
    }

    /**
     * O orçamento para um estado que precisa de [demandBytes]: os 16 MB de sempre se ele cabe neles; senão o que ele pede, até o [ceilingBytes] do
     * aparelho (nunca abaixo dos 16 MB, nunca acima do teto absoluto). Se nem assim cabe, a degradação é a de sempre: o despejo escolhe pela posição
     * da animação e o frame anterior continua na tela.
     */
    fun budgetFor(demandBytes: Long, ceilingBytes: Long): Long {
        if (demandBytes <= BASE_BYTES) return BASE_BYTES
        return minOf(demandBytes, ceilingBytes.coerceIn(BASE_BYTES, ABSOLUTE_MAX_BYTES))
    }

    /** Bytes em MB inteiros, arredondados PARA CIMA (o `~N MB` do relatório do import): o que passa de 16 MB nunca aparece como "~16 MB". */
    fun megabytes(bytes: Long): Long = (bytes + 1024L * 1024 - 1) / (1024L * 1024)
}
