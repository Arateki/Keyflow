package com.keyflow.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** O cache LRU dos sprites das teclas: reaproveita o que já tem e joga fora o menos usado. */
class LruMapTest {

    @Test
    fun `an existing key is reused and not created again`() {
        val cache = LruMap<String, Int>(3)
        var created = 0
        repeat(5) { cache.getOrPut("a") { created++; 1 } }
        assertThat(created).isEqualTo(1)
        assertThat(cache.size).isEqualTo(1)
    }

    @Test
    fun `beyond the limit the least recently used key goes away`() {
        val cache = LruMap<String, Int>(2)
        cache.getOrPut("a") { 1 }
        cache.getOrPut("b") { 2 }
        cache.getOrPut("a") { 1 } // renova o "a": agora o menos usado é o "b"
        cache.getOrPut("c") { 3 }
        assertThat(cache.contains("a")).isTrue()
        assertThat(cache.contains("b")).isFalse()
        assertThat(cache.contains("c")).isTrue()
        assertThat(cache.size).isEqualTo(2)
    }
}
