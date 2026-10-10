package com.keyflow.agent

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolRegistryTest {

    private val registry = ToolRegistry(listOf(FakeTool("skin_list"), FakeTool("skin_install", ToolRisk.WRITE), FakeTool("skin_remove", ToolRisk.DESTRUCTIVE)))

    @Test
    fun `registers resolves and keeps the registration order`() {
        assertThat(registry.all().map { it.name }).containsExactly("skin_list", "skin_install", "skin_remove").inOrder()
        assertThat(registry["skin_install"]?.risk).isEqualTo(ToolRisk.WRITE)
        assertThat(registry["nope"]).isNull()
    }

    @Test
    fun `refuses a name the API would refuse`() {
        for (bad in listOf("", "with space", "açúcar", "a.b", "x".repeat(129))) {
            val e = assertThrows(IllegalArgumentException::class.java) { ToolRegistry().register(FakeTool(bad)) }
            assertThat(e.message).contains("nome de ferramenta inválido")
        }
        ToolRegistry().register(FakeTool("a-b_C9"))
        ToolRegistry().register(FakeTool("x".repeat(128)))
    }

    @Test
    fun `refuses a duplicate name`() {
        val e = assertThrows(IllegalArgumentException::class.java) { registry.register(FakeTool("skin_list")) }
        assertThat(e.message).contains("já registrada")
    }

    @Test
    fun `refuses a schema outside the subset at registration time`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ToolRegistry().register(FakeTool("t", schema = """{"type":"object","properties":{"a":{"type":"string","pattern":"^x"}}}"""))
        }
        assertThat(e.message).contains("esquema inválido em t")
    }

    @Test
    fun `lists only the active tools`() {
        assertThat(registry.active { it != "skin_install" }.map { it.name }).containsExactly("skin_list", "skin_remove").inOrder()
        assertThat(registry.definitions { it == "skin_list" }.map { it.name }).containsExactly("skin_list")
    }

    @Test
    fun `the definitions carry a copy of the schema`() {
        val tool = registry["skin_list"]!!
        val definition = registry.definitions { true }.first { it.name == "skin_list" }
        definition.inputSchema.put("hacked", true)
        assertThat(tool.inputSchema.has("hacked")).isFalse()
    }

    // --- argumentos ---

    private val tool get() = registry["skin_list"]!!

    private fun parse(json: String) = registry.parseArgs(tool, json)

    @Test
    fun `parses valid arguments`() {
        val parsed = parse("""{"text":"oi"}""")
        assertThat(parsed).isInstanceOf(ParsedArgs.Valid::class.java)
        assertThat((parsed as ParsedArgs.Valid).args.getString("text")).isEqualTo("oi")
    }

    @Test
    fun `empty arguments mean an empty object`() {
        assertThat(parse("")).isInstanceOf(ParsedArgs.Valid::class.java)
        assertThat(parse("   ")).isInstanceOf(ParsedArgs.Valid::class.java)
        assertThat(parse("{}")).isInstanceOf(ParsedArgs.Valid::class.java)
    }

    @Test
    fun `rejects what is not a JSON object`() {
        for (bad in listOf("""{"text":""", "not json", "[1,2]", "\"x\"", "42", """{"text":"a"} trailing""")) {
            val parsed = parse(bad)
            assertThat(parsed).isInstanceOf(ParsedArgs.Rejected::class.java)
        }
    }

    @Test
    fun `rejects arguments that break the schema without running anything`() {
        val parsed = parse("""{"text":"${"x".repeat(101)}"}""") as ParsedArgs.Rejected
        assertThat(parsed.reason).contains("o máximo é 100")
        assertThat((parse("""{"other":1}""") as ParsedArgs.Rejected).reason).contains("não existe nesta ferramenta")
    }

    @Test
    fun `rejects deep nesting before the parser sees it`() {
        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        val parsed = parse("{\"text\":$deep}") as ParsedArgs.Rejected
        assertThat(parsed.reason).contains("aninhamento")
        // Nem o texto do modelo é repetido na resposta.
        assertThat(parsed.reason.length).isLessThan(200)
    }

    @Test
    fun `brackets inside strings do not count as nesting`() {
        val text = "[".repeat(100)
        assertThat(ToolRegistry.nestingDepth("{\"text\":\"$text\"}")).isEqualTo(1)
        assertThat(ToolRegistry.nestingDepth("{\"a\":{\"b\":[1,{\"c\":2}]}}")).isEqualTo(4)
        assertThat(ToolRegistry.nestingDepth("{\"t\":\"a\\\"[[[\"}")).isEqualTo(1)
    }

    @Test
    fun `rejects arguments over the size limit`() {
        val parsed = registry.parseArgs(tool, """{"text":"${"x".repeat(50)}"}""", maxChars = 20) as ParsedArgs.Rejected
        assertThat(parsed.reason).contains("limite de 20")
    }
}
