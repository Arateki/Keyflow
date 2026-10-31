package com.keyflow.computador.sessao

import com.keyflow.computador.Host
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.computador.ssh.ConexaoSsh
import com.keyflow.computador.ssh.DestinoSsh
import com.keyflow.computador.ssh.EventoConexao
import com.keyflow.computador.ssh.PtyInfo
import com.keyflow.computador.ssh.ResultadoConexao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tudo de fora que a sessão usa, em uma porta só (o Android implementa; os testes usam fakes). */
interface DependenciasDeSessao {
    fun novaConexao(): ConexaoSsh

    /** Monta o destino (senha lembrada, assinador). `null` = não deu (cofre, chave sumiu): a sessão falha. */
    suspend fun destino(host: Host, dono: DonoDaSessao): DestinoSsh?

    /** Chave com biometria **fora** da janela de 5 minutos: a reconexão não abre o prompt sozinha (D14). */
    fun precisaDoUsuario(host: Host): Boolean

    /** Há rede? A reconexão só tenta com `true`. */
    val rede: StateFlow<Boolean>

    fun agoraMs(): Long
}

/**
 * Uma sessão SSH: estado, o buffer de texto, a conexão e a reconexão automática (CMP-22). Cada tentativa é uma **conexão nova com
 * autenticação nova**. A primeira conexão que falha não se repete sozinha (o usuário vê o erro); depois de aberta, a queda por rede ou
 * servidor reconecta com backoff.
 */
class SessaoTerminal(
    val id: String,
    val host: Host,
    val dono: DonoDaSessao,
    private val dependencias: DependenciasDeSessao,
    private val escopo: CoroutineScope,
    val buffer: BufferDeTexto = BufferDeTexto(),
    @Volatile var porta: PortaDoEmulador? = null,
) {
    @Volatile var compartilhada: Boolean = false

    private val _estado = MutableStateFlow<EstadoSessao>(EstadoSessao.Conectando)
    val estado: StateFlow<EstadoSessao> = _estado

    /** Transições que a máquina recusaria (nunca deve ter itens; os testes conferem). */
    val transicoesImpossiveis = java.util.concurrent.CopyOnWriteArrayList<Pair<EstadoSessao, EstadoSessao>>()

    @Volatile var ultimaAtividadeMs: Long = dependencias.agoraMs()
        private set

    @Volatile private var conexao: ConexaoSsh? = null
    @Volatile private var pty = PtyInfo(80, 24)
    private var ciclo: Job? = null
    @Volatile private var encerrando = false

    fun iniciar(pty: PtyInfo) {
        this.pty = pty
        ciclo = escopo.launch { executar(reconectando = false) }
    }

    /** O usuário tocou em "reconectar" (biometria já liberada): retoma a partir de [EstadoSessao.PrecisaDoUsuario]. */
    fun retomar() {
        if (_estado.value != EstadoSessao.PrecisaDoUsuario) return
        ciclo?.cancel()
        ciclo = escopo.launch { executar(reconectando = true) }
    }

    suspend fun escrever(bytes: ByteArray) {
        ultimaAtividadeMs = dependencias.agoraMs()
        conexao?.escrever(bytes)
    }

    suspend fun redimensionar(novo: PtyInfo) {
        pty = novo
        conexao?.redimensionar(novo)
    }

    suspend fun fechar(motivo: MotivoEncerramento = MotivoEncerramento.USUARIO) {
        encerrando = true
        if (!_estado.value.terminal) definir(EstadoSessao.Encerrada(motivo))
        ciclo?.cancel()
        withContext(NonCancellable) { runCatching { conexao?.fechar() } }
    }

    private fun definir(novo: EstadoSessao) {
        val atual = _estado.value
        if (!EstadoSessao.permitida(atual, novo)) { transicoesImpossiveis += atual to novo; return }
        _estado.value = novo
    }

    private suspend fun executar(reconectando: Boolean) {
        var emReconexao = reconectando
        var tentativa = 0
        var primeiraQueda: Long? = null
        while (true) {
            if (emReconexao) {
                tentativa++
                val decorrido = primeiraQueda?.let { dependencias.agoraMs() - it } ?: 0L
                val espera = Reconexao.espera(tentativa, decorrido)
                    ?: return definir(EstadoSessao.Falhou(ultimaCausa ?: CausaSsh.DESCONECTADO))
                if (dependencias.precisaDoUsuario(host)) return definir(EstadoSessao.PrecisaDoUsuario)
                definir(EstadoSessao.Reconectando(tentativa))
                dependencias.rede.first { it }
                delay(espera)
            }
            val conn = dependencias.novaConexao()
            conexao = conn
            val observador = escopo.launch { conn.eventos.collect { traduzir(it, emReconexao) } }
            val destino = dependencias.destino(host, dono)
            val resultado = if (destino == null) ResultadoConexao.Falhou(CausaSsh.BIOMETRIA) else conn.conectar(destino, pty)
            observador.cancel()
            when (resultado) {
                ResultadoConexao.Aberta -> {
                    if (emReconexao) { buffer.adicionarLocal(BufferDeTexto.SEPARADOR_DE_RECONEXAO); porta?.escreverNaTela(BufferDeTexto.SEPARADOR_DE_RECONEXAO.toByteArray()) }
                    definir(EstadoSessao.Aberta)
                    tentativa = 0
                    primeiraQueda = null
                    ultimaCausa = null
                    conn.saida.collect { bytes ->
                        ultimaAtividadeMs = dependencias.agoraMs()
                        buffer.alimentar(bytes)
                        porta?.escreverNaTela(bytes)
                    }
                    if (encerrando) return
                    // A conexão emite `Caiu` antes de fechar a saída: o último evento guardado diz por quê.
                    (conn.eventos.replayCache.lastOrNull { it is EventoConexao.Caiu } as? EventoConexao.Caiu)?.let { ultimaCausa = it.causa }
                    primeiraQueda = dependencias.agoraMs()
                    val causa = ultimaCausa ?: CausaSsh.DESCONECTADO
                    ultimaCausa = causa
                    if (!Reconexao.reconectavel(causa)) return definir(EstadoSessao.Falhou(causa))
                    emReconexao = true
                }
                is ResultadoConexao.Falhou -> {
                    ultimaCausa = resultado.causa
                    if (!emReconexao || !Reconexao.reconectavel(resultado.causa)) return definir(EstadoSessao.Falhou(resultado.causa))
                    if (primeiraQueda == null) primeiraQueda = dependencias.agoraMs()
                }
            }
        }
    }

    @Volatile private var ultimaCausa: CausaSsh? = null

    private fun traduzir(e: EventoConexao, reconectando: Boolean) {
        if (reconectando) return
        when (e) {
            EventoConexao.Conectando -> Unit
            is EventoConexao.AguardandoServidor -> definir(EstadoSessao.AguardandoServidor)
            EventoConexao.Autenticando -> definir(EstadoSessao.Autenticando)
            else -> Unit
        }
    }
}
