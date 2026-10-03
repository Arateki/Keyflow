package com.keyflow.skins

/**
 * Cache LRU com **teto de bytes** (e não de entradas): guarda o que couber em [maxBytes] e joga fora o menos usado. É o que segura os
 * bitmaps dos frames da orbe de pacote (SKP-ORB-05, ~16 MB): um frame custa lado²×4 bytes, então o número de frames que cabem depende
 * do tamanho da orbe. Sem Android (o tamanho de cada valor vem de [sizeOf]); seguro entre threads — o desenho (main) e o renderizador
 * de fundo o dividem.
 *
 * Um valor maior que o teto inteiro ainda entra (a orbe precisa do frame que está desenhando) e é o primeiro a sair.
 *
 * [chooseVictim] troca o "menos usado" por outra escolha quando o dono do cache sabe mais: ela recebe as chaves (da menos para a mais usada)
 * e a que acabou de entrar (intocável) e devolve quem sai, ou `null` para ficar com o LRU. A orbe usa isto porque uma animação em loop é
 * o pior caso do LRU: com mais frames do que cabem, o menos usado é sempre exatamente o próximo a ser mostrado, e o cache erra sempre.
 */
internal class ByteBudgetLru<K, V>(
    private val maxBytes: Long,
    private val chooseVictim: ((keys: Iterable<K>, justInserted: K) -> K?)? = null,
    private val sizeOf: (V) -> Int,
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var total = 0L

    /** Bytes guardados agora. */
    @get:Synchronized
    val bytes: Long get() = total

    @get:Synchronized
    val size: Int get() = map.size

    /** O valor de [key] (e a renova como "usada agora"), ou `null`. */
    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun contains(key: K): Boolean = map.containsKey(key)

    @Synchronized
    fun put(key: K, value: V) {
        map.put(key, value)?.let { total -= sizeOf(it) }
        total += sizeOf(value)
        while (total > maxBytes && map.size > 1) {
            // O que acabou de entrar é o último da ordem de acesso e nunca é o despejado enquanto houver outro.
            val victim = chooseVictim?.invoke(map.keys, key)?.takeIf { it != key && map.containsKey(it) } ?: map.keys.first { it != key }
            map.remove(victim)?.let { total -= sizeOf(it) }
        }
    }

    /** Tira tudo o que [predicate] aceita (a orbe de um pacote que foi fechado). */
    @Synchronized
    fun removeWhere(predicate: (K) -> Boolean) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                total -= sizeOf(entry.value)
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        total = 0L
    }
}
