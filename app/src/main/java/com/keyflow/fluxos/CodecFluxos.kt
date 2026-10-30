package com.keyflow.fluxos

import com.keyflow.orbe.LugarDaOrbe
import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec JSON dos fluxos e dos gatilhos para o DataStore (`flows_json` e `flow_triggers_json`, esquema `v:1` da spec). Usa
 * `org.json` (presente no Android; os testes de unidade da JVM adicionam o jar `org.json:json`) — zero dependência nova.
 *
 * **Tolerante por construção (FLX-47)** — o app nunca cai por causa de dado ruim:
 * - chave ausente, em branco ou JSON malformado → [decodificarFluxos] devolve `null` e [decodificarConfig] usa a semente
 *   (os fluxos padrão); lista presente e **vazia** é vazia de verdade (a pessoa excluiu tudo);
 * - fluxo malformado (sem `id` ou `name`, ou com `steps` que não é lista) é descartado e os outros ficam; etapa malformada
 *   (não é objeto, sem `type`) é descartada; `type` desconhecido vira [Etapa.Desconhecida] (JSON de versão mais nova);
 * - campo desconhecido é ignorado, em todos os níveis — e por isso nada que alguém escreva à mão (chave, token, endpoint…)
 *   chega a um [Fluxo]: só existem os campos do esquema (FLX-46);
 * - nome (40), instrução e sistema (500), etapas (8), fluxos (20) e gatilhos (64) são cortados ([LIMITES]); id de fluxo e
 *   superfície repetidos ficam com o primeiro (D7);
 * - nunca lança.
 *
 * **Versão mais nova**: um JSON com `v` > 1 (campos e etapas que esta versão não conhece) é lido em modo tolerante — o que se
 * conhece fica, o resto é ignorado —, mas **regravar o perde**: sai como `v:1` e a [Etapa.Desconhecida] guarda só o tipo, sem o
 * conteúdo (FLX-47; coberto por `CodecFluxosTest`). É o preço de não travar nem recusar o arquivo.
 *
 * Os campos de texto só valem se forem de fato `String` no JSON: `optString` diverge entre o `org.json` do Android (`null`
 * vira "null") e o do Maven (vira ""), então todo acesso passa por [str].
 *
 * Gatilho de fluxo inexistente **não** é filtrado na leitura (a leitura só observa): quem grava limpa os órfãos (FLX-50).
 */
object CodecFluxos {

    /** Versão do esquema (`"v"`); a leitura não a exige: ignora o que não conhece. */
    const val VERSAO = 1

    // ── fluxos ───────────────────────────────────────────────────────────────

    fun codificarFluxos(fluxos: List<Fluxo>): String = JSONObject().apply {
        put("v", VERSAO)
        put("flows", JSONArray().apply { fluxos.forEach { put(fluxoJson(it)) } })
    }.toString()

    /**
     * Os fluxos gravados em [json]; `null` quando não há o que ler (ausente, em branco ou malformado) — quem chama usa então a
     * semente. Lista vazia = a pessoa não tem nenhum fluxo.
     */
    fun decodificarFluxos(json: String?): List<Fluxo>? = tolerante(null) {
        if (json.isNullOrBlank()) return@tolerante null
        val lista = JSONObject(json).opt("flows") as? JSONArray ?: return@tolerante null
        val vistos = HashSet<String>()
        val lidos = ArrayList<Fluxo>()
        for (i in 0 until lista.length()) {
            if (lidos.size >= LIMITES.FLUXOS) break
            val fluxo = fluxoDe(lista.opt(i) as? JSONObject) ?: continue
            if (vistos.add(fluxo.id)) lidos += fluxo
        }
        lidos
    }

    private fun fluxoJson(fluxo: Fluxo): JSONObject = JSONObject().apply {
        put("id", fluxo.id)
        put("name", fluxo.nome)
        fluxo.padrao?.let { put("builtin", it) }
        put("steps", JSONArray().apply { fluxo.etapas.forEach { put(etapaJson(it)) } })
    }

