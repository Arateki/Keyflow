package com.keyflow.computador.sessao

import com.keyflow.computador.ssh.CausaSsh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconexaoTest {
    @Test fun backoffUmDoisQuatroOitoDezesseisETrinta() {
        assertEquals(listOf(1L, 2, 4, 8, 16, 30, 30, 30, 30, 30).map { it * 1000 }, (1..10).map { Reconexao.espera(it, 0)!! })
    }

    @Test fun decimaPrimeiraTentativaNaoExiste() = assertNull(Reconexao.espera(11, 0))

    @Test fun dezMinutosDesdeAQuedaAcabam() {
        assertEquals(30_000L, Reconexao.espera(7, 570_000))
        assertNull(Reconexao.espera(7, 571_000))
    }

    @Test fun tentativaInvalida() { assertNull(Reconexao.espera(0, 0)); assertNull(Reconexao.espera(-1, 0)) }

    @Test fun soReconectaPorRedeOuServidor() {
        for (c in listOf(CausaSsh.REDE, CausaSsh.TEMPO, CausaSsh.RECUSADA, CausaSsh.DESCONECTADO, CausaSsh.DNS)) assertTrue("$c", Reconexao.reconectavel(c))
        for (c in listOf(CausaSsh.AUTENTICACAO, CausaSsh.CHAVE_DO_SERVIDOR_MUDOU, CausaSsh.SERVIDOR_NAO_CONFIADO, CausaSsh.BIOMETRIA, CausaSsh.BIOMETRIA_INVALIDADA, CausaSsh.CHAVE_NAO_ACEITA, CausaSsh.SEM_ALGORITMO))
            assertFalse("$c", Reconexao.reconectavel(c))
    }
}
