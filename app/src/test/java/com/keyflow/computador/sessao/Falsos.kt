package com.keyflow.computador.sessao

import com.keyflow.computador.Host
import com.keyflow.computador.ssh.CausaSsh
import com.keyflow.computador.ssh.ConexaoSsh
import com.keyflow.computador.ssh.DestinoSsh
import com.keyflow.computador.ssh.EventoConexao
import com.keyflow.computador.ssh.PtyInfo
import com.keyflow.computador.ssh.ResultadoConexao
import com.keyflow.computador.ssh.ResultadoTeste
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

val HOST = Host("a1b2c3d4", "dev", "dev.example.org", 22, "yan")

/** Uma conexão de mentira: o teste decide o resultado e quando a saída acaba. */
class ConexaoFalsa(val resultado: ResultadoConexao = ResultadoConexao.Aberta, val eventosNaConexao: List<EventoConexao> = emptyList()) : ConexaoSsh {
    private val _eventos = MutableSharedFlow<EventoConexao>(replay = 8, extraBufferCapacity = 64)
    override val eventos: SharedFlow<EventoConexao> = _eventos
    val canal = Channel<ByteArray>(Channel.UNLIMITED)
    override val saida: Flow<ByteArray> = canal.receiveAsFlow()
    val escritos = mutableListOf<ByteArray>()
    var fechou = false
    var tamanho: PtyInfo? = null

    override suspend fun conectar(destino: DestinoSsh, pty: PtyInfo): ResultadoConexao {
        eventosNaConexao.forEach { _eventos.emit(it) }
        if (resultado is ResultadoConexao.Falhou) _eventos.emit(EventoConexao.Caiu(resultado.causa))
        return resultado
    }
    override suspend fun testar(destino: DestinoSsh): ResultadoTeste = ResultadoTeste.Falhou(CausaSsh.PROTOCOLO)
    override suspend fun escrever(bytes: ByteArray) { escritos += bytes }
    override suspend fun redimensionar(pty: PtyInfo) { tamanho = pty }
    override suspend fun fechar() { fechou = true; canal.close() }

    /** O servidor some: a saída acaba depois de um `Caiu`. */
    suspend fun cair(causa: CausaSsh = CausaSsh.REDE) { _eventos.emit(EventoConexao.Caiu(causa)); canal.close() }
}

class DependenciasFalsas(private val relogio: () -> Long) : DependenciasDeSessao {
    val fila = ArrayDeque<ConexaoFalsa>()
    val criadas = mutableListOf<ConexaoFalsa>()
    var destinos = 0
    var biometriaForaDaJanela = false
    override val rede: MutableStateFlow<Boolean> = MutableStateFlow(true)
    override fun novaConexao(): ConexaoSsh = (if (fila.isEmpty()) ConexaoFalsa() else fila.removeFirst()).also { criadas += it }
    override suspend fun destino(host: Host, dono: DonoDaSessao): DestinoSsh { destinos++; return DestinoSsh(host) }
    override fun precisaDoUsuario(host: Host) = biometriaForaDaJanela
    override fun agoraMs() = relogio()
}