    private fun fluxoDe(obj: JSONObject?): Fluxo? {
        obj ?: return null
        val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val nome = obj.str("name")?.cortar(LIMITES.NOME)?.takeIf { it.isNotBlank() } ?: return null
        val passos = when (val bruto = obj.opt("steps")) {
            null, JSONObject.NULL -> JSONArray() // fluxo novo: sem etapas ainda
            is JSONArray -> bruto
            else -> return null
        }
        val etapas = ArrayList<Etapa>()
        for (i in 0 until passos.length()) {
            if (etapas.size >= LIMITES.ETAPAS) break
            etapaDe(passos.opt(i) as? JSONObject)?.let { etapas += it }
        }
        return Fluxo(id = id, nome = nome, padrao = obj.str("builtin")?.takeIf { it.isNotBlank() }, etapas = etapas)
    }

    // ── etapas ───────────────────────────────────────────────────────────────

    private fun etapaJson(etapa: Etapa): JSONObject = JSONObject().apply {
        put("type", if (etapa is Etapa.Desconhecida) etapa.chave else etapa.tipo.chave)
        when (etapa) {
            is Etapa.Visao -> etapa.instrucao?.takeIf { it.isNotEmpty() }?.let { put("instruction", it) }
            is Etapa.Perguntar -> etapa.sistema?.takeIf { it.isNotEmpty() }?.let { put("system", it) }
            else -> Unit
        }
        etapa.modelo?.let { put("model", modeloJson(it)) }
    }

    private fun etapaDe(obj: JSONObject?): Etapa? {
        obj ?: return null
        val chave = obj.str("type")?.takeIf { it.isNotBlank() } ?: return null
        return when (TipoEtapa.daChave(chave)) {
            TipoEtapa.DITAR -> Etapa.Ditar(modeloDe(obj))
            TipoEtapa.CAPTURAR -> Etapa.Capturar
            TipoEtapa.VISAO -> Etapa.Visao(obj.texto("instruction"), modeloDe(obj))
            TipoEtapa.PERGUNTAR -> Etapa.Perguntar(obj.texto("system"), modeloDe(obj))
            TipoEtapa.FALAR -> Etapa.Falar(modeloDe(obj))
            TipoEtapa.INSERIR -> Etapa.Inserir
            TipoEtapa.AGENTE -> Etapa.Agente(modeloDe(obj))
            TipoEtapa.DESCONHECIDA, null -> Etapa.Desconhecida(chave.cortar(LIMITES.NOME))
        }
    }

    private fun modeloJson(ref: ModeloRef): JSONObject = JSONObject().apply {
        put("source", ref.origem)
        ref.modelo?.let { put("model", it) }
    }

    /** `{source, model}`; sem `source` legível não há modelo (a etapa usa o da aba). `model` vazio = o da aba para a origem. */
    private fun modeloDe(etapa: JSONObject): ModeloRef? {
        val ref = etapa.opt("model") as? JSONObject ?: return null
        val origem = ref.str("source")?.takeIf { it.isNotBlank() } ?: return null
        return ModeloRef(origem, ref.str("model")?.takeIf { it.isNotBlank() })
    }

    // ── gatilhos ─────────────────────────────────────────────────────────────

    fun codificarGatilhos(gatilhos: List<Gatilho>): String = JSONObject().apply {
        put("v", VERSAO)
        put(
            "triggers",
            JSONArray().apply {
                gatilhos.forEach { put(JSONObject().put("surface", superficieJson(it.superficie)).put("flow", it.fluxoId)) }
            },
        )
    }.toString()

