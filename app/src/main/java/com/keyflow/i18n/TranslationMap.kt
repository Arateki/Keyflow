package com.keyflow.i18n

/** Junta os mapas de cada funcionalidade. Chave repetida é erro de montagem, não uma tradução a mais. */
internal fun translationMap(vararg parts: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>(parts.sumOf { it.size })
    for (part in parts) {
        for ((key, value) in part) {
            require(out.put(key, value) == null) { "chave de tradução repetida: $key" }
        }
    }
    return out
}
