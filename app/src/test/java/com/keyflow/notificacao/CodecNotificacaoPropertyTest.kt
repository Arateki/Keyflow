package com.keyflow.notificacao

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Qualquer configuração válida volta igual do JSON (NTF-37), inclusive ações desconhecidas e fluxos. */
class CodecNotificacaoPropertyTest {

    private val acao: Arb<AcaoDaOrbe> = arbitrary { rs ->
        when (rs.random.nextInt(7)) {
            0 -> AcaoDaOrbe.Nenhuma
            1 -> AcaoDaOrbe.GravarEnviar
            2 -> AcaoDaOrbe.RetrairExpandirTeclado
            3 -> AcaoDaOrbe.LerTela
            4 -> AcaoDaOrbe.RodarFluxo("f" + rs.random.nextInt(1000))
            // um id que o parser desta versão não conhece (e que não colide com os conhecidos nem com `flow:`)
            else -> AcaoDaOrbe.Desconhecida("x" + rs.random.nextInt(1000) + "_novo")
        }
    }
    private val skin = Arb.element(CodecNotificacao.SKINS_CONHECIDAS.toList() + listOf("pack:a", "pack:neon-1a2b3c4d"))

    companion object {
        val config: Arb<ConfigNotificacao> = run {
            val t = CodecNotificacaoPropertyTest()
            arbitrary { rs ->
                ConfigNotificacao(
                    mostrar = rs.random.nextBoolean(),
                    permitirComTelaBloqueada = rs.random.nextBoolean(),
                    copiarDaOrbePrincipal = rs.random.nextBoolean(),
                    skin = t.skin.sample(rs).value,
                    acao1Toque = t.acao.sample(rs).value,
                    acao2Toques = t.acao.sample(rs).value,
                    acao3Toques = t.acao.sample(rs).value,
                )
            }
        }
    }

    @Test
    fun `decodificar de codificar devolve a mesma configuracao`() = runBlocking<Unit> {
        checkAll(3_000, config) { c ->
            assertEquals(c, CodecNotificacao.decodificar(CodecNotificacao.codificar(c)))
        }
    }

    @Test
    fun `codificar e idempotente`() = runBlocking<Unit> {
        checkAll(1_000, config) { c ->
            val j = CodecNotificacao.codificar(c)
            assertEquals(j, CodecNotificacao.codificar(CodecNotificacao.decodificar(j)))
        }
    }
}
