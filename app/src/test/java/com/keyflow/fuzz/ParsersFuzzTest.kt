package com.keyflow.fuzz

import com.keyflow.agent.FakeTool
import com.keyflow.agent.ParsedArgs
import com.keyflow.agent.ToolRegistry
import com.keyflow.auth.CredentialCodec
import com.keyflow.core.shortcuts.ShortcutCodec
import com.keyflow.skins.SkinPackManifest
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Fuzzing dos leitores de texto: nenhum deles pode lançar com entrada ruim — o contrato de cada um é recusar (ou cair no
 * padrão) e seguir. Uma exceção que escapa derruba o teclado (barras), a orbe (skin) ou o laço do agente (argumentos que o
 * modelo inventa). Rodam na JVM com o org.json do Maven, parecido mas não idêntico ao do Android.
 */
class ParsersFuzzTest {

    private fun neverThrows(what: String, input: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail("$what lançou ${t.javaClass.simpleName} (${t.message?.take(120)}) com a entrada: ${input.take(300)}")
        }
    }

    @Test(timeout = 120_000)
    fun barsAndGridsDecodingNeverThrows() = runBlocking<Unit> {
        checkAll(ITERATIONS, FuzzInputs.anyText) { input ->
            neverThrows("decodeBars", input) { ShortcutCodec.decodeBars(input) }
            neverThrows("decodeGrids", input) { ShortcutCodec.decodeGrids(input) }
        }
    }

    @Test(timeout = 120_000)
    fun credentialDecodingNeverThrows() = runBlocking<Unit> {
        checkAll(ITERATIONS, FuzzInputs.anyText) { input -> neverThrows("CredentialCodec.decode", input) { CredentialCodec.decode(input) } }
    }

    /** O manifest vem do .zip que a pessoa importa: entrada hostil por definição. */
    @Test(timeout = 120_000)
    fun skinManifestParsingNeverThrows() = runBlocking<Unit> {
        checkAll(ITERATIONS, FuzzInputs.anyText) { input -> neverThrows("SkinPackManifest.parse", input) { SkinPackManifest.parse(input) } }
    }

    /** Os argumentos de ferramenta vêm do modelo: o que não passar no esquema é recusado, nunca uma exceção no laço. */
    @Test(timeout = 120_000)
    fun toolArgumentsAreRejectedNotThrown() = runBlocking<Unit> {
        val tool = FakeTool(
            "fuzz",
            schema = """{"type":"object","properties":{
                "text":{"type":"string","maxLength":40},
                "count":{"type":"integer","minimum":0,"maximum":10},
                "on":{"type":"boolean"},
                "mode":{"type":"string","enum":["a","b"]},
                "list":{"type":"array","maxItems":3,"items":{"type":"string"}},
                "inner":{"type":"object","properties":{"x":{"type":"number"}},"additionalProperties":false}
            },"required":["text"],"additionalProperties":false}""",
        )
        val registry = ToolRegistry(listOf(tool))
        checkAll(ITERATIONS, FuzzInputs.anyText) { input ->
            neverThrows("parseArgs", input) {
                val parsed = registry.parseArgs(tool, input)
                if (parsed is ParsedArgs.Valid) assertTrue("aceitou sem o campo obrigatório: $input", parsed.args.has("text"))
            }
        }
    }

    companion object {
        const val ITERATIONS = 5_000
    }
}
