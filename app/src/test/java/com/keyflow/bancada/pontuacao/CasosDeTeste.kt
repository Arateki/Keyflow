package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso
import org.json.JSONArray
import org.json.JSONObject

/** Monta um [Caso] à mão para os testes dos pontuadores: `campos` são os extras do pontuador (fatos, esquema, valor…). */
internal fun caso(
    id: String = "t-01",
    idioma: String = "pt",
    entrada: String = "entrada",
    esperado: Any? = null,
    maxTokensSaida: Int = 96,
    campos: Array<out Pair<String, Any?>> = emptyArray(),
): Caso {
    val json = JSONObject().put("id", id).put("idioma", idioma).put("entrada", entrada).put("maxTokensSaida", maxTokensSaida).put("tokensEntrada", 10)
    json.put("esperado", esperado ?: JSONObject.NULL)
    for ((chave, valor) in campos) json.put(chave, valor ?: JSONObject.NULL)
    return Caso(id, idioma, entrada, maxTokensSaida, 10, json)
}

internal fun lista(vararg itens: String): JSONArray = JSONArray(itens.toList())

internal fun obj(texto: String): JSONObject = JSONObject(texto)