    /** Os gatilhos legíveis de [json]; ausente ou malformado = nenhum. Gatilho ilegível é descartado; superfície repetida fica com o primeiro. */
    fun decodificarGatilhos(json: String?): List<Gatilho> = tolerante(emptyList()) {
        if (json.isNullOrBlank()) return@tolerante emptyList()
        val lista = JSONObject(json).opt("triggers") as? JSONArray ?: return@tolerante emptyList()
        val vistas = HashSet<Superficie>()
        val lidos = ArrayList<Gatilho>()
        for (i in 0 until lista.length()) {
            if (lidos.size >= LIMITES.GATILHOS) break
            val obj = lista.opt(i) as? JSONObject ?: continue
            val superficie = obj.str("surface")?.let(::superficieDe) ?: continue
            val fluxoId = obj.str("flow")?.takeIf { it.isNotBlank() } ?: continue
            if (vistas.add(superficie)) lidos += Gatilho(superficie, fluxoId)
        }
        lidos
    }

    /**
     * `orb:<lugar>:<toques>` (lugar em minúsculas: `flutuante`, `teclado`, `notificacao`; toques 2 ou 3) e `bar:<botaoId>`.
     * O rascunho anterior (`orb:2`, `notif:<id>`) nunca foi implementado, então não há migração: é descartado.
     */
    internal fun superficieJson(superficie: Superficie): String = when (superficie) {
        is Superficie.Orbe -> "orb:${nomeDoLugar(superficie.lugar)}:${superficie.toques}"
        is Superficie.Barra -> "bar:${superficie.botaoId}"
    }

    internal fun superficieDe(texto: String): Superficie? {
        if (texto.startsWith("bar:")) {
            return texto.substring("bar:".length).takeIf { it.isNotBlank() }?.let { Superficie.Barra(it) }
        }
        if (!texto.startsWith("orb:")) return null
        val partes = texto.split(':')
        if (partes.size != 3) return null
        val lugar = LugarDaOrbe.entries.firstOrNull { nomeDoLugar(it) == partes[1] } ?: return null
        val toques = partes[2].toIntOrNull()?.takeIf { it in Superficie.Orbe.TOQUES } ?: return null
        return Superficie.Orbe(lugar, toques)
    }

    private fun nomeDoLugar(lugar: LugarDaOrbe): String = when (lugar) {
        LugarDaOrbe.FLUTUANTE -> "flutuante"
        LugarDaOrbe.TECLADO -> "teclado"
        LugarDaOrbe.NOTIFICACAO -> "notificacao"
    }

    // ── a config inteira ─────────────────────────────────────────────────────

    /**
     * Os dois JSON juntos. Sem `flows_json` legível usa a [semente] (os fluxos padrão, em memória — nada é gravado) e ignora os
     * gatilhos, que não teriam a que se ligar (FLX-40). Com ele, os gatilhos são os de [triggersJson].
     */
    fun decodificarConfig(flowsJson: String?, triggersJson: String?, semente: () -> List<Fluxo>): ConfigFluxos {
        val fluxos = decodificarFluxos(flowsJson) ?: return ConfigFluxos(semente(), emptyList())
        return ConfigFluxos(fluxos, decodificarGatilhos(triggersJson))
    }

    // ── apoio ────────────────────────────────────────────────────────────────

    /** O valor de [nome] se for mesmo uma `String` no JSON (número, objeto, `null` e ausente dão `null`). */
    private fun JSONObject.str(nome: String): String? = opt(nome) as? String

    /** Texto livre da etapa: cortado em [LIMITES.TEXTO_DA_ETAPA]; vazio = "sem texto" (nulo). */
    private fun JSONObject.texto(nome: String): String? = str(nome)?.takeIf { it.isNotEmpty() }?.cortar(LIMITES.TEXTO_DA_ETAPA)

    /** Corta em [limite] caracteres sem deixar meio par substituto (emoji) no fim. */
    private fun String.cortar(limite: Int): String {
        if (length <= limite) return this
        val fim = if (this[limite - 1].isHighSurrogate()) limite - 1 else limite
        return substring(0, fim)
    }

    /** Qualquer falha de leitura (JSON malformado, aninhamento que estoura a pilha) cai em [padrao]: o app nunca cai por dado ruim. */
    private inline fun <T> tolerante(padrao: T, bloco: () -> T): T = try {
        bloco()
    } catch (_: Exception) {
        padrao
    } catch (_: StackOverflowError) {
        padrao
    }
}
