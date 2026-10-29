package com.keyflow.bancada.pontuacao

import com.keyflow.bancada.corpus.Caso
import java.math.BigDecimal
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * `json` (BPT-05): o modelo extrai campos de um texto e responde só com JSON. Pesos do design §3: **válido** (0,2) — um objeto JSON,
 * limpo ou numa cerca de código (1), com texto em volta (0,5, marca `TEXTO_EM_VOLTA`); **esquema** (0,2) — fração dos campos do
 * `esquema` presentes com o tipo certo; **campos** (0,6) — fração dos campos de `esperado` com o valor certo. Campo extra não penaliza;
 * campo faltante pesa nos dois. Datas e números são normalizados antes de comparar ("agora" vai fixo no prompt).
 *
 * Tipos do esquema: `string`, `number`, `integer`, `boolean`, `date` (`AAAA-MM-DD`), `datetime` (`AAAA-MM-DDThh:mm`) e `array`; com `?` no
 * fim (`string?`) o campo aceita `null`. A chave precisa estar na resposta: omitir não é o mesmo que responder `null`.
 */
object PontuadorJson : Pontuador {
    const val VALIDO = "valido"
    const val ESQUEMA = "esquema"
    const val CAMPOS = "campos"

    private val PESOS = mapOf(VALIDO to 0.2, ESQUEMA to 0.2, CAMPOS to 0.6)

    override fun pontuar(caso: Caso, saida: String): Pontuacao {
        if (saida.isBlank()) return Pontuacao.zero(Marca.VAZIA)
        val extracao = ExtratorJson.extrair(saida)
        val objeto = extracao.primeiroObjeto ?: return Pontuacao.zero(Marca.JSON_ILEGIVEL)
        val marcas = LinkedHashSet<Marca>()
        val valido = when (extracao.forma) {
            ExtratorJson.Forma.LIMPO -> 1.0
            ExtratorJson.Forma.CERCA -> {
                marcas += Marca.CERCA_DE_CODIGO
                1.0
            }
            else -> {
                marcas += Marca.TEXTO_EM_VOLTA
                0.5
            }
        }
        val esquema = caso.bruto.optJSONObject("esquema") ?: JSONObject()
        val esperado = caso.esperado as? JSONObject ?: error("caso ${caso.id}: o pontuador `json` exige `esperado` como objeto")
        val chavesEsquema = esquema.keys().asSequence().toList()
        val noEsquema = if (chavesEsquema.isEmpty()) 1.0 else chavesEsquema.count { objeto.has(it) && tipoCerto(objeto.opt(it), esquema.getString(it)) }.toDouble() / chavesEsquema.size
        val chavesEsperadas = esperado.keys().asSequence().toList()
        val campos = if (chavesEsperadas.isEmpty()) 1.0 else chavesEsperadas.count {
            objeto.has(it) && valorCerto(objeto.opt(it), esperado.opt(it), esquema.optString(it, "string").removeSuffix("?"))
        }.toDouble() / chavesEsperadas.size
        return Pontuacao.ponderada(mapOf(VALIDO to valido, ESQUEMA to noEsquema, CAMPOS to campos), PESOS, marcas)
    }

    /** O valor tem o [tipo] do esquema; `tipo?` aceita também `null`. */
    internal fun tipoCerto(valor: Any?, tipo: String): Boolean {
        if (tipo.endsWith("?") && (valor == null || valor == JSONObject.NULL)) return true
        return tipoBase(valor, tipo.removeSuffix("?"))
    }

    private fun tipoBase(valor: Any?, tipo: String): Boolean = when (tipo) {
        "string" -> valor is String
        "number" -> valor is Number
        "integer" -> valor is Number && ehInteiro(valor)
        "boolean" -> valor is Boolean
        "date" -> valor is String && dataIso(valor) != null
        "datetime" -> valor is String && dataHoraIso(valor) != null
        "array" -> valor is JSONArray
        else -> false
    }

    /** [obtido] vale o mesmo que [esperado] depois de normalizar conforme o [tipo]. */
    internal fun valorCerto(obtido: Any?, esperado: Any?, tipo: String): Boolean {
        if (obtido == null || obtido == JSONObject.NULL) return esperado == null || esperado == JSONObject.NULL
        return when (tipo) {
            "number", "integer" -> {
                val a = decimal(obtido)
                val b = decimal(esperado)
                a != null && b != null && a.compareTo(b) == 0
            }
            "boolean" -> obtido is Boolean && obtido == esperado
            "date" -> obtido is String && esperado is String && dataIso(obtido) != null && dataIso(obtido) == dataIso(esperado)
            "datetime" -> obtido is String && esperado is String && dataHoraIso(obtido) != null && dataHoraIso(obtido) == dataHoraIso(esperado)
            "array" -> obtido is JSONArray && esperado is JSONArray && elementos(obtido) == elementos(esperado)
            else -> obtido is String && esperado is String && Normalizacao.texto(obtido) == Normalizacao.texto(esperado)
        }
    }

    private fun elementos(array: JSONArray): Set<String> = (0 until array.length()).map { Normalizacao.texto(array.opt(it).toString()) }.toSet()

    private fun ehInteiro(n: Number): Boolean = decimal(n)?.let { it.stripTrailingZeros().scale() <= 0 } ?: false

    /** Número ou texto numérico com ponto decimal; `null` se não é número. */
    private fun decimal(valor: Any?): BigDecimal? = when (valor) {
        is Number -> try { BigDecimal(valor.toString()) } catch (e: NumberFormatException) { null }
        is String -> try { BigDecimal(valor.trim()) } catch (e: NumberFormatException) { null }
        else -> null
    }

    private val BR = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""")
    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    /** `AAAA-MM-DD` ou `dd/MM/aaaa`, como data ISO; `null` se não é uma data de calendário (31/02 não é). Sem `java.time`: o `minSdk` é 24. */
    internal fun dataIso(texto: String): String? {
        val t = texto.trim()
        val (ano, mes, dia) = BR.matchEntire(t)?.let { Triple(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
            ?: ISO.matchEntire(t)?.let { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            ?: return null
        if (mes !in 1..12 || dia !in 1..diasNoMes(ano, mes)) return null
        return String.format(Locale.ROOT, "%04d-%02d-%02d", ano, mes, dia)
    }

    private fun diasNoMes(ano: Int, mes: Int): Int = when (mes) {
        2 -> if (ano % 4 == 0 && (ano % 100 != 0 || ano % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private val DATA_HORA = Regex("""^(\d{4}-\d{2}-\d{2})[T ](\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?(?:Z|[+-]\d{2}:?\d{2})?$""")

    /** `AAAA-MM-DDThh:mm` (segundos e fuso ignorados); `null` se não é data e hora ISO. */
    internal fun dataHoraIso(texto: String): String? {
        val m = DATA_HORA.matchEntire(texto.trim()) ?: return null
        val data = dataIso(m.groupValues[1]) ?: return null
        val hora = m.groupValues[2].toInt()
        val minuto = m.groupValues[3].toInt()
        if (hora > 23 || minuto > 59) return null
        return String.format(Locale.ROOT, "%sT%02d:%02d", data, hora, minuto)
    }
}
