package com.keyflow.computador

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownHostsArquivoTest {
    private val ed = TesteSsh.servidor("ed25519")
    private val ec = TesteSsh.servidor("ecdsa-p256")
    private val rsa = TesteSsh.servidor("rsa3072")
    private val b64ed = TesteSsh.chave("ed25519.pub").trim().split(" ")[1]

    @Test fun escreveNoFormatoDoOpenSshPorta22SemColchetes() {
        val a = KnownHostsArquivo.ler(null).comChave("srv.example.org", 22, ed)
        assertEquals("srv.example.org ssh-ed25519 $b64ed\n", a.serializar())
    }

    @Test fun escreveColchetesEmPortaDiferente() {
        val a = KnownHostsArquivo.ler(null).comChave("srv.example.org", 2222, ed)
        assertEquals("[srv.example.org]:2222 ssh-ed25519 $b64ed\n", a.serializar())
    }

    @Test fun leLinhasDoOpenSsh() {
        val a = KnownHostsArquivo.ler("[srv.example.org]:2222 ssh-ed25519 $b64ed comentario\nsrv.example.org ssh-ed25519 $b64ed\n")
        assertEquals(listOf(ed), a.chavesConhecidas("srv.example.org", 2222))
        assertEquals(listOf(ed), a.chavesConhecidas("SRV.example.org", 22))
        assertEquals(emptyList<ChaveDoServidor>(), a.chavesConhecidas("srv.example.org", 2200))
    }

    @Test fun ipv6EmColchetesEPortaDiferente() {
        val a = KnownHostsArquivo.ler(null).comChave("[2001:db8::1]", 2222, ed).comChave("[2001:db8::1]", 22, ec)
        assertEquals("[2001:db8::1]:2222 ssh-ed25519 $b64ed\n2001:db8::1 ecdsa-sha2-nistp256 ${TesteSsh.chave("ecdsa-p256.pub").trim().split(" ")[1]}\n", a.serializar())
        assertEquals(listOf(ed), KnownHostsArquivo.ler(a.serializar()).chavesConhecidas("2001:db8::1", 2222))
    }

    @Test fun linhaMalformadaPreservaAsOutrasEFicaComoEstava() {
        val texto = "# comentario\n\nlixo sem chave\nsrv ssh-ed25519 !!!nao-base64\n@revoked srv ssh-ed25519 $b64ed\n|1|abc|def ssh-ed25519 $b64ed\nsrv.example.org ssh-ed25519 $b64ed\n*.example.org ssh-ed25519 $b64ed\n"
        val a = KnownHostsArquivo.ler(texto)
        assertEquals(listOf(ed), a.chavesConhecidas("srv.example.org", 22))
        assertEquals(emptyList<ChaveDoServidor>(), a.chavesConhecidas("srv", 22))
        assertEquals(texto, a.serializar())
    }

    @Test fun removerPorHostNaoTocaOsDemais() {
        val a = KnownHostsArquivo.ler(null).comChave("a.org", 22, ed).comChave("b.org", 22, ed).comChave("a.org", 2222, ec).comChave("a.org", 22, rsa)
        val sem = a.semHost("a.org", 22)
        assertEquals(emptyList<ChaveDoServidor>(), sem.chavesConhecidas("a.org", 22))
        assertEquals(listOf(ed), sem.chavesConhecidas("b.org", 22))
        assertEquals(listOf(ec), sem.chavesConhecidas("a.org", 2222))
    }

    @Test fun removerDeLinhaComListaDeHostsPreservaOOutro() {
        val a = KnownHostsArquivo.ler("a.org,b.org ssh-ed25519 $b64ed\n").semHost("a.org", 22)
        assertEquals("b.org ssh-ed25519 $b64ed\n", a.serializar())
    }

    @Test fun mesmoTipoSubstituiETiposDiferentesConvivem() {
        val outraEd = ChaveDoServidor("ssh-ed25519", ByteArray(32) { 7 })
        val a = KnownHostsArquivo.ler(null).comChave("a.org", 22, ed).comChave("a.org", 22, ec).comChave("a.org", 22, outraEd)
        assertEquals(setOf(ec, outraEd), a.chavesConhecidas("a.org", 22).toSet())
    }

    @Test fun terminacaoDeLinhaWindowsELida() {
        assertEquals(listOf(ed), KnownHostsArquivo.ler("srv ssh-ed25519 $b64ed\r\n").chavesConhecidas("srv", 22))
    }

    @Test fun arquivoVazioOuNuloNaoLanca() {
        assertTrue(KnownHostsArquivo.ler("").chavesConhecidas("x", 22).isEmpty())
        assertEquals("", KnownHostsArquivo.ler(null).serializar())
    }

    @Test fun repositorioEmMemoriaGravaELeDeVolta() {
        val r = RepositorioKnownHostsEmMemoria()
        r.confiar("srv", 22, ed)
        val recarregado = RepositorioKnownHostsEmMemoria(r.serializar())
        assertEquals(listOf(ed), recarregado.chavesConhecidas("srv", 22))
        recarregado.esquecer("srv", 22)
        assertEquals("", recarregado.serializar())
    }
}
