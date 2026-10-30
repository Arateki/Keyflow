package com.keyflow.computador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliticaDeConfiancaTest {
    private val ed = TesteSsh.servidor("ed25519")
    private val ec = TesteSsh.servidor("ecdsa-p256")
    private val rsa = TesteSsh.servidor("rsa3072")

    private fun repo() = RepositorioKnownHostsEmMemoria()

    @Test fun chaveNovaTrazAImpressaoDoSshKeygen() {
        val v = PoliticaDeConfianca.avaliar("srv.example.org", 22, ec, repo())
        assertTrue(v is VereditoDeConfianca.Nova)
        assertEquals("SHA256:poVxiS+g4bpceFXLf/2CqFASfV+yVyyQYeNpqZ5ljr0", (v as VereditoDeConfianca.Nova).impressao.texto)
        assertEquals(emptyList<String>(), v.outrosTipos)
    }

    @Test fun mesmaChaveEConhecida() {
        val r = repo().also { it.confiar("srv.example.org", 22, ed) }
        assertEquals(VereditoDeConfianca.Conhecida, PoliticaDeConfianca.avaliar("srv.example.org", 22, ed, r))
    }

    @Test fun chaveDiferenteDoMesmoTipoNoMesmoHostPortaEMudou() {
        val outraEd = ChaveDoServidor("ssh-ed25519", TesteSsh.servidor("ed25519").blob.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() })
        val r = repo().also { it.confiar("srv.example.org", 22, ed) }
        val v = PoliticaDeConfianca.avaliar("srv.example.org", 22, outraEd, r)
        assertEquals(VereditoDeConfianca.Mudou(ed.impressao, outraEd.impressao), v)
    }

    @Test fun tiposDiferentesConvivem() {
        val r = repo().also { it.confiar("srv.example.org", 22, ed) }
        val v = PoliticaDeConfianca.avaliar("srv.example.org", 22, rsa, r)
        assertEquals(VereditoDeConfianca.Nova(rsa.impressao, listOf("ssh-ed25519")), v)
        r.confiar("srv.example.org", 22, rsa)
        assertEquals(VereditoDeConfianca.Conhecida, PoliticaDeConfianca.avaliar("srv.example.org", 22, ed, r))
        assertEquals(VereditoDeConfianca.Conhecida, PoliticaDeConfianca.avaliar("srv.example.org", 22, rsa, r))
    }

    @Test fun portaDiferenteEOutroHost() {
        val r = repo().also { it.confiar("srv.example.org", 22, ed) }
        assertTrue(PoliticaDeConfianca.avaliar("srv.example.org", 2222, ed, r) is VereditoDeConfianca.Nova)
    }

    @Test fun nomeDeHostNaoDistingueMaiusculas() {
        val r = repo().also { it.confiar("Srv.Example.ORG", 22, ed) }
        assertEquals(VereditoDeConfianca.Conhecida, PoliticaDeConfianca.avaliar("srv.example.org", 22, ed, r))
    }

    @Test fun rotacaoSemChaveConhecidaAutenticadaERecusada() {
        val r = repo().also { it.confiar("srv.example.org", 22, ed) }
        assertEquals(ResultadoRotacao.Recusada, AtualizadorDeChaves.aplicar("srv.example.org", 22, rsa, listOf(rsa, ec), r))
        assertEquals(1, r.chavesConhecidas("srv.example.org", 22).size)
    }

    @Test fun rotacaoComChaveConhecidaAcrescentaSoTiposNovos() {
        val r = repo().also { it.confiar("srv.example.org", 22, ed) }
        val outraEd = ChaveDoServidor("ssh-ed25519", ByteArray(8) { 9 })
        val res = AtualizadorDeChaves.aplicar("srv.example.org", 22, ed, listOf(ed, outraEd, ec), r)
        assertEquals(ResultadoRotacao.Aceita(listOf(ec)), res)
        // A chave nova do tipo que já era conhecido NÃO foi aceita em silêncio.
        assertEquals(ed, r.chavesConhecidas("srv.example.org", 22).single { it.algoritmo == "ssh-ed25519" })
    }
}
