package com.keyflow.computador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigracaoHostUnicoTest {
    private fun legado(host: String = "srv.example.org", porta: Int = 2200, user: String = "yan", tipo: String = "password", senha: String = "", pem: String = "") =
        HostUnicoLegado(host, porta, user, tipo, senha, pem)

    @Test fun hostVazioNaoMigraNada() {
        assertNull(MigracaoHostUnico.migrar(legado(host = "", senha = "x", pem = "y")))
        assertNull(MigracaoHostUnico.migrar(legado(host = "   ")))
    }

    @Test fun senhaViraHostServidorSshESegredoAParte() {
        val r = MigracaoHostUnico.migrar(legado(senha = "s3nh4-falsa"))!!
        val h = r.config.hosts.single()
        assertEquals("Servidor SSH", h.nome)
        assertEquals("srv.example.org", h.endereco)
        assertEquals(2200, h.porta)
        assertEquals("yan", h.usuario)
        assertEquals(AutenticacaoHost.Senha, h.autenticacao)
        assertTrue(h.lembrarSenha)
        assertEquals("s3nh4-falsa", r.segredos.senha)
        assertNull(r.segredos.chavePem)
        assertEquals(PoliticaAgente.PROIBIDO, h.agente)
    }

    @Test fun senhaNaoSalvaNaoLembra() {
        val r = MigracaoHostUnico.migrar(legado(senha = ""))!!
        assertEquals(AutenticacaoHost.Senha, r.config.hosts.single().autenticacao)
        assertEquals(false, r.config.hosts.single().lembrarSenha)
        assertNull(r.segredos.senha)
    }

    @Test fun chaveViraReferenciaESegredoAParte() {
        val r = MigracaoHostUnico.migrar(legado(tipo = "key", senha = "ignorada", pem = "PEM-FALSO"))!!
        assertEquals(AutenticacaoHost.Chave(MigracaoHostUnico.ID_CHAVE), r.config.hosts.single().autenticacao)
        assertEquals(MigracaoHostUnico.ID_CHAVE, r.chaveId)
        assertEquals("PEM-FALSO", r.segredos.chavePem)
        assertNull(r.segredos.senha)
    }

    @Test fun tipoChaveSemChaveViraPerguntar() {
        assertEquals(AutenticacaoHost.Perguntar, MigracaoHostUnico.migrar(legado(tipo = "key"))!!.config.hosts.single().autenticacao)
    }

    @Test fun idempotente() {
        val primeira = MigracaoHostUnico.migrar(legado(senha = "x"))!!
        assertNull(MigracaoHostUnico.migrar(legado(senha = "x"), existente = primeira.config))
        // Dados iguais dão o mesmo host (id determinístico).
        assertEquals(primeira.config, MigracaoHostUnico.migrar(legado(senha = "x"))!!.config)
    }

    @Test fun portaForaDaFaixaVira22EIpv6GanhaColchetes() {
        val h = MigracaoHostUnico.migrar(legado(host = "::1", porta = 0))!!.config.hosts.single()
        assertEquals("[::1]", h.endereco)
        assertEquals(22, h.porta)
    }

    @Test fun legadoInvalidoNaoMigra() = assertNull(MigracaoHostUnico.migrar(legado(host = "com espaço")))

    @Test fun resultadoNaoVazaNoToString() {
        val r = MigracaoHostUnico.migrar(legado(tipo = "key", pem = "PEM-FALSO-XYZ"))!!
        assertTrue(!r.toString().contains("PEM-FALSO") && !r.segredos.toString().contains("PEM-FALSO"))
        assertTrue(!legado(senha = "SENHA-XYZ").toString().contains("SENHA-XYZ"))
    }
}
