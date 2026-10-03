package com.keyflow.skins

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O teto de bytes do cache de frames da orbe (SKP-ORB-05, ~16 MB). */
class ByteBudgetLruTest {

    private fun cache(max: Long) = ByteBudgetLru<String, ByteArray>(max) { it.size }

    private fun cache(max: Long, victim: (Iterable<String>, String) -> String?) = ByteBudgetLru<String, ByteArray>(max, victim) { it.size }

    @Test
    fun `it keeps what fits and evicts the least recently used first`() {
        val c = cache(100)
        c.put("a", ByteArray(40))
        c.put("b", ByteArray(40))
        c.get("a") // renova: agora o menos usado é o b
        c.put("c", ByteArray(40))
        assertThat(c.contains("a")).isTrue()
        assertThat(c.contains("b")).isFalse()
        assertThat(c.contains("c")).isTrue()
        assertThat(c.bytes).isEqualTo(80)
        assertThat(c.size).isEqualTo(2)
    }

    @Test
    fun `the total never goes over the budget while there is something else to evict`() {
        val c = cache(1000)
        repeat(500) { c.put("k$it", ByteArray(37)) }
        assertThat(c.bytes).isAtMost(1000)
        assertThat(c.contains("k499")).isTrue()
        assertThat(c.contains("k0")).isFalse()
    }

    @Test
    fun `replacing a key does not double count`() {
        val c = cache(100)
        c.put("a", ByteArray(60))
        c.put("a", ByteArray(30))
        assertThat(c.bytes).isEqualTo(30)
        assertThat(c.size).isEqualTo(1)
    }

    @Test
    fun `a value bigger than the whole budget still enters and is the first to go`() {
        val c = cache(100)
        c.put("big", ByteArray(500))
        assertThat(c.get("big")).isNotNull()
        c.put("small", ByteArray(10))
        assertThat(c.contains("big")).isFalse()
        assertThat(c.contains("small")).isTrue()
        assertThat(c.bytes).isEqualTo(10)
    }

    @Test
    fun `removeWhere drops matching keys and their bytes`() {
        val c = cache(1000)
        c.put("x1", ByteArray(10))
        c.put("y1", ByteArray(20))
        c.put("x2", ByteArray(30))
        c.removeWhere { it.startsWith("x") }
        assertThat(c.size).isEqualTo(1)
        assertThat(c.bytes).isEqualTo(20)
        c.clear()
        assertThat(c.bytes).isEqualTo(0)
        assertThat(c.size).isEqualTo(0)
    }

