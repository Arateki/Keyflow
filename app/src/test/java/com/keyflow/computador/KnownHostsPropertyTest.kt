package com.keyflow.computador

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class KnownHostsPropertyTest {
    private val hosts = Arb.element("a.org", "b.example.com", "10.0.0.1", "[2001:db8::1]", "Servidor-X.local")
    private val portas = Arb.element(22, 2222, 22022, 65535)
    private val algs = Arb.element("ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa")
    private val entrada = Arb.bind(hosts, portas, algs, Arb.byteArray(Arb.int(4..64), Arb.byte())) { h, p, a, bytes ->
        (h to p) to ChaveDoServidor(a, bytes)
    }

    @Test fun escreverELerPreservaAsChaves() = runBlocking<Unit> {
        checkAll(300, Arb.list(entrada, 0..15)) { itens ->
            var arq = KnownHostsArquivo.ler(null)
            for ((hp, chave) in itens) arq = arq.comChave(hp.first, hp.second, chave)
            val relido = KnownHostsArquivo.ler(arq.serializar())
            for ((hp, _) in itens) assertEquals(arq.chavesConhecidas(hp.first, hp.second).toSet(), relido.chavesConhecidas(hp.first, hp.second).toSet())
            assertEquals(arq.serializar(), relido.serializar())
        }
    }

    @Test fun lixoNuncaLancaEPreservaOTexto() {
        val r = Random(1030)
        val pedacos = listOf("a.org ssh-ed25519 AAAA\n", "[x]:99 ssh-rsa Zm9v\n", "\n", "# c\n", "@x y z\n", "|1|a ssh-rsa Zm9v\n", "\u0000\n", "a,b,c ssh-ed25519 Zm9v extra palavras\n")
        repeat(2000) {
            val t = buildString { repeat(r.nextInt(8)) { append(pedacos[r.nextInt(pedacos.size)]) }; if (r.nextBoolean()) append("sem fim") }
            val a = KnownHostsArquivo.ler(t)
            a.chavesConhecidas("a.org", 22)
            assertEquals(if (t.isNotEmpty() && !t.endsWith("\n")) t + "\n" else t, a.serializar())
        }
    }
}
