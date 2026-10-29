package com.keyflow.bancada.resultado

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** O JSON não é um `bancada-resultado/1` legível. */
class ExecucaoInvalidaException(message: String) : Exception(message)

/**
 * O esquema `bancada-resultado/1` (BHS-01/04): a execução em JSON, ida e volta sem perda. É o formato do arquivo do histórico, da
 * exportação pelo seletor de documentos e do resumo de `verification/bancada/`. O texto livre (saída do modelo, motivos) passa pelo
 * [redigir] — o `RedatorDeSegredos` do app — antes de ser gravado: nenhuma chave de API sai daqui, mesmo que o modelo a repita (BON-03).
 */
object ExecucaoJson {
    const val ESQUEMA = "bancada-resultado/1"

    fun escrever(execucao: Execucao, redigir: (String) -> String = { it }): String = paraJson(execucao, redigir).toString(2)

    fun paraJson(e: Execucao, redigir: (String) -> String = { it }): JSONObject = JSONObject().apply {
        put("esquema", ESQUEMA)
        put("id", e.id)
        put("iniciada", e.iniciada)
        put("estado", e.estado.id)
        put("motivo", e.motivo?.name ?: JSONObject.NULL)
        put(
            "aparelho",
            JSONObject()
                .put("fabricante", e.aparelho.fabricante).put("modelo", e.aparelho.modelo).put("soc", e.aparelho.soc ?: JSONObject.NULL)
                .put("abi", e.aparelho.abi).put("ramTotalMb", e.aparelho.ramTotalMb).put("sdk", e.aparelho.sdk),
        )
        put(
            "app",
            JSONObject()
                .put("versionName", e.app.versionName).put("versionCode", e.app.versionCode).put("build", e.app.build)
                .put("commit", e.app.commit ?: JSONObject.NULL),
        )
        put("motor", JSONObject().put("nome", e.motor.nome).put("versao", e.motor.versao ?: JSONObject.NULL))
        put("corpus", e.corpus)
        put("perfil", e.perfil)
        put(
            "modelo",
            JSONObject()
                .put("id", e.modelo.id).put("origem", e.modelo.origem).put("sha256", e.modelo.sha256 ?: JSONObject.NULL)
                .put("backend", e.modelo.backend).put("threads", e.modelo.threads ?: JSONObject.NULL).put("pensamento", e.modelo.pensamento),
        )
        put(
            "opcoes",
            JSONObject()
                .put("aplicadas", JSONObject().also { o -> e.opcoes.aplicadas.toSortedMap().forEach { (k, v) -> o.put(k, v) } })
                .put("ignoradas", JSONArray(e.opcoes.ignoradas.sorted())),
        )
        put(
            "cargas",
            e.cargas?.let { c ->
                JSONObject().put("friaMs", c.friaMs ?: JSONObject.NULL).put("quenteMs", c.quenteMs ?: JSONObject.NULL)
                    .put("ramPicoMb", c.ramPicoMb ?: JSONObject.NULL).put("memDisponivelMb", c.memDisponivelMb ?: JSONObject.NULL)
            } ?: JSONObject.NULL,
        )
        put("testes", JSONArray().also { a -> e.testes.forEach { a.put(testeParaJson(it, redigir)) } })
    }

    private fun testeParaJson(t: ResultadoTeste, redigir: (String) -> String): JSONObject = JSONObject().apply {
        put("teste", t.teste)
        put("versaoTeste", t.versaoTeste)
        put("nota", t.nota ?: JSONObject.NULL)
        put("n", t.n)
        put("invalidos", t.invalidos)
        put("naoSeAplica", t.naoSeAplica)
        put("naoDeterministico", t.naoDeterministico)
        put("primeiroTrechoMsP50", t.primeiroTrechoMsP50 ?: JSONObject.NULL)
        put("totalMsP50", t.totalMsP50 ?: JSONObject.NULL)
        put("tokensPorSegundoMedia", t.tokensPorSegundoMedia ?: JSONObject.NULL)
        put("casos", JSONArray().also { a -> t.casos.forEach { a.put(casoParaJson(it, redigir)) } })
    }

