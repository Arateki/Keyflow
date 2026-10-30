package com.keyflow.computador.fixture

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshSession
import org.junit.Assert.*
import org.junit.Test

class SshFixtureServerTest {
    private fun cliente(servidor: SshFixtureServer, aceitar: Boolean = true) = SshClient("127.0.0.1", object : HostKeyVerifier {
        override suspend fun verify(key: PublicKey): Boolean {
            val real = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded))
            return aceitar && real == servidor.hostSha256
        }
    }, servidor.porta)

    private suspend fun lerAte(canal: SshSession, fim: ByteArray): ByteArray = withTimeout(15_000) {
        val saida = ByteArrayOutputStream()
        while (true) {
            saida.write(canal.stdout.receive())
            val bytes = saida.toByteArray()
            if (bytes.size >= fim.size && bytes.takeLast(fim.size).toByteArray().contentEquals(fim)) return@withTimeout bytes
        }
        @Suppress("UNREACHABLE_CODE") error("Sem resposta")
    }

    @Test fun senhaPromptGravacoesEcoPtyEnvERedimensionamento() = runTest {
        withContext(Dispatchers.IO) {
            SshFixtureServer().use { servidor ->
                val cliente = cliente(servidor)
                try {
                    assertEquals(ConnectResult.Success, cliente.connect())
                    assertEquals(AuthResult.Success, cliente.authenticatePassword("senha", SshFixtureServer.SENHA_FALSA))
                    val canal = checkNotNull(cliente.openSession())
                    try {
                        assertTrue(canal.requestPty("xterm-kitty", 80, 24, 640, 384))
                        assertTrue(canal.requestEnv("LANG", "pt_BR.UTF-8"))
                        assertTrue(canal.requestShell())
                        assertArrayEquals(servidor.abertura, lerAte(canal, SshFixtureServer.PROMPT))
                        assertEquals(SshFixtureServer.Pty("xterm-kitty", 80, 24, 640, 384), servidor.ptys.poll(5, TimeUnit.SECONDS))
                        assertEquals("LANG" to "pt_BR.UTF-8", servidor.ambientes.poll(5, TimeUnit.SECONDS))
                        assertTrue(canal.resizeTerminal(120, 40, 960, 640))
                        assertEquals(SshFixtureServer.Tamanho(120, 40, 960, 640), servidor.tamanhos.poll(5, TimeUnit.SECONDS))
                        val texto = "ação falsa sem Enter".toByteArray()
                        canal.write(texto)
                        assertArrayEquals(texto, lerAte(canal, texto))
                    } finally { canal.close() }
                } finally { cliente.disconnect() }
            }
        }
    }

    @Test fun chaveEd25519FalsaAutentica() = runTest {
        withContext(Dispatchers.IO) {
            SshFixtureServer().use { servidor ->
                val cliente = cliente(servidor)
                try {
                    assertEquals(ConnectResult.Success, cliente.connect())
                    assertEquals(AuthResult.Success, cliente.authenticatePublicKey("teste", servidor.chaveUsuarioPem))
                    val canal = checkNotNull(cliente.openSession())
                    try {
                        assertTrue(canal.requestShell())
                        assertArrayEquals(servidor.abertura, lerAte(canal, SshFixtureServer.PROMPT))
                    } finally { canal.close() }
                } finally { cliente.disconnect() }
            }
        }
    }

    @Test fun senhaErradaEUsuarioIncorretoSaoRecusados() = runTest {
        withContext(Dispatchers.IO) {
            SshFixtureServer().use { servidor ->
                val cliente = cliente(servidor)
                try {
                    assertEquals(ConnectResult.Success, cliente.connect())
                    assertTrue(cliente.authenticatePassword("senha", "errada-apenas-teste") is AuthResult.Failure)
                } finally { cliente.disconnect() }
                val outro = cliente(servidor)
                try {
                    assertEquals(ConnectResult.Success, outro.connect())
                    val resultado = outro.authenticatePassword("outro", SshFixtureServer.SENHA_FALSA)
                    assertNotEquals(AuthResult.Success, resultado)
                } finally { outro.disconnect() }
            }
        }
    }

    @Test fun hostNaoConfiavelNaoConecta() = runTest {
        withContext(Dispatchers.IO) {
            SshFixtureServer().use { servidor ->
                val cliente = cliente(servidor, aceitar = false)
                try { assertFalse(cliente.connect() is ConnectResult.Success) } finally { cliente.disconnect() }
            }
        }
    }

    @Test fun cairNoMeioFechaOTransporteReal() = runTest {
        withContext(Dispatchers.IO) {
            SshFixtureServer(cairNoMeio = true).use { servidor ->
                val cliente = cliente(servidor)
                try {
                    assertEquals(ConnectResult.Success, cliente.connect())
                    assertEquals(AuthResult.Success, cliente.authenticatePassword("senha", SshFixtureServer.SENHA_FALSA))
                    val canal = checkNotNull(cliente.openSession())
                    val queda = async(start = CoroutineStart.UNDISPATCHED) { cliente.disconnectedFlow.first() }
                    canal.requestShell()
                    withTimeout(15_000) { queda.await() }
                    assertTrue(servidor.quedas.poll(5, TimeUnit.SECONDS) == true)
                } finally { cliente.disconnect() }
            }
        }
    }

    @Test fun hostFixoGravacoesPequenasComHashesEParadaLimpa() {
        val primeira = SshFixtureServer().use { servidor ->
            val loader = javaClass.classLoader!!
            val manifesto = loader.getResourceAsStream("computador/gravacoes/SHA256SUMS")!!.bufferedReader().use { it.readLines() }
            manifesto.forEach { linha ->
                val (hash, arquivo) = linha.split("  ", limit = 2)
                val bytes = loader.getResourceAsStream("computador/gravacoes/$arquivo")!!.use { it.readBytes() }
                assertTrue(arquivo, bytes.size <= 65_536)
                assertEquals(arquivo, hash, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
            servidor.hostSha256
        }
        SshFixtureServer().use { assertEquals(primeira, it.hostSha256) }
    }
}
