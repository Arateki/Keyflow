package com.keyflow.computador.app

import com.keyflow.computador.Host
import com.keyflow.computador.sessao.DependenciasDeSessao
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.ssh.ConexaoSsh
import com.keyflow.computador.ssh.DestinoSsh
import com.keyflow.computador.ssh.EventoConexao
import com.keyflow.computador.ssh.PtyInfo
import com.keyflow.computador.ssh.ResultadoConexao
import com.keyflow.computador.ssh.ResultadoTeste
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Conexão de mentira dos instrumentados: abre na hora, devolve o que o teste empurrar em [servidor] e anota tudo que o app escreve. */
class ConexaoFalsa : ConexaoSsh {
    private val ev = MutableSharedFlow<EventoConexao>(replay = 4)
    override val eventos: SharedFlow<EventoConexao> = ev
    private val canal = Channel<ByteArray>(Channel.UNLIMITED)
    override val saida: Flow<ByteArray> = canal.receiveAsFlow()
    val escritos = CopyOnWriteArrayList<ByteArray>()
    val tamanhos = CopyOnWriteArrayList<PtyInfo>()
    @Volatile var ptyInicial: PtyInfo? = null
    @Volatile var fechada = false

    override suspend fun conectar(destino: DestinoSsh, pty: PtyInfo): ResultadoConexao { ptyInicial = pty; return ResultadoConexao.Aberta }
    override suspend fun testar(destino: DestinoSsh): ResultadoTeste = error("não usado")
    override suspend fun escrever(bytes: ByteArray) { escritos += bytes }
    override suspend fun redimensionar(pty: PtyInfo) { tamanhos += pty }
    override suspend fun fechar() { fechada = true; canal.close() }

    /** O que o "servidor" manda para a tela. */
    fun servidor(bytes: ByteArray) { canal.trySend(bytes) }

    fun tudoQueFoiEscrito(): ByteArray = escritos.fold(ByteArray(0)) { a, b -> a + b }
}

/** As dependências da sessão com [ConexaoFalsa]: [conexoes] guarda cada uma criada, na ordem. */
class DependenciasFalsas : DependenciasDeSessao {
    val conexoes = CopyOnWriteArrayList<ConexaoFalsa>()
    override fun novaConexao(): ConexaoSsh = ConexaoFalsa().also { conexoes += it }
    override suspend fun destino(host: Host, dono: DonoDaSessao): DestinoSsh = DestinoSsh(host)
    override fun precisaDoUsuario(host: Host) = false
    override val rede: StateFlow<Boolean> = MutableStateFlow(true)
    override fun agoraMs() = System.currentTimeMillis()
}
