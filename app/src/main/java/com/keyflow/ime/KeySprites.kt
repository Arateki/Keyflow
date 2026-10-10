package com.keyflow.ime

import android.graphics.Bitmap

/**
 * Cache LRU simples: guarda até [maxEntries] valores por chave e joga fora o menos usado. Sem Android — o teste de
 * unidade cobre a regra ([SpriteCache] só a especializa para bitmaps de tecla).
 */
internal class LruMap<K, V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    val size: Int get() = map.size

    /** O valor da [key]; se não há, cria com [create] e guarda. Acessar renova a chave. */
    fun getOrPut(key: K, create: () -> V): V = map.getOrPut(key, create)

    fun contains(key: K): Boolean = map.containsKey(key)
}

/**
 * Os "sprites" das teclas de uma skin com superfície própria: a face de uma tecla — sombra, vidro, brilho, aro — é
 * desenhada **uma vez** num bitmap e depois só copiada a cada quadro. Assim a skin pode ter muito detalhe (degradês,
 * desfoques, brilhos) sem custo por tecla no `onDraw`. As teclas de um teclado repetem poucos tamanhos (letra,
 * shift, espaço, enter), então o cache é pequeno e quase sempre acerta.
 */
internal class SpriteCache(maxEntries: Int = 40) {

    /**
     * O que define a aparência de um sprite: tamanho, raio dos cantos, cor e se está pressionada. [variant] separa sprites que o resto da
     * chave não distingue (a skin de pacote guarda aqui o papel da tecla: letra ou função); as skins de hoje não usam e ficam em 0.
     */
    data class Key(val width: Int, val height: Int, val radius: Int, val tint: Int, val pressed: Boolean, val variant: Int = 0)

    private val cache = LruMap<Key, Bitmap>(maxEntries)

    val size: Int get() = cache.size

    /** O sprite de [key], desenhado por [render] (que recebe o bitmap vazio) só na primeira vez. */
    fun get(key: Key, pad: Int, render: (Bitmap) -> Unit): Bitmap = cache.getOrPut(key) {
        Bitmap.createBitmap(key.width + 2 * pad, key.height + 2 * pad, Bitmap.Config.ARGB_8888).also(render)
    }
}
