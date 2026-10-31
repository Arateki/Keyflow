package com.keyflow.computador.ssh

import com.keyflow.computador.AutenticacaoHost
import com.keyflow.computador.AtualizadorDeChaves
import com.keyflow.computador.ChaveDoServidor
import com.keyflow.computador.PoliticaDeConfianca
import com.keyflow.computador.RepositorioDeConfianca
import com.keyflow.computador.VereditoDeConfianca
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.AuthHandler
import org.connectbot.sshlib.AuthPublicKey
import org.connectbot.sshlib.AuthResult
import org.connectbot.sshlib.ConnectResult
import org.connectbot.sshlib.HostKeyVerifier
import org.connectbot.sshlib.KeyboardInteractiveCallback
import org.connectbot.sshlib.PingResult
import org.connectbot.sshlib.PublicKey
import org.connectbot.sshlib.SshClient
import org.connectbot.sshlib.SshClientConfig
import org.connectbot.sshlib.SshSession
import org.connectbot.sshlib.transport.KtorTcpTransportFactory
import org.connectbot.sshlib.transport.TransportFactory
import kotlinx.coroutines.withTimeout

/**
 * O adaptador do cbssh: o **único** arquivo de `ssh/` que o importa (CMP-17). Um [SshClient] por conexão, política criptográfica padrão
 * da biblioteca (nada de algoritmo legado), verificação do servidor pela [PoliticaDeConfianca] (suspende até o usuário decidir),
 * autenticação por [AuthHandler] ligado ao `Assinador`/senha/código, e erros traduzidos para [CausaSsh] sem ler mensagem de exceção.
 *
 * Dois limites separados (tabela de erros): o **TCP** tem [tempoTcpMs] (15 s, no próprio `TransportFactory`) e a **troca de chaves** tem [tempoTrocaDeChavesMs]
 * (30 s). O `handshakeTimeoutMs` do cbssh fica desligado (0) porque ele contaria o tempo em que o usuário decide a impressão digital; quem vigia a troca é
 * o relógio daqui, que **para de contar enquanto o usuário decide**.
 */
