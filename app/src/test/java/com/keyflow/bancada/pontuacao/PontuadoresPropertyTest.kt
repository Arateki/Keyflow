package com.keyflow.bancada.pontuacao

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BPT-11: o que vale para QUALQUER saída. Nota sempre em [0, 1] e nunca uma exceção (o modelo escreve qualquer coisa); a saída idêntica ao
 * esperado vale 1; a saída vazia vale 0; as distâncias são simétricas e as taxas ficam nos limites.
 */
class PontuadoresPropertyTest {

    private val ditado = caso(esperado = "Oi, pessoal, tudo bem? Eu queria confirmar a reunião.")
    private val reescrita = caso(campos = arrayOf("fatosObrigatorios" to lista("reunião", "10h"), "maxPalavras" to 10, "proibidos" to lista("blz")))
    private val extracao = caso(
        esperado = obj("""{"nome":"Ana","idade":30}"""),
        campos = arrayOf("esquema" to obj("""{"nome":"string","idade":"integer"}""")),
    )
    private val acao = caso(esperado = obj("""{"nome":"app_status","args":{}}"""))
    private val recusa = caso(esperado = null)
    private val contexto = caso(esperado = "25 MB", campos = arrayOf("valor" to "25 MB", "distratores" to lista("52 MB")))
    private val pares = listOf(
        PontuadorPontuacao to ditado, PontuadorReescrita to reescrita, PontuadorJson to extracao,
        PontuadorAcoes to acao, PontuadorAcoes to recusa, PontuadorContexto to contexto,
    )

    private val palavra = Arb.string(1..8, Codepoint.az())
    private val texto = Arb.string(0..160)

    @Test
    fun everyScoreStaysInRangeForAnyOutput() = runBlocking<Unit> {
        checkAll(2_000, texto) { saida ->
            for ((pontuador, c) in pares) {
                val p = pontuador.pontuar(c, saida)
                assertTrue("nota ${p.nota} fora de [0,1] para «$saida»", p.nota in 0.0..1.0)
                assertTrue(p.criterios.values.all { it in 0.0..1.0 })
            }
        }
    }

    @Test
    fun hostileJsonShapedOutputNeverThrows() = runBlocking<Unit> {
        val pedaco = Arb.element(listOf("{", "}", "[", "]", "\"", ":", ",", "\\", "nome", "args", "null", "recusa", "```", " ", "1", "-"))
        checkAll(2_000, Arb.list(pedaco, 0..40)) { partes ->
            val saida = partes.joinToString("")
            for ((pontuador, c) in pares) assertTrue(pontuador.pontuar(c, saida).nota in 0.0..1.0)
        }
    }

    @Test
    fun theExpectedPunctuationOutputIsOne() = runBlocking<Unit> {
        checkAll(500, Arb.list(palavra, 1..12)) { palavras ->
            val esperado = palavras.mapIndexed { i, w -> if (i == 0) w.replaceFirstChar { it.uppercase() } else w }.joinToString(" ") + "."
            val c = caso(esperado = esperado)
            assertEquals(1.0, PontuadorPontuacao.pontuar(c, esperado).nota, 1e-9)
            assertEquals(0.0, PontuadorPontuacao.pontuar(c, "   ").nota, 0.0)
        }
    }

    @Test
    fun emptyOutputIsZeroForTheTestsThatHaveNoRefusal() {
        for ((pontuador, c) in pares.filter { it.second !== recusa }) assertEquals(0.0, pontuador.pontuar(c, "").nota, 0.0)
    }

    @Test
    fun werAndCerAreBoundedAndIdenticalIsPerfect() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(palavra, 0..10), Arb.list(palavra, 0..10)) { a, b ->
            assertEquals(1.0, Wer.nota(a, a), 0.0)
            val nota = Wer.nota(a, b)
            assertTrue(nota in 0.0..1.0)
            val cer = Cer.nota(a.joinToString(" "), b.joinToString(" "))
            assertTrue(cer in 0.0..1.0)
        }
    }

    @Test
    fun levenshteinIsSymmetricAndBoundedByTheLongerSequence() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(palavra, 0..8), Arb.list(palavra, 0..8)) { a, b ->
            val d = Levenshtein.distancia(a, b)
            assertEquals(d, Levenshtein.distancia(b, a))
            assertTrue(d <= maxOf(a.size, b.size))
            assertTrue(d >= kotlin.math.abs(a.size - b.size))
        }
    }

    @Test
    fun f1IsSymmetricAndBounded() = runBlocking<Unit> {
        checkAll(1_000, Arb.list(palavra, 0..8), Arb.list(palavra, 0..8)) { a, b ->
            val x = F1.multiconjunto(a, b)
            assertEquals(x, F1.multiconjunto(b, a), 1e-12)
            assertTrue(x in 0.0..1.0)
            assertEquals(1.0, F1.multiconjunto(a, a), 1e-12)
        }
    }

    @Test
    fun chrfIsBoundedAndIdenticalIsPerfect() = runBlocking<Unit> {
        checkAll(1_000, texto, texto) { a, b ->
            assertTrue(ChrF.nota(a, b) in 0.0..1.0)
            assertEquals(1.0, ChrF.nota(a, a), 1e-12)
        }
    }

    @Test
    fun normalizationIsIdempotent() = runBlocking<Unit> {
        checkAll(1_000, texto) { s ->
            val uma = Normalizacao.texto(s)
            assertEquals(uma, Normalizacao.texto(uma))
        }
    }
}
