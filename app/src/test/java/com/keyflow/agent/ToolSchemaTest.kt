package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** D3: o subconjunto de JSON Schema e o validador (TLS-01). */
class ToolSchemaTest {

    private val schema = JSONObject(
        """
        {
          "type": "object",
          "properties": {
            "name":  {"type": "string", "minLength": 2, "maxLength": 8},
            "size":  {"type": "integer", "minimum": 110, "maximum": 280},
            "ratio": {"type": "number", "minimum": 0, "maximum": 1},
            "on":    {"type": "boolean"},
            "mode":  {"type": "string", "enum": ["light", "dark"]},
            "level": {"type": "integer", "enum": [1, 2, 3]},
            "tags":  {"type": "array", "items": {"type": "string", "maxLength": 3}, "minItems": 1, "maxItems": 3}
          },
          "required": ["name"],
          "additionalProperties": false
        }
        """,
    )

    private fun errors(json: String): List<String> = ToolSchema.validate(schema, JSONObject(json))

    @Test
    fun `accepts a call that follows the schema`() {
        assertThat(errors("""{"name":"neon"}""")).isEmpty()
        assertThat(errors("""{"name":"neon","size":156,"ratio":0.5,"on":true,"mode":"dark","level":2,"tags":["a","bc"]}""")).isEmpty()
    }

    @Test
    fun `refuses a missing required argument`() {
        assertThat(errors("""{"size":156}""")).containsExactly("argumentos: falta o argumento obrigatório \"name\"")
    }

    @Test
    fun `refuses a wrong type for each type`() {
        assertThat(errors("""{"name":5}""").single()).contains("esperava um texto, veio um número")
        assertThat(errors("""{"name":"ab","size":"big"}""").single()).contains("esperava um número inteiro, veio um texto")
        assertThat(errors("""{"name":"ab","size":156.5}""").single()).contains("esperava um número inteiro")
        assertThat(errors("""{"name":"ab","ratio":"x"}""").single()).contains("esperava um número, veio um texto")
        assertThat(errors("""{"name":"ab","on":"true"}""").single()).contains("esperava verdadeiro ou falso")
        assertThat(errors("""{"name":"ab","tags":"a"}""").single()).contains("esperava uma lista, veio um texto")
        assertThat(errors("""{"name":null}""").single()).contains("veio nulo")
        assertThat(ToolSchema.validate(schema, JSONArray()).single()).contains("esperava um objeto, veio uma lista")
    }

    @Test
    fun `a whole number written with a decimal point is still an integer`() {
        assertThat(errors("""{"name":"ab","size":156.0}""")).isEmpty()
    }

    @Test
    fun `refuses numbers outside the range and accepts the edges`() {
        assertThat(errors("""{"name":"ab","size":109}""").single()).contains("menor que o mínimo 110")
        assertThat(errors("""{"name":"ab","size":281}""").single()).contains("maior que o máximo 280")
        assertThat(errors("""{"name":"ab","size":110}""")).isEmpty()
        assertThat(errors("""{"name":"ab","size":280}""")).isEmpty()
        assertThat(errors("""{"name":"ab","ratio":1.5}""").single()).contains("maior que o máximo 1")
        assertThat(errors("""{"name":"ab","ratio":-0.1}""").single()).contains("menor que o mínimo 0")
        assertThat(errors("""{"name":"ab","size":99999999999}""").single()).contains("maior que o máximo")
    }

    @Test
    fun `refuses texts outside the length bounds`() {
        assertThat(errors("""{"name":"a"}""").single()).contains("o mínimo é 2")
        assertThat(errors("""{"name":"abcdefghi"}""").single()).contains("o máximo é 8")
    }

    @Test
    fun `enum accepts only the listed values for strings and numbers`() {
        assertThat(errors("""{"name":"ab","mode":"sepia"}""").single()).contains("fora da lista permitida")
        assertThat(errors("""{"name":"ab","level":4}""").single()).contains("fora da lista permitida")
        assertThat(errors("""{"name":"ab","level":3}""")).isEmpty()
    }

    @Test
    fun `refuses an argument the schema does not declare`() {
        val found = errors("""{"name":"ab","secret":"x"}""")
        assertThat(found.single()).contains("argumento \"secret\" não existe nesta ferramenta")
    }