    private fun casoParaJson(c: ResultadoCaso, redigir: (String) -> String): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("estado", c.estado.name)
        put("causa", c.causa ?: JSONObject.NULL)
        put("motivo", c.motivo?.let(redigir) ?: JSONObject.NULL)
        put("nota", c.nota ?: JSONObject.NULL)
        put("criterios", JSONObject().also { o -> c.criterios.toSortedMap().forEach { (k, v) -> o.put(k, v) } })
        put("marcas", JSONArray(c.marcas.sorted()))
        put("saida", c.saida?.let(redigir) ?: JSONObject.NULL)
        put("primeiroTrechoMs", c.primeiroTrechoMs ?: JSONObject.NULL)
        put("totalMs", c.totalMs ?: JSONObject.NULL)
        put("tokensEntrada", c.tokensEntrada ?: JSONObject.NULL)
        put("tokensSaida", c.tokensSaida ?: JSONObject.NULL)
        put("tokensPorSegundo", c.tokensPorSegundo ?: JSONObject.NULL)
        put("tokensEstimados", c.tokensEstimados)
        put("termico", c.termico ?: JSONObject.NULL)
        put("bateriaPct", c.bateriaPct ?: JSONObject.NULL)
        put("notasRepeticoes", JSONArray().also { a -> c.notasRepeticoes.forEach { a.put(it) } })
    }

    fun ler(texto: String): Execucao {
        val problemas = validar(texto)
        if (problemas.isNotEmpty()) throw ExecucaoInvalidaException(problemas.joinToString("; "))
        return try {
            deJson(JSONObject(texto))
        } catch (e: JSONException) {
            throw ExecucaoInvalidaException("JSON ilegível: ${e.message}")
        }
    }

    fun deJson(j: JSONObject): Execucao {
        val ap = j.getJSONObject("aparelho")
        val app = j.getJSONObject("app")
        val motor = j.getJSONObject("motor")
        val modelo = j.getJSONObject("modelo")
        val opcoes = j.getJSONObject("opcoes")
        val cargas = j.optJSONObject("cargas")
        return Execucao(
            id = j.getString("id"),
            iniciada = j.getString("iniciada"),
            estado = EstadoExecucao.deId(j.getString("estado")),
            motivo = if (j.isNull("motivo")) null else MotivoIncompleta.valueOf(j.getString("motivo")),
            aparelho = Aparelho(
                ap.getString("fabricante"), ap.getString("modelo"), texto(ap, "soc"), ap.getString("abi"), ap.getInt("ramTotalMb"), ap.getInt("sdk"),
            ),
            app = AppInfo(app.getString("versionName"), app.getInt("versionCode"), app.getString("build"), texto(app, "commit")),
            motor = MotorInfo(motor.getString("nome"), texto(motor, "versao")),
            corpus = j.getInt("corpus"),
            perfil = j.getString("perfil"),
            modelo = ModeloInfo(
                modelo.getString("id"), modelo.getString("origem"), texto(modelo, "sha256"), modelo.getString("backend"),
                inteiro(modelo, "threads"), modelo.getBoolean("pensamento"),
            ),
            opcoes = OpcoesRegistradas(
                aplicadas = opcoes.getJSONObject("aplicadas").let { o -> o.keys().asSequence().associateWith { o.getString(it) } },
                ignoradas = opcoes.getJSONArray("ignoradas").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            ),
            cargas = cargas?.let { Cargas(longo(it, "friaMs"), longo(it, "quenteMs"), inteiro(it, "ramPicoMb"), inteiro(it, "memDisponivelMb")) },
            testes = j.getJSONArray("testes").let { a -> (0 until a.length()).map { testeDeJson(a.getJSONObject(it)) } },
        )
    }

    private fun testeDeJson(j: JSONObject) = ResultadoTeste(
        teste = j.getString("teste"),
        versaoTeste = j.getInt("versaoTeste"),
        nota = decimal(j, "nota"),
        n = j.getInt("n"),
        invalidos = j.getInt("invalidos"),
        naoSeAplica = j.getInt("naoSeAplica"),
        naoDeterministico = j.getBoolean("naoDeterministico"),
        primeiroTrechoMsP50 = longo(j, "primeiroTrechoMsP50"),
        totalMsP50 = longo(j, "totalMsP50"),
        tokensPorSegundoMedia = decimal(j, "tokensPorSegundoMedia"),
        casos = j.getJSONArray("casos").let { a -> (0 until a.length()).map { casoDeJson(a.getJSONObject(it)) } },
    )

    private fun casoDeJson(j: JSONObject) = ResultadoCaso(
        id = j.getString("id"),
        estado = EstadoCaso.valueOf(j.getString("estado")),
        causa = texto(j, "causa"),
        motivo = texto(j, "motivo"),
        nota = decimal(j, "nota"),
        criterios = j.getJSONObject("criterios").let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } },
        marcas = j.getJSONArray("marcas").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
        saida = texto(j, "saida"),
        primeiroTrechoMs = longo(j, "primeiroTrechoMs"),
        totalMs = longo(j, "totalMs"),
        tokensEntrada = inteiro(j, "tokensEntrada"),
        tokensSaida = inteiro(j, "tokensSaida"),
        tokensPorSegundo = decimal(j, "tokensPorSegundo"),
        tokensEstimados = j.getBoolean("tokensEstimados"),
        termico = inteiro(j, "termico"),
        bateriaPct = inteiro(j, "bateriaPct"),
        notasRepeticoes = j.getJSONArray("notasRepeticoes").let { a -> (0 until a.length()).map { a.getDouble(it) } },
    )

    private fun texto(j: JSONObject, chave: String): String? = if (!j.has(chave) || j.isNull(chave)) null else j.getString(chave)
    private fun inteiro(j: JSONObject, chave: String): Int? = if (!j.has(chave) || j.isNull(chave)) null else j.getInt(chave)
    private fun longo(j: JSONObject, chave: String): Long? = if (!j.has(chave) || j.isNull(chave)) null else j.getLong(chave)
    private fun decimal(j: JSONObject, chave: String): Double? = if (!j.has(chave) || j.isNull(chave)) null else j.getDouble(chave)

    // ── validação do esquema ──────────────────────────────────────────────────────────────

    /** Confere o [texto] contra o esquema `bancada-resultado/1`: devolve os problemas (vazio = válido). BHS-04. */
    fun validar(texto: String): List<String> {
        val raiz = try {
            JSONObject(texto)
        } catch (e: JSONException) {
            return listOf("não é um JSON: ${e.message}")
        }
        val problemas = ArrayList<String>()
        fun exigir(o: JSONObject, caminho: String, chave: String, tipo: String, anulavel: Boolean = false): Boolean {
            if (!o.has(chave)) {
                problemas += "$caminho$chave: ausente"
                return false
            }
            val v = o.get(chave)
            if (v == JSONObject.NULL) {
                if (!anulavel) problemas += "$caminho$chave: nulo"
                return anulavel
            }
            val ok = when (tipo) {
                "string" -> v is String
                "int" -> v is Int || v is Long
                "number" -> v is Number
                "bool" -> v is Boolean
                "objeto" -> v is JSONObject
                "array" -> v is JSONArray
                else -> false
            }
            if (!ok) problemas += "$caminho$chave: esperava $tipo"
            return ok
        }
        if (raiz.optString("esquema") != ESQUEMA) problemas += "esquema: esperava $ESQUEMA"
        for (c in listOf("id", "iniciada", "estado", "perfil")) exigir(raiz, "", c, "string")
        if (exigir(raiz, "", "motivo", "string", anulavel = true) && !raiz.isNull("motivo") &&
            runCatching { MotivoIncompleta.valueOf(raiz.getString("motivo")) }.isFailure
        ) {
            problemas += "motivo: valor desconhecido"
        }
        exigir(raiz, "", "corpus", "int")
        if (exigir(raiz, "", "estado", "string") && runCatching { EstadoExecucao.deId(raiz.getString("estado")) }.isFailure) problemas += "estado: valor desconhecido"
        if (exigir(raiz, "", "aparelho", "objeto")) raiz.getJSONObject("aparelho").let { a ->
            for (c in listOf("fabricante", "modelo", "abi")) exigir(a, "aparelho.", c, "string")
            exigir(a, "aparelho.", "soc", "string", anulavel = true)
            exigir(a, "aparelho.", "ramTotalMb", "int")
            exigir(a, "aparelho.", "sdk", "int")
        }
        if (exigir(raiz, "", "app", "objeto")) raiz.getJSONObject("app").let { a ->
            for (c in listOf("versionName", "build")) exigir(a, "app.", c, "string")
            exigir(a, "app.", "versionCode", "int")
            exigir(a, "app.", "commit", "string", anulavel = true)
        }
        if (exigir(raiz, "", "motor", "objeto")) raiz.getJSONObject("motor").let { a ->
            exigir(a, "motor.", "nome", "string")
            exigir(a, "motor.", "versao", "string", anulavel = true)
        }
        if (exigir(raiz, "", "modelo", "objeto")) raiz.getJSONObject("modelo").let { a ->
            for (c in listOf("id", "origem", "backend")) exigir(a, "modelo.", c, "string")
            exigir(a, "modelo.", "sha256", "string", anulavel = true)
            exigir(a, "modelo.", "threads", "int", anulavel = true)
            exigir(a, "modelo.", "pensamento", "bool")
        }
        if (exigir(raiz, "", "opcoes", "objeto")) raiz.getJSONObject("opcoes").let { a ->
            exigir(a, "opcoes.", "aplicadas", "objeto")
            exigir(a, "opcoes.", "ignoradas", "array")
        }
        if (raiz.has("cargas") && !raiz.isNull("cargas") && raiz.get("cargas") !is JSONObject) problemas += "cargas: esperava objeto"
        if (exigir(raiz, "", "testes", "array")) {
            val testes = raiz.getJSONArray("testes")
            for (i in 0 until testes.length()) {
                val t = testes.optJSONObject(i)
                if (t == null) {
                    problemas += "testes[$i]: esperava objeto"
                    continue
                }
                val p = "testes[$i]."
                exigir(t, p, "teste", "string")
                exigir(t, p, "versaoTeste", "int")
                exigir(t, p, "nota", "number", anulavel = true)
                for (c in listOf("n", "invalidos", "naoSeAplica")) exigir(t, p, c, "int")
                exigir(t, p, "naoDeterministico", "bool")
                exigir(t, p, "primeiroTrechoMsP50", "int", anulavel = true)
                exigir(t, p, "totalMsP50", "int", anulavel = true)
                exigir(t, p, "tokensPorSegundoMedia", "number", anulavel = true)
                if (exigir(t, p, "casos", "array")) {
                    val casos = t.getJSONArray("casos")
                    for (k in 0 until casos.length()) {
                        val c = casos.optJSONObject(k)
                        if (c == null) {
                            problemas += "$p" + "casos[$k]: esperava objeto"
                            continue
                        }
                        val q = "$p" + "casos[$k]."
                        exigir(c, q, "id", "string")
                        if (exigir(c, q, "estado", "string") && runCatching { EstadoCaso.valueOf(c.getString("estado")) }.isFailure) problemas += "${q}estado: valor desconhecido"
                        exigir(c, q, "nota", "number", anulavel = true)
                        exigir(c, q, "criterios", "objeto")
                        exigir(c, q, "marcas", "array")
                        exigir(c, q, "tokensEstimados", "bool")
                        exigir(c, q, "notasRepeticoes", "array")
                    }
                }
            }
        }
        return problemas
    }
}
