package com.keyflow.notificacao

import com.keyflow.fuzz.FuzzInputs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** O JSON da notificação vem do DataStore ou de uma versão mais nova: o codec nunca lança e o que devolve é sempre válido. */
class CodecNotificacaoFuzzTest {

    private fun conferir(entrada: String) {
        val c = try {
            CodecNotificacao.decodificar(entrada)
        } catch (t: Throwable) {
            fail("decodificar lançou ${t.javaClass.simpleName} (${t.message?.take(100)}) com: ${entrada.take(300)}")
            return
        }
        assertEquals("skin inválida: ${c.skin}", true, CodecNotificacao.skinValida(c.skin))
        // o que sai pode ser regravado e relido sem perda
        assertEquals(c, CodecNotificacao.decodificar(CodecNotificacao.codificar(c)))
    }

    private val mutadas: Arb<String> = arbitrary { rs ->
        FuzzInputs.mutate(rs.random, CodecNotificacao.codificar(CodecNotificacaoPropertyTest.config.sample(rs).value))
    }
    private val geradas: Arb<String> = arbitrary { rs -> FuzzInputs.json(rs.random) }

    @Test(timeout = 120_000)
    fun `json mutado nunca lanca`() = runBlocking<Unit> { checkAll(5_000, mutadas) { conferir(it) } }

    @Test(timeout = 120_000)
    fun `json arbitrario nunca lanca`() = runBlocking<Unit> { checkAll(5_000, geradas) { conferir(it) } }
}