class ConexaoCbssh(
    private val confianca: RepositorioDeConfianca,
    private val interacao: InteracaoComUsuario,
    private val escopo: CoroutineScope,
    private val tempoTcpMs: Long = 15_000,
    private val tempoTrocaDeChavesMs: Long = 30_000,
    private val intervaloVigiaMs: Long = 30_000,
    private val agoraMs: () -> Long = System::currentTimeMillis,
    /** Gancho de teste: ajusta o `SshClientConfig` (por exemplo, o transporte). Em produção não há. */
    private val ajustar: (SshClientConfig.Builder) -> Unit = {},
) : ConexaoSsh {
    private val _eventos = MutableSharedFlow<EventoConexao>(replay = 1, extraBufferCapacity = 64)
    override val eventos: SharedFlow<EventoConexao> = _eventos

    private val canalSaida = Channel<ByteArray>(Channel.UNLIMITED)
    override val saida: Flow<ByteArray> = canalSaida.receiveAsFlow()

    @Volatile private var cliente: SshClient? = null
    @Volatile private var canal: SshSession? = null
    private var tarefas: List<Job> = emptyList()
    private val fechada = AtomicBoolean(false)

    private sealed interface Prep
    private class Preparada(val cliente: SshClient, val algoritmo: String, val chave: ChaveDoServidor, val metodo: String) : Prep
    private class Falha(val causa: CausaSsh) : Prep

    override suspend fun conectar(destino: DestinoSsh, pty: PtyInfo): ResultadoConexao {
        emitir(EventoConexao.Conectando)
        val preparada = when (val r = preparar(destino)) {
            is Falha -> return falhou(r.causa)
            is Preparada -> r
        }
        val c = preparada.cliente
        try {
            val sessao = c.openSession() ?: return falhar(c, CausaSsh.PROTOCOLO)
            canal = sessao
            if (!sessao.requestPty(pty.termo, pty.colunas, pty.linhas, pty.largura, pty.altura)) return falhar(c, CausaSsh.PROTOCOLO)
            // Melhor esforço: muitos servidores não aceitam `env` sem AcceptEnv.
            runCatching { sessao.requestEnv("COLORTERM", "truecolor") }
            if (!sessao.requestShell()) return falhar(c, CausaSsh.PROTOCOLO)
            iniciarLeitura(c, sessao)
            emitir(EventoConexao.Aberta)
            return ResultadoConexao.Aberta
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { c.disconnect() } }
            throw e
        } catch (e: Exception) {
            return falhar(c, MapaDeErros.causaDe(e))
        }
    }

    override suspend fun testar(destino: DestinoSsh): ResultadoTeste {
        val inicio = agoraMs()
        return when (val r = preparar(destino)) {
            is Falha -> ResultadoTeste.Falhou(r.causa)
            is Preparada -> {
                val ms = agoraMs() - inicio
                // Sem shell: só fecha.
                withContext(NonCancellable) { runCatching { r.cliente.disconnect() } }
                ResultadoTeste.Ok(r.algoritmo, r.chave.impressao, r.metodo, ms)
            }
        }
    }

    override suspend fun escrever(bytes: ByteArray) {
        canal?.write(bytes)
    }

    override suspend fun redimensionar(pty: PtyInfo) {
        canal?.resizeTerminal(pty.colunas, pty.linhas, pty.largura, pty.altura)
    }

    override suspend fun fechar() {
        fechada.set(true)
        tarefas.forEach { it.cancel() }
        withContext(NonCancellable) {
            runCatching { canal?.close() }
            runCatching { cliente?.disconnect() }
        }
        canalSaida.close()
    }

    // ---- internos -----------------------------------------------------------------------------------------------------------------

    private fun emitir(e: EventoConexao) { _eventos.tryEmit(e) }

    private fun falhou(causa: CausaSsh): ResultadoConexao {
        emitir(EventoConexao.Caiu(causa))
        return ResultadoConexao.Falhou(causa)
    }

    private suspend fun falhar(c: SshClient, causa: CausaSsh): ResultadoConexao {
        withContext(NonCancellable) { runCatching { c.disconnect() } }
        return falhou(causa)
    }

    private suspend fun preparar(destino: DestinoSsh): Prep {
        val h = destino.host
        val endereco = h.endereco.removePrefix("[").removeSuffix("]")
        val estado = EstadoDaConexao()
        val verificador = object : HostKeyVerifier {
            override suspend fun verify(key: PublicKey): Boolean {
                val chave = ChaveDoServidor(key.type, key.encoded)
                estado.chaveDoServidor = chave
                return when (val v = PoliticaDeConfianca.avaliar(endereco, h.porta, chave, confianca)) {
                    VereditoDeConfianca.Conhecida -> true
                    is VereditoDeConfianca.Nova -> {
                        if (!destino.confiarSozinho) { estado.causa = CausaSsh.SERVIDOR_PEDE_CONFIRMACAO; return false }
                        emitir(EventoConexao.AguardandoServidor(key.type, v.impressao))
                        estado.aguardandoUsuario.set(true)
                        val ok = try { interacao.confirmarServidor(endereco, h.porta, key.type, v.impressao) } finally { estado.aguardandoUsuario.set(false) }
                        if (ok) { confianca.confiar(endereco, h.porta, chave); true } else { estado.causa = CausaSsh.SERVIDOR_NAO_CONFIADO; false }
                    }
                    is VereditoDeConfianca.Mudou -> {
                        estado.causa = CausaSsh.CHAVE_DO_SERVIDOR_MUDOU
                        emitir(EventoConexao.ServidorMudou(v.antiga, v.nova))
                        false
                    }
                }
            }

            // Rotação anunciada pelo servidor: só grava quando a chave que autenticou o servidor já era conhecida (CMP-19) e a sessão é do usuário.
            override suspend fun addKeys(keys: List<PublicKey>) {
                val atual = estado.chaveDoServidor ?: return
                if (!destino.confiarSozinho) return
                AtualizadorDeChaves.aplicar(endereco, h.porta, atual, keys.map { ChaveDoServidor(it.type, it.encoded) }, confianca)
            }
        }
        val config = SshClientConfig {
            this.host = endereco
            this.port = h.porta
            this.hostKeyVerifier = verificador
            this.keepAliveIntervalMs = intervaloVigiaMs
            // Com senha (ou "perguntar"), o cbssh tenta `password` em vez de `keyboard-interactive` quando o servidor oferece os dois.
            this.preferPasswordAuth = h.autenticacao !is AutenticacaoHost.Chave
            this.handshakeTimeoutMs = 0
            ajustar(this)
            // O limite do TCP vai por cima do transporte escolhido (o padrão do cbssh, ou o do teste): conectar o socket tem 15 s.
            val base = this.transportFactory ?: KtorTcpTransportFactory(endereco, h.porta, this.ipVersion)
            this.transportFactory = TransportFactory { withTimeout(tempoTcpMs) { base.create() } }
        }
        val c = SshClient(config)
        val resultado = try {
            conectarComLimite(c, estado)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { c.disconnect() } }
            throw e
        }
        when (resultado) {
            is ConnectResult.Success -> Unit
            is ConnectResult.HostKeyRejected -> return Falha(estado.causa ?: CausaSsh.SERVIDOR_NAO_CONFIADO)
            is ConnectResult.AlgorithmMismatch -> return Falha(CausaSsh.SEM_ALGORITMO)
            is ConnectResult.TransportError -> return Falha(if (estado.estourou) CausaSsh.TEMPO else MapaDeErros.causaDe(resultado.cause))
            is ConnectResult.ProtocolError -> return Falha(if (estado.estourou) CausaSsh.TEMPO else resultado.cause?.let { MapaDeErros.causaDe(it) } ?: CausaSsh.PROTOCOLO)
        }
        if (estado.estourou) { withContext(NonCancellable) { runCatching { c.disconnect() } }; return Falha(CausaSsh.TEMPO) }
        emitir(EventoConexao.Autenticando)
        val auth = autenticador(destino, estado)
        val r = try { c.authenticate(h.usuario, auth) } catch (e: CancellationException) { withContext(NonCancellable) { runCatching { c.disconnect() } }; throw e }
        if (r !is AuthResult.Success) {
            withContext(NonCancellable) { runCatching { c.disconnect() } }
            return Falha(
                when {
                    estado.biometriaInvalidada -> CausaSsh.BIOMETRIA_INVALIDADA
                    estado.biometriaCancelada -> CausaSsh.BIOMETRIA
                    r is AuthResult.Error -> r.cause?.let { MapaDeErros.causaDe(it) } ?: CausaSsh.PROTOCOLO
                    estado.ofereceuChave && estado.metodoSemChave -> CausaSsh.AUTENTICACAO
                    estado.ofereceuChave -> CausaSsh.CHAVE_NAO_ACEITA
                    else -> CausaSsh.AUTENTICACAO
                },
            )
        }
        cliente = c
        return Preparada(c, estado.chaveDoServidor?.algoritmo ?: "", estado.chaveDoServidor ?: ChaveDoServidor("", ByteArray(0)), estado.metodoFinal ?: "")
    }

    /** `connect()` com os limites de TCP e de troca de chaves que **não conta** o tempo em que o usuário decide a impressão digital. */
    private suspend fun conectarComLimite(c: SshClient, estado: EstadoDaConexao): ConnectResult = coroutineScope {
        val tarefa = async { c.connect() }
        val relogio = launch {
            // O relógio de 45 s (TCP + troca de chaves) só conta enquanto o usuário **não** está decidindo a impressão digital.
            val passo = 250L
            var contado = 0L
            while (isActive) {
                delay(passo)
                if (!estado.aguardandoUsuario.get()) contado += passo
                if (contado >= tempoTcpMs + tempoTrocaDeChavesMs) { estado.estourou = true; tarefa.cancel(); return@launch }
            }
        }
        try {
            tarefa.await()
        } catch (e: CancellationException) {
            // Estouro do relógio da troca de chaves, ou do limite do TCP (que o `withTimeout` do transporte lança como cancelamento): é tempo, não cancelamento de fora.
            if (estado.estourou || (e is kotlinx.coroutines.TimeoutCancellationException && currentCoroutineContext().isActive)) ConnectResult.TransportError(java.net.SocketTimeoutException()) else throw e
        } finally {
            relogio.cancel()
        }
    }

    private class EstadoDaConexao {
        @Volatile var causa: CausaSsh? = null
        @Volatile var chaveDoServidor: ChaveDoServidor? = null
        @Volatile var estourou = false
        @Volatile var ofereceuChave = false
        @Volatile var metodoSemChave = false
        @Volatile var biometriaCancelada = false
        @Volatile var biometriaInvalidada = false
        @Volatile var metodoFinal: String? = null
        val aguardandoUsuario = AtomicBoolean(false)
    }

    private fun autenticador(destino: DestinoSsh, estado: EstadoDaConexao) = object : AuthHandler {
        private val comChave = destino.host.autenticacao is AutenticacaoHost.Chave && destino.assinador != null

        override suspend fun onPublicKeysNeeded(): List<AuthPublicKey> {
            if (!comChave) return emptyList()
            val pub = destino.assinador!!.chavePublica()
            estado.ofereceuChave = true
            return listOf(AuthPublicKey(pub.algoritmo, pub.blob))
        }

        override suspend fun onSignatureRequest(key: AuthPublicKey, dataToSign: ByteArray): ByteArray? {
            val a = destino.assinador ?: return null
            val assinatura = a.assinar(dataToSign)
            if (assinatura == null) { if (a.invalidada) estado.biometriaInvalidada = true else estado.biometriaCancelada = true } else estado.metodoFinal = "publickey"
            return assinatura
        }

        override suspend fun onKeyboardInteractivePrompt(name: String, instruction: String, prompts: List<KeyboardInteractiveCallback.Prompt>): List<String>? {
            val r = interacao.pedirCodigo(name, instruction, prompts.map { it.text }, prompts.map { it.echo })
            if (r != null) { estado.metodoFinal = "keyboard-interactive"; estado.metodoSemChave = true }
            return r
        }

        override suspend fun onPasswordNeeded(): String? {
            if (destino.host.autenticacao is AutenticacaoHost.Chave) return null
            val s = destino.senhaLembrada ?: interacao.pedirSenha()
            if (s != null) { estado.metodoFinal = "password"; estado.metodoSemChave = true }
            return s
        }
    }

    private fun iniciarLeitura(c: SshClient, sessao: SshSession) {
        val leitura = escopo.launch {
            val saidaPadrao = launch { try { for (b in sessao.stdout) canalSaida.send(b) } catch (_: Exception) { } }
            val erro = launch { try { for (b in sessao.stderr) canalSaida.send(b) } catch (_: Exception) { } }
            saidaPadrao.join()
            erro.cancelAndJoin()
            if (!fechada.get()) { emitir(EventoConexao.Caiu(CausaSsh.DESCONECTADO)); canalSaida.close() }
        }
        val queda = escopo.launch {
            c.disconnectedFlow.collect { causa ->
                if (!fechada.get()) {
                    emitir(EventoConexao.Caiu(if (causa == null) CausaSsh.DESCONECTADO else MapaDeErros.causaDe(causa)))
                    canalSaida.close()
                }
            }
        }
        val vigia = escopo.launch {
            Vigia(intervaloVigiaMs, 3) {
                when (val p = c.ping()) {
                    is PingResult.Success -> true
                    PingResult.NotSupported -> null
                    PingResult.NotAuthenticated, is PingResult.Failure -> false
                }
            }.vigiar()
            if (!fechada.get()) {
                emitir(EventoConexao.Caiu(CausaSsh.REDE))
                runCatching { c.disconnect() }
                canalSaida.close()
            }
        }
        tarefas = listOf(leitura, queda, vigia)
    }
}
