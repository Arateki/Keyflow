package com.keyflow.computador

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ~10 mil entradas mutadas a partir de um JSON válido: o decodificador nunca lança e o que sai respeita as regras. */
class CodecHostsFuzzTest {
    private val base = CodecHosts.codificar(
        ConfigHosts(
            listOf(
                Host("a1b2c3d4", "dev", "dev.example.org", 22, "yan", AutenticacaoHost.Chave("k1")),
                Host("b1b2c3d4", "outro", "[::1]", 2222, "root", AutenticacaoHost.Senha, lembrarSenha = true, agente = PoliticaAgente.PERMITIDO),
            ),
        ),
    )

    private fun mutar(r: Random): String {
        val sb = StringBuilder(base)
        repeat(1 + r.nextInt(6)) {
            if (sb.isEmpty()) return@repeat
            val i = r.nextInt(sb.length)
            when (r.nextInt(6)) {
                0 -> sb.deleteCharAt(i)
                1 -> sb.insert(i, "{}[]\":,\\u0000nullтrue-1e999".random(r))
                2 -> sb.setCharAt(i, (r.nextInt(0x2FFF)).toChar())
                3 -> sb.setLength(i)
                4 -> sb.insert(i, base.substring(r.nextInt(base.length)))
                else -> sb.replace(i, minOf(sb.length, i + 1 + r.nextInt(8)), listOf("null", "9999999999999999999", "\"\"", "[]", "{}", "1.5", "true").random(r))
            }
        }
        return sb.toString()
    }

    private fun String.random(r: Random) = this[r.nextInt(length)].toString()
    private fun <T> List<T>.random(r: Random) = this[r.nextInt(size)]

    @Test fun nuncaLancaEOResultadoRespeitaAsRegras() {
        val r = Random(20261030)
        repeat(10_000) {
            val entrada = mutar(r)
            val config = CodecHosts.decodificar(entrada)
            assertTrue(config.hosts.size <= LimitesHost.MAX_HOSTS)
            assertEquals(config.hosts.size, config.hosts.map { it.id }.distinct().size)
            for (h in config.hosts) {
                assertTrue("host inválido sobreviveu: $h de $entrada", ValidadorHost.validar(h).none { it.campo != CampoHost.LIMITE })
                if (h.agentePodeUsar) assertTrue(RegraAgente.podeMarcar(h, credencialDisponivel = true) is PodeMarcar.Sim)
            }
        }
    }
}
