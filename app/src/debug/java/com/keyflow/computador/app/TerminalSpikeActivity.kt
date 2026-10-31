package com.keyflow.computador.app

import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.terminal.InlineImageLimits
import org.connectbot.terminal.InlineImages
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/** Bancada descartável: somente debug, 80×24 e SSH de loopback com credencial falsa. */
class TerminalSpikeActivity : ComponentActivity() {
    data class Saida(val bytes: ByteArray, val dentroDoWriteInput: Boolean)

    lateinit var emulador: TerminalEmulator
        private set
    val saidas = LinkedBlockingQueue<Saida>()
    val dentroDoWriteInput = AtomicBoolean(false)
    @Volatile var erroDeConexao: Throwable? = null
        private set
    @Volatile var sshAberto = false
        private set
    private val enviados = Channel<ByteArray>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        emulador = TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            maxScrollbackLines = 5_000,
            inlineImages = InlineImages.On(InlineImageLimits(decodedBytes = 16 * 1024 * 1024, encodedBytes = 8 * 1024 * 1024)),
            onKeyboardInput = {
                saidas.add(Saida(it.copyOf(), dentroDoWriteInput.get()))
                enviados.trySend(it.copyOf())
            },
        )
        setContent {
            MaterialTheme {
                Terminal(
                    terminalEmulator = emulador,
                    modifier = Modifier.fillMaxSize(),
                    forcedSize = 24 to 80,
                    initialFontSize = 6.sp,
                    minFontSize = 6.sp,
                    keyboardEnabled = true,
                    showSoftKeyboard = intent.getBooleanExtra("mostrar_ime", false),
                )
            }
        }
        val porta = intent.getIntExtra("ssh_port", 0)
        if (porta > 0) conectarFixture(porta)
    }

    fun alimentar(bytes: ByteArray) {
        dentroDoWriteInput.set(true)
        try { emulador.writeInput(bytes) } finally { dentroDoWriteInput.set(false) }
    }

    private fun conectarFixture(porta: Int) {
        require(porta in 1..65535)
        val esperada = requireNotNull(intent.getStringExtra("host_sha256"))
        val verificador = object : HostKeyVerifier {
            override suspend fun verify(key: PublicKey): Boolean {
                val hash = MessageDigest.getInstance("SHA-256").digest(key.encoded)
                return "SHA256:" + Base64.encodeToString(hash, Base64.NO_WRAP or Base64.NO_PADDING) == esperada
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val cliente = SshClient("127.0.0.1", verificador, porta)
            try {
                check(cliente.connect() is ConnectResult.Success)
                check(cliente.authenticatePassword("senha", SENHA_FALSA) is AuthResult.Success)
                val canal = checkNotNull(cliente.openSession())
                check(canal.requestPty("xterm-kitty", 80, 24, 640, 384))
                check(canal.requestShell())
                sshAberto = true
                val escritor = launch { for (bytes in enviados) canal.write(bytes) }
                try {
                    for (bytes in canal.stdout) withContext(Dispatchers.Main.immediate) { alimentar(bytes) }
                } finally {
                    escritor.cancel()
                    canal.close()
                }
            } catch (cancelado: CancellationException) {
                throw cancelado
            } catch (erro: Exception) {
                erroDeConexao = erro
            } finally {
                sshAberto = false
                withContext(NonCancellable) { cliente.disconnect() }
            }
        }
    }

    override fun onDestroy() {
        enviados.close()
        emulador.close()
        super.onDestroy()
    }

    companion object { const val SENHA_FALSA = "senha-falsa-keyflow" }
}
