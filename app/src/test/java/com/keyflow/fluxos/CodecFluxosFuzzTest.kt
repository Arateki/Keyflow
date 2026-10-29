package com.keyflow.fluxos

import com.keyflow.fuzz.FuzzInputs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * O JSON dos fluxos vem do DataStore, de uma versão mais nova do app ou de um arquivo restaurado: entrada hostil por
 * definição. O codec recusa (cai na semente, descarta o item) e segue — nunca lança, e o que devolve respeita os limites.
 * Roda na JVM com o org.json do Maven, parecido mas não idêntico ao do Android.
 */
class CodecFluxosFuzzTest {

    private fun neverThrows(what: String, input: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail("$what lançou ${t.javaClass.simpleName} (${t.message?.take(120)}) com a entrada: ${input.take(300)}")
        }
    }

    /** Uma config válida codificada e depois mutada (cortada, com caracteres trocados, inseridos ou apagados). */
    private val mutadas: Arb<String> = arbitrary { rs ->
        val c = CodecFluxosPropertyTest.config.sample(rs).value
        val json = if (rs.random.nextBoolean()) CodecFluxos.codificarFluxos(c.fluxos) else CodecFluxos.codificarGatilhos(c.gatilhos)
        FuzzInputs.mutate(rs.random, json)
    }

    private fun conferirInvariantes(config: ConfigFluxos, input: String) {
        assertTrue("fluxos demais: ${input.take(200)}", config.fluxos.size <= LIMITES.FLUXOS || config.fluxos === SEMENTE)
        assertEquals("ids repetidos: ${input.take(200)}", config.fluxos.map { it.id }.distinct().size, config.fluxos.size)
        config.fluxos.filter { it !in SEMENTE }.forEach { f ->
            assertTrue("nome fora de 1..40", f.nome.isNotBlank() && f.nome.length <= LIMITES.NOME)
            assertTrue("etapas demais", f.etapas.size <= LIMITES.ETAPAS)
            f.etapas.forEach { e ->
                if (e is Etapa.Visao) assertTrue((e.instrucao?.length ?: 0) <= LIMITES.TEXTO_DA_ETAPA)
                if (e is Etapa.Perguntar) assertTrue((e.sistema?.length ?: 0) <= LIMITES.TEXTO_DA_ETAPA)
            }
        }
        assertTrue("gatilhos demais", config.gatilhos.size <= LIMITES.GATILHOS)
        assertEquals("superfície repetida", config.gatilhos.map { it.superficie }.distinct().size, config.gatilhos.size)
    }

    @Test(timeout = 120_000)
    fun decodingNeverThrowsAndAlwaysHonoursTheLimits() = runBlocking<Unit> {
        checkAll(ITERATIONS, FuzzInputs.anyText) { input -> exercitar(input) }
        checkAll(ITERATIONS, mutadas) { input -> exercitar(input) }
    }

    private fun exercitar(input: String) {
        neverThrows("decodificarFluxos", input) { CodecFluxos.decodificarFluxos(input) }
        neverThrows("decodificarGatilhos", input) { CodecFluxos.decodificarGatilhos(input) }
        neverThrows("decodificarConfig", input) {
            conferirInvariantes(CodecFluxos.decodificarConfig(input, input) { SEMENTE }, input)
        }
    }

    /** Aninhamento fundo não pode estourar a pilha. */
    @Test(timeout = 120_000)
    fun deepNestingDoesNotOverflowTheStack() {
        for (fundo in listOf(1_000, 5_000, 20_000)) {
            val colchetes = "[".repeat(fundo) + "]".repeat(fundo)
            val chaves = "{\"a\":".repeat(fundo) + "1" + "}".repeat(fundo)
            val passos = """{"v":1,"flows":[{"id":"a","name":"X","steps":[${"{\"type\":\"ditar\",\"model\":".repeat(fundo)}1${"}".repeat(fundo)}]}]}"""
            for (entrada in listOf(colchetes, chaves, passos)) {
                neverThrows("decodificarFluxos", entrada) { CodecFluxos.decodificarFluxos(entrada) }
                neverThrows("decodificarGatilhos", entrada) { CodecFluxos.decodificarGatilhos(entrada) }
            }
        }
    }

    companion object {
        const val ITERATIONS = 5_000
        val SEMENTE = listOf(Fluxo("s-padrao", "Semente", padrao = "padrao", etapas = listOf(Etapa.Ditar(), Etapa.Falar())))
    }
}