    @Test
    fun `additionalProperties true allows extras and a schema validates them`() {
        val open = JSONObject("""{"type":"object","properties":{},"additionalProperties":true}""")
        assertThat(ToolSchema.validate(open, JSONObject("""{"x":1,"y":[1]}"""))).isEmpty()

        val files = JSONObject("""{"type":"object","properties":{},"additionalProperties":{"type":"string","maxLength":5}}""")
        assertThat(ToolSchema.validate(files, JSONObject("""{"a.svg":"abc","b.svg":"de"}"""))).isEmpty()
        val bad = ToolSchema.validate(files, JSONObject("""{"a.svg":"abcdefgh","b.svg":3}"""))
        assertThat(bad).hasSize(2)
        assertThat(bad.joinToString()).contains("a.svg")
    }

    @Test
    fun `validates array items with the index in the path and the item count`() {
        assertThat(errors("""{"name":"ab","tags":["a","toolong"]}""").single()).contains("tags[1]")
        assertThat(errors("""{"name":"ab","tags":[]}""").single()).contains("o mínimo é 1")
        assertThat(errors("""{"name":"ab","tags":["a","b","c","d"]}""").single()).contains("o máximo é 3")
        assertThat(errors("""{"name":"ab","tags":[1]}""").single()).contains("tags[0]")
    }

    @Test
    fun `validates nested objects`() {
        val nested = JSONObject("""{"type":"object","properties":{"box":{"type":"object","properties":{"w":{"type":"integer","minimum":1}},"required":["w"]}}}""")
        assertThat(ToolSchema.validate(nested, JSONObject("""{"box":{"w":2}}"""))).isEmpty()
        assertThat(ToolSchema.validate(nested, JSONObject("""{"box":{}}"""))).containsExactly("argumentos.box: falta o argumento obrigatório \"w\"")
        assertThat(ToolSchema.validate(nested, JSONObject("""{"box":{"w":0}}""")).single()).contains("argumentos.box.w")
    }

    @Test
    fun `lists at most eight problems`() {
        val many = JSONObject().apply { for (i in 0 until 30) put("k$i", 1) }
        assertThat(ToolSchema.validate(schema, many).size).isAtMost(8)
    }

    @Test
    fun `a key chosen by the model is cut in the message`() {
        val long = "x".repeat(500)
        val message = ToolSchema.validate(schema, JSONObject().put("name", "ab").put(long, 1)).single()
        assertThat(message.length).isLessThan(150)
    }

    // --- o próprio esquema (check) ---

    @Test
    fun `check accepts a good schema`() {
        assertThat(ToolSchema.check(schema)).isEmpty()
    }

    @Test
    fun `check refuses a root that is not an object`() {
        assertThat(ToolSchema.check(JSONObject("""{"type":"string"}""")).joinToString()).contains("type=object")
    }

    @Test
    fun `check refuses keywords outside the subset so a rule is never silently ignored`() {
        for (keyword in listOf("pattern", "oneOf", "\$ref", "format", "allOf", "const")) {
            val s = JSONObject("""{"type":"object","properties":{"a":{"type":"string"}}}""").put(keyword, "x")
            assertThat(ToolSchema.check(s).joinToString()).contains("palavra-chave não suportada \"$keyword\"")
        }
        val nested = JSONObject("""{"type":"object","properties":{"a":{"type":"string","pattern":"^x"}}}""")
        assertThat(ToolSchema.check(nested).joinToString()).contains("pattern")
    }

    @Test
    fun `check refuses malformed schemas`() {
        val bad = listOf(
            """{"type":"object","properties":{"a":{}}}""" to "type ausente",
            """{"type":"object","properties":{"a":{"type":"date"}}}""" to "type ausente ou não suportado",
            """{"type":"object","properties":{"a":{"type":"array"}}}""" to "precisa de items",
            """{"type":"object","properties":{"a":{"type":"string","enum":[]}}}""" to "enum",
            """{"type":"object","properties":{},"required":["x"]}""" to "não está em properties",
            """{"type":"object","properties":{"a":{"type":"integer","minimum":"1"}}}""" to "minimum precisa ser um número",
            """{"type":"object","properties":{"a":{"type":"string","maxLength":-1}}}""" to "não negativo",
            """{"type":"object","additionalProperties":"yes"}""" to "additionalProperties",
        )
        for ((json, expected) in bad) {
            assertThat(ToolSchema.check(JSONObject(json)).joinToString()).contains(expected)
        }
    }

    @Test
    fun `a bad schema reaching the validator refuses everything instead of passing`() {
        val broken = JSONObject("""{"type":"mystery"}""")
        assertThat(ToolSchema.validate(broken, JSONObject())).isNotEmpty()
    }
}
