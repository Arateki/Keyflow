package com.keyflow.computador.chaves

import com.keyflow.computador.ImpressaoDigital
import org.json.JSONArray
import org.json.JSONObject

/**
 * `computer_keys_json` (`{"v":1,"keys":[…]}`): só **metadados** (id, nome, tipo, impressão, proteção, biometria, data). Sem material
 * secreto. Tolerante como o `CodecHosts`: nunca lança, item ruim é descartado, id repetido vira um.
 */
object CodecChaves {
    fun codificar(chaves: List<ChaveSsh>): String {
        val a = JSONArray()
        for (c in chaves) a.put(
            JSONObject().put("id", c.id).put("name", c.nome).put("type", c.tipo.name).put("fp", c.impressao.texto)
                .put("protection", c.protecao.name).put("biometric", c.exigeBiometria).put("created", c.criadaEmMs),
        )
        return JSONObject().put("v", 1).put("keys", a).toString()
    }

    fun decodificar(json: String?): List<ChaveSsh> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val itens = JSONObject(json).optJSONArray("keys") ?: return emptyList()
            val out = mutableListOf<ChaveSsh>()
            for (i in 0 until itens.length()) {
                val o = itens.optJSONObject(i) ?: continue
                val id = (o.opt("id") as? String)?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: continue
                if (out.any { it.id == id }) continue
                val nome = (o.opt("name") as? String)?.take(80) ?: continue
                val tipo = TipoDeChave.entries.firstOrNull { it.name == o.opt("type") } ?: continue
                val impressao = (o.opt("fp") as? String)?.let { ImpressaoDigital.deTexto(it) } ?: continue
                val protecao = Protecao.entries.firstOrNull { it.name == o.opt("protection") } ?: continue
                out += ChaveSsh(id, nome, tipo, impressao, protecao, (o.opt("biometric") as? Boolean) ?: false, (o.opt("created") as? Number)?.toLong() ?: 0L)
            }
            out
        } catch (e: Exception) {
            emptyList()
        } catch (e: StackOverflowError) {
            emptyList()
        }
    }
}