    @Test
    fun `it is safe to use from two threads`() {
        val c = cache(10_000)
        val threads = (0 until 4).map { t ->
            Thread { repeat(2000) { i -> c.put("t$t-${i % 50}", ByteArray(100)); c.get("t$t-${(i + 7) % 50}") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertThat(c.bytes).isAtMost(10_000)
        var counted = 0L
        for (t in 0 until 4) for (i in 0 until 50) if (c.contains("t$t-$i")) counted += 100
        assertThat(counted).isEqualTo(c.bytes)
    }

    @Test
    fun `a custom victim chooser replaces the least recently used one`() {
        // "Mais à frente na volta" sai primeiro: aqui, a chave de maior índice numérico — o contrário do LRU.
        val c = cache(100) { keys, justInserted -> keys.filter { it != justInserted }.maxByOrNull { it.drop(1).toInt() } }
        c.put("f1", ByteArray(40))
        c.put("f2", ByteArray(40))
        c.put("f3", ByteArray(40)) // estoura: sairia f1 pelo LRU; pelo chooser sai f2 (o maior índice entre os que NÃO acabaram de entrar)
        assertThat(c.contains("f1")).isTrue()
        assertThat(c.contains("f2")).isFalse()
        assertThat(c.contains("f3")).isTrue()
        assertThat(c.bytes).isEqualTo(80)
    }

    @Test
    fun `the chooser can never evict the entry that just came in, and a bad answer falls back to LRU`() {
        val greedy = cache(100) { _, justInserted -> justInserted } // tenta despejar o recém-chegado
        greedy.put("a", ByteArray(60))
        greedy.put("b", ByteArray(60))
        assertThat(greedy.contains("b")).isTrue()
        assertThat(greedy.contains("a")).isFalse()
        val absent = cache(100) { _, _ -> "nao-existe" }
        absent.put("a", ByteArray(60))
        absent.put("b", ByteArray(60))
        assertThat(absent.contains("a")).isFalse()
        assertThat(absent.bytes).isEqualTo(60)
    }

    @Test
    fun `looping over more frames than fit keeps most of them with a cyclic aware chooser but none with plain LRU`() {
        val frames = 10
        val capacity = 7
        fun run(c: ByteBudgetLru<Int, ByteArray>, cursorOf: () -> Int): Int {
            var misses = 0
            repeat(frames * 6) { step ->
                val frame = step % frames
                if (c.get(frame) == null) {
                    misses++
                    c.put(frame, ByteArray(10))
                }
            }
            return misses
        }
        var cursor = 0
        // LRU puro: a volta sequencial erra SEMPRE depois de encher (cada frame sai antes de ser reusado).
        val lru = ByteBudgetLru<Int, ByteArray>(capacity * 10L) { it.size }
        val lruMisses = run(lru) { cursor }
        assertThat(lruMisses).isEqualTo(frames * 6)
        // Consciente da volta: sai o frame mais longe À FRENTE do cursor (o que acabou de passar), então os outros ficam.
        val cyclic = ByteBudgetLru<Int, ByteArray>(capacity * 10L, { keys, justInserted -> keys.filter { it != justInserted }.maxByOrNull { Math.floorMod(it - cursor, frames) } }) { it.size }
        var misses = 0
        repeat(frames * 6) { step ->
            cursor = step % frames
            if (cyclic.get(cursor) == null) {
                misses++
                cyclic.put(cursor, ByteArray(10))
            }
        }
        // 10 frames em 7 lugares: a cada volta só os 3 de fora são renderizados (e a 1ª volta enche) — bem menos que 60.
        assertThat(misses).isAtMost(10 + 5 * 4)
    }

    // ── o teto que muda (orçamento adaptativo da orbe, SKP-ORB-06) ───────────

    @Test
    fun `the budget starts as the constructor says and only setMaxBytes changes it`() {
        val c = cache(100)
        assertThat(c.maxBytes).isEqualTo(100)
        c.put("a", ByteArray(60))
        c.put("b", ByteArray(60))
        assertThat(c.maxBytes).isEqualTo(100)
        assertThat(c.bytes).isEqualTo(60)
    }

    @Test
    fun `a bigger budget keeps what a smaller one would have evicted`() {
        val c = cache(100)
        c.setMaxBytes(300)
        repeat(5) { c.put("k$it", ByteArray(50)) }
        assertThat(c.bytes).isEqualTo(250)
        assertThat(c.size).isEqualTo(5)
        assertThat(c.maxBytes).isEqualTo(300)
    }

    @Test
    fun `shrinking the budget evicts the excess right away, least recently used first by default`() {
        val c = cache(300)
        c.put("a", ByteArray(100))
        c.put("b", ByteArray(100))
        c.put("c", ByteArray(100))
        c.get("a") // o menos usado agora é o b
        c.setMaxBytes(200)
        assertThat(c.bytes).isEqualTo(200)
        assertThat(c.contains("b")).isFalse()
        assertThat(c.contains("a") && c.contains("c")).isTrue()
    }

    @Test
    fun `shrinking asks the chooser who goes and falls back to the least recently used on a bad answer`() {
        val c = cache(300)
        c.put("a", ByteArray(100))
        c.put("b", ByteArray(100))
        c.put("c", ByteArray(100))
        c.setMaxBytes(200) { keys -> keys.first { it == "c" } }
        assertThat(c.contains("c")).isFalse()
        assertThat(c.bytes).isEqualTo(200)
        c.setMaxBytes(100) { "não-existe" }
        assertThat(c.contains("a")).isFalse() // o menos usado
        assertThat(c.contains("b")).isTrue()
        assertThat(c.bytes).isEqualTo(100)
    }

    @Test
    fun `shrinking below one entry empties it and growing back works`() {
        val c = cache(300)
        c.put("a", ByteArray(100))
        c.setMaxBytes(10)
        assertThat(c.size).isEqualTo(0)
        assertThat(c.bytes).isEqualTo(0)
        c.setMaxBytes(300)
        c.put("a", ByteArray(100))
        assertThat(c.bytes).isEqualTo(100)
    }

    @Test
    fun `a budget that never changes behaves exactly like before the setter existed`() {
        // O mesmo laço de acessos num cache sem `setMaxBytes` e noutro em que o setter foi chamado com o MESMO valor: a mesma ordem de despejo.
        fun run(c: ByteBudgetLru<String, ByteArray>): List<String> {
            repeat(200) { i -> if (i % 3 == 0) c.get("k${i / 2}"); c.put("k$i", ByteArray(37)) }
            return (0 until 200).filter { c.contains("k$it") }.map { "k$it" }
        }
        val plain = run(cache(1000))
        val same = run(cache(1000).also { it.setMaxBytes(1000) })
        assertThat(same).isEqualTo(plain)
    }
}
