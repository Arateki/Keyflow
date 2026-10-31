package com.keyflow.computador.fixture

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.apache.sshd.common.channel.Channel
import org.apache.sshd.common.channel.PtyMode
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command

/** Servidor isolado em loopback: só reproduz dados falsos; nunca executa comandos do cliente. */
class SshFixtureServer(private val cairNoMeio: Boolean = false) : AutoCloseable {
    data class Pty(val term: String, val colunas: Int, val linhas: Int, val largura: Int, val altura: Int)
    data class Tamanho(val colunas: Int, val linhas: Int, val largura: Int, val altura: Int)
    val ptys = LinkedBlockingQueue<Pty>()
    val tamanhos = LinkedBlockingQueue<Tamanho>()
    val ambientes = LinkedBlockingQueue<Pair<String, String>>()
    val quedas = LinkedBlockingQueue<Boolean>()
    /** Quantas vezes o servidor viu um pedido de senha ou de chave (um cliente que cancela antes de autenticar deixa isto em zero). */
    val tentativasDeLogin = java.util.concurrent.atomic.AtomicInteger()
    private val executor = Executors.newCachedThreadPool { tarefa -> Thread(tarefa, "ssh-fixture-eco").apply { isDaemon = true } }
    private val chaveHost = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom.getInstance("SHA1PRNG").apply { setSeed("KeyFlow host SOMENTE TESTE 2026".toByteArray()) })
    }.generateKeyPair()
    private val usuario: KeyPair = run {
        val parametros = EdDSANamedCurveTable.getByName("Ed25519")
        val privada = EdDSAPrivateKey(EdDSAPrivateKeySpec(ByteArray(32) { (it + 17).toByte() }, parametros))
        KeyPair(EdDSAPublicKey(EdDSAPublicKeySpec(privada.getAbyte(), parametros)), privada)
    }
    // Apenas chave falsa gerada em memória, nunca ~/.ssh nem arquivo de credenciais. Formato OpenSSH, o que o cbssh lê.
    val chaveUsuarioPem: String get() = openSshEd25519(ByteArray(32) { (it + 17).toByte() }, (usuario.public as EdDSAPublicKey).abyte)
    val hostSha256: String = run {
        val publica = Base64.getDecoder().decode(PublicKeyEntry.toString(chaveHost.public).split(' ')[1])
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(publica))
    }
    val abertura: ByteArray = GRAVACOES.fold(ByteArray(0)) { bytes, nome ->
        bytes + checkNotNull(javaClass.classLoader!!.getResourceAsStream("computador/gravacoes/$nome")) { "Gravação ausente: $nome" }.use { it.readBytes() }
    } + "\r\nhttps://fixture.invalid/pronto\r\n".toByteArray() + PROMPT
    private val servidor = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(this@SshFixtureServer.chaveHost)
        passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { nome, senha, _ -> tentativasDeLogin.incrementAndGet(); nome == "senha" && senha == SENHA_FALSA }
        publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { nome, chave, _ -> tentativasDeLogin.incrementAndGet(); nome == "teste" && chave.encoded.contentEquals(usuario.public.encoded) }
        channelFactories = listOf(object : org.apache.sshd.common.channel.ChannelFactory {
            override fun getName() = "session"
            override fun createChannel(session: org.apache.sshd.common.session.Session): Channel = object : ChannelSession() {
                override fun handlePtyReqParsed(term: String, colunas: Int, linhas: Int, largura: Int, altura: Int, modos: MutableMap<PtyMode, Int>): org.apache.sshd.common.channel.RequestHandler.Result {
                    ptys.add(Pty(term, colunas, linhas, largura, altura))
                    return super.handlePtyReqParsed(term, colunas, linhas, largura, altura, modos)
                }
                override fun handleWindowChangeParsed(colunas: Int, linhas: Int, largura: Int, altura: Int): org.apache.sshd.common.channel.RequestHandler.Result {
                    tamanhos.add(Tamanho(colunas, linhas, largura, altura))
                    return super.handleWindowChangeParsed(colunas, linhas, largura, altura)
                }
                override fun handleEnvParsed(nome: String, valor: String): org.apache.sshd.common.channel.RequestHandler.Result {
                    ambientes.add(nome to valor)
                    return super.handleEnvParsed(nome, valor)
                }
            }
        })
        shellFactory = org.apache.sshd.server.shell.ShellFactory { EcoGravado() }
        start()
    }
    val porta: Int get() = servidor.port
    val sessoesAbertas: Int get() = servidor.activeSessions.size

    private inner class EcoGravado : Command {
        private lateinit var entrada: InputStream
        private lateinit var saida: OutputStream
        private lateinit var fim: ExitCallback
        override fun setInputStream(input: InputStream) { entrada = input }
        override fun setOutputStream(output: OutputStream) { saida = output }
        override fun setErrorStream(error: OutputStream) = Unit
        override fun setExitCallback(callback: ExitCallback) { fim = callback }
        override fun start(channel: ChannelSession, env: Environment) {
            executor.submit {
                try {
                    if (cairNoMeio) {
                        saida.write("parcial-apenas-teste".toByteArray())
                        saida.flush()
                        quedas.add(true)
                        channel.session.close(true)
                    } else {
                        saida.write(abertura)
                        saida.flush()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val tamanho = entrada.read(buffer)
                            if (tamanho < 0) break
                            saida.write(buffer, 0, tamanho)
                            saida.flush()
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Fechar o cliente interrompe a leitura do eco.
                } finally { fim.onExit(0) }
            }
        }
        override fun destroy(channel: ChannelSession) { entrada.close() }
    }

    override fun close() {
        try { servidor.stop(true) } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Thread da fixture não terminou" }
        }
    }

    companion object {
        private fun campo(bytes: ByteArray): ByteArray =
            java.nio.ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()

        /** Chave privada Ed25519 sem senha no formato `openssh-key-v1`, montada à mão para o teste. */
        fun openSshEd25519(semente: ByteArray, publica: ByteArray): String {
            val tipo = "ssh-ed25519".toByteArray()
            val blobPublico = campo(tipo) + campo(publica)
            val verificacao = byteArrayOf(1, 2, 3, 4)
            var privado = verificacao + verificacao + campo(tipo) + campo(publica) + campo(semente + publica) + campo("fixture".toByteArray())
            var preenchimento = 1
            while (privado.size % 8 != 0) privado += (preenchimento++).toByte()
            val corpo = "openssh-key-v1".toByteArray() + 0.toByte() + campo("none".toByteArray()) + campo("none".toByteArray()) +
                campo(ByteArray(0)) + byteArrayOf(0, 0, 0, 1) + campo(blobPublico) + campo(privado)
            return "-----BEGIN OPENSSH PRIVATE KEY-----\n" + Base64.getMimeEncoder(70, byteArrayOf(10)).encodeToString(corpo) +
                "\n-----END OPENSSH PRIVATE KEY-----\n"
        }

        const val SENHA_FALSA = "senha-falsa-keyflow"
        val PROMPT = "fixture$ ".toByteArray()
        val GRAVACOES = listOf("texto-cores.bin", "kitty.bin", "iterm2.bin", "img2sixel.bin", "chafa.bin", "placeholders-kitty.bin", "herdr.bin")
    }
}
