package com.keyflow.agent

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * O subconjunto de JSON Schema que as ferramentas do agente usam (D3) e o validador dele. Puro (JVM).
 *
 * Aceita: `type` (`object`, `string`, `integer`, `number`, `boolean`, `array`), `properties`, `required`, `additionalProperties`
 * (booleano ou um esquema, para mapas como "nome do arquivo → texto"), `items`, `enum`, `minimum`/`maximum`, `minLength`/`maxLength`,
 * `minItems`/`maxItems`, e `description`/`default` (só documentação, sem efeito).
 *
 * Duas escolhas de segurança, ambas mais duras que o JSON Schema padrão:
 *  - propriedade que o esquema não declara é RECUSADA (salvo `additionalProperties`): o esquema de uma ferramenta lista tudo o que ela
 *    entende, e um argumento a mais é um modelo confuso — melhor devolver o erro e deixá-lo se corrigir;
 *  - palavra-chave fora do subconjunto (`pattern`, `oneOf`, `$ref`…) é recusada no REGISTRO ([check]): uma regra que o validador não
 *    aplica não pode parecer que protege.
 */
object ToolSchema {

    private val TYPES = setOf("object", "string", "integer", "number", "boolean", "array")

    private val KEYWORDS = setOf(
        "type", "description", "default", "enum", "properties", "required", "additionalProperties", "items",
        "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems",
    )

    /** Quantos problemas listar numa resposta ao modelo: o suficiente para ele se corrigir, sem despejar uma página. */
    private const val MAX_ERRORS = 8

    /** Profundidade máxima de um argumento que o esquema deixa descer. */
    private const val MAX_DEPTH = 16

    /**
     * Os defeitos do próprio esquema (o autor da ferramenta é quem lê): lista vazia = esquema aceito. A raiz precisa ser um objeto —
     * é o que a API da Anthropic exige em `input_schema`.
     */
    fun check(schema: JSONObject): List<String> {
        val problems = ArrayList<String>()
        if (schema.opt("type") != "object") problems += "a raiz do esquema precisa ter type=object"
        checkNode(schema, "$", problems, 0)
        return problems
    }

    private fun checkNode(node: JSONObject, path: String, problems: MutableList<String>, depth: Int) {
        if (depth > MAX_DEPTH) {
            problems += "$path: esquema aninhado demais"
            return
        }
        val keys = node.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in KEYWORDS) problems += "$path: palavra-chave não suportada \"$key\""
        }
        val type = node.opt("type") as? String
        if (type == null || type !in TYPES) {
            problems += "$path: type ausente ou não suportado"
            return
        }
        node.opt("enum")?.let { e ->
            if (e !is JSONArray || e.length() == 0) problems += "$path: enum precisa ser uma lista não vazia"
        }
        for (numeric in listOf("minimum", "maximum")) {
            node.opt(numeric)?.let { if (it !is Number) problems += "$path: $numeric precisa ser um número" }
        }
        for (count in listOf("minLength", "maxLength", "minItems", "maxItems")) {
            node.opt(count)?.let { if (it !is Number || it.toDouble() < 0) problems += "$path: $count precisa ser um inteiro não negativo" }
        }
        when (type) {
            "object" -> {
                val props = node.optJSONObject("properties")
                if (node.has("properties") && props == null) problems += "$path: properties precisa ser um objeto"
                props?.let {
                    val names = it.keys()
                    while (names.hasNext()) {
                        val name = names.next()
                        val child = it.optJSONObject(name)
                        if (child == null) problems += "$path.$name: a propriedade precisa de um esquema" else checkNode(child, "$path.$name", problems, depth + 1)
                    }
                }
                val required = node.opt("required")
                if (required != null) {
                    if (required !is JSONArray) {
                        problems += "$path: required precisa ser uma lista"
                    } else {
                        for (i in 0 until required.length()) {
                            val name = required.opt(i) as? String
                            if (name == null) problems += "$path: required só aceita nomes" else if (props?.has(name) != true) problems += "$path: required cita \"$name\", que não está em properties"
                        }
                    }
                }
                when (val extra = node.opt("additionalProperties")) {
                    null, is Boolean -> Unit
                    is JSONObject -> checkNode(extra, "$path.*", problems, depth + 1)
                    else -> problems += "$path: additionalProperties precisa ser booleano ou um esquema"
                }
            }
            "array" -> {
                val items = node.optJSONObject("items")
                if (items == null) problems += "$path: array precisa de items" else checkNode(items, "$path[]", problems, depth + 1)
            }
        }
    }

    /**
     * Confere [value] (o que o `org.json` devolveu para os argumentos) contra [schema]. Lista vazia = válido; senão, frases curtas
     * em português com o caminho do problema, no máximo [MAX_ERRORS] — vão ao modelo como resultado de erro.
     */
    fun validate(schema: JSONObject, value: Any?): List<String> {
        val errors = ArrayList<String>()
        validateNode(schema, value, "argumentos", errors, 0)
        return errors
    }

    private fun validateNode(schema: JSONObject, value: Any?, path: String, errors: MutableList<String>, depth: Int) {
        if (errors.size >= MAX_ERRORS) return
        if (depth > MAX_DEPTH) {
            errors += "$path: aninhado demais"
            return
        }
        val type = schema.opt("type") as? String
        when (type) {
            "object" -> {
                if (value !is JSONObject) return typeError(path, "um objeto", value, errors)
                validateObject(schema, value, path, errors, depth)
            }
            "array" -> {
                if (value !is JSONArray) return typeError(path, "uma lista", value, errors)
                validateArray(schema, value, path, errors, depth)
            }
            "string" -> {
                if (value !is String) return typeError(path, "um texto", value, errors)
                (schema.opt("minLength") as? Number)?.let { if (value.length < it.toInt()) errors += "$path: o texto tem ${value.length} caracteres, o mínimo é ${it.toInt()}" }
                (schema.opt("maxLength") as? Number)?.let { if (value.length > it.toInt()) errors += "$path: o texto tem ${value.length} caracteres, o máximo é ${it.toInt()}" }
            }
            "integer" -> {
                if (value !is Number || !isIntegral(value)) return typeError(path, "um número inteiro", value, errors)
                checkRange(schema, value, path, errors)
            }
            "number" -> {
                if (value !is Number || !value.toDouble().isFinite()) return typeError(path, "um número", value, errors)
                checkRange(schema, value, path, errors)
            }
            "boolean" -> if (value !is Boolean) return typeError(path, "verdadeiro ou falso", value, errors)
            // Um esquema sem type reconhecido não valida nada: o `check` do registro já o recusou; se chegou aqui, recusa tudo.
            else -> {
                errors += "$path: o esquema da ferramenta é inválido"
                return
            }
        }
        val allowed = schema.optJSONArray("enum")
        if (allowed != null && errors.size < MAX_ERRORS && !inEnum(allowed, value)) {
            errors += "$path: valor fora da lista permitida (${enumText(allowed)})"
        }
    }

    private fun validateObject(schema: JSONObject, value: JSONObject, path: String, errors: MutableList<String>, depth: Int) {
        val props = schema.optJSONObject("properties")
        schema.optJSONArray("required")?.let { required ->
            for (i in 0 until required.length()) {
                val name = required.optString(i)
                if (!value.has(name)) errors += "$path: falta o argumento obrigatório \"$name\""
            }
        }
        val extra = schema.opt("additionalProperties")
        val keys = value.keys()
        while (keys.hasNext()) {
            if (errors.size >= MAX_ERRORS) return
            val key = keys.next()
            val child = value.opt(key)
            val declared = props?.optJSONObject(key)
            when {
                declared != null -> validateNode(declared, child, "$path.${shortKey(key)}", errors, depth + 1)
                extra == true -> Unit
                extra is JSONObject -> validateNode(extra, child, "$path.${shortKey(key)}", errors, depth + 1)
                else -> errors += "$path: argumento \"${shortKey(key)}\" não existe nesta ferramenta"
            }
        }
    }

    private fun validateArray(schema: JSONObject, value: JSONArray, path: String, errors: MutableList<String>, depth: Int) {
        (schema.opt("minItems") as? Number)?.let { if (value.length() < it.toInt()) errors += "$path: a lista tem ${value.length()} itens, o mínimo é ${it.toInt()}" }
        (schema.opt("maxItems") as? Number)?.let { if (value.length() > it.toInt()) errors += "$path: a lista tem ${value.length()} itens, o máximo é ${it.toInt()}" }
        val items = schema.optJSONObject("items") ?: return
        for (i in 0 until value.length()) {
            if (errors.size >= MAX_ERRORS) return
            validateNode(items, value.opt(i), "$path[$i]", errors, depth + 1)
        }
    }

    private fun checkRange(schema: JSONObject, value: Number, path: String, errors: MutableList<String>) {
        val v = value.toDouble()
        (schema.opt("minimum") as? Number)?.let { if (v < it.toDouble()) errors += "$path: ${show(value)} é menor que o mínimo ${show(it)}" }
        (schema.opt("maximum") as? Number)?.let { if (v > it.toDouble()) errors += "$path: ${show(value)} é maior que o máximo ${show(it)}" }
    }

    /** `3` e `3.0` são inteiros (como no JSON Schema); `3.5` não. O `org.json` do Android devolve `Double` para `3.0`, o real pode devolver `BigDecimal`. */
    private fun isIntegral(n: Number): Boolean = when (n) {
        is Int, is Long, is Short, is Byte, is BigInteger -> true
        is BigDecimal -> n.stripTrailingZeros().scale() <= 0
        else -> n.toDouble().let { it.isFinite() && it == Math.rint(it) }
    }

    private fun inEnum(allowed: JSONArray, value: Any?): Boolean {
        for (i in 0 until allowed.length()) {
            val candidate = allowed.opt(i)
            val same = when {
                candidate is Number && value is Number -> candidate.toDouble() == value.toDouble()
                else -> candidate == value
            }
            if (same) return true
        }
        return false
    }

    private fun enumText(allowed: JSONArray): String = (0 until allowed.length()).joinToString(", ") { allowed.opt(it).toString() }.take(120)

    private fun typeError(path: String, expected: String, value: Any?, errors: MutableList<String>) {
        errors += "$path: esperava $expected, veio ${kindOf(value)}"
    }

    private fun kindOf(value: Any?): String = when {
        value == null || JSONObject.NULL == value -> "nulo"
        value is JSONObject -> "um objeto"
        value is JSONArray -> "uma lista"
        value is String -> "um texto"
        value is Boolean -> "verdadeiro/falso"
        value is Number -> "um número"
        else -> "outro tipo"
    }

    private fun show(n: Number): String = if (isIntegral(n)) n.toLong().toString() else n.toString()

    /** O nome de uma chave que o MODELO escolheu, cortado: ele volta ao modelo numa mensagem de erro e não deve levar um texto enorme junto. */
    private fun shortKey(key: String): String = if (key.length > 40) key.take(40) + "…" else key
}
