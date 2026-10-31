package com.keyflow.computador.sessao

import com.keyflow.computador.Host
import com.keyflow.computador.ssh.PtyInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** O que a UI e a notificação veem de uma sessão. Nunca carrega host, usuário nem texto. */
data class SessaoResumo(val id: String, val hostId: String, val dono: DonoDaSessao, val compartilhada: Boolean, val estado: EstadoSessao)

sealed interface ResultadoAbertura {
    data class Aberta(val sessao: SessaoTerminal) : ResultadoAbertura
    data class Recusada(val motivo: MotivoRecusa) : ResultadoAbertura
}

enum class MotivoRecusa(val frase: String) {
    LIMITE_TOTAL("O limite é de 8 sessões abertas. Feche uma para abrir outra."),
    LIMITE_DO_AGENTE("O agente já tem 3 sessões abertas."),
}

sealed interface EventoGerenciador {
    /** 0 → 1 sessão: o serviço em primeiro plano sobe. */
    data object PrimeiraSessao : EventoGerenciador
    /** Fechou a última: o serviço cai. */
    data object SemSessoes : EventoGerenciador
}

/**
 * Abre, lista e fecha sessões (CMP-29): **8 no total, 3 do agente**; ociosidade só da sessão do agente (10 min, D21) — a do usuário
 * nunca fecha por ociosidade. A senha e a chave nunca passam por aqui: vêm pelas [DependenciasDeSessao].
 */
class GerenciadorDeSessoes(
    private val escopo: CoroutineScope,
    private val dependencias: DependenciasDeSessao,
    private val maxTotal: Int = 8,
    private val maxAgente: Int = 3,
    private val ociosidadeAgenteMs: Long = 10 * 60_000L,
    private val intervaloDeVerificacaoMs: Long = 30_000,
    private val novoId: () -> String = { java.util.UUID.randomUUID().toString().take(8) },
) {
    private val sessoes = linkedMapOf<String, SessaoTerminal>()
    private val trava = Any()
    private val _resumos = MutableStateFlow<List<SessaoResumo>>(emptyList())
    val resumos: StateFlow<List<SessaoResumo>> = _resumos
    private val _eventos = MutableSharedFlow<EventoGerenciador>(extraBufferCapacity = 16)
    val eventos: SharedFlow<EventoGerenciador> = _eventos

    init {
        escopo.launch {
            while (true) {
                delay(intervaloDeVerificacaoMs)
                fecharAgenteOcioso()
            }
        }
    }

    fun abrir(host: Host, dono: DonoDaSessao, pty: PtyInfo = PtyInfo(80, 24), porta: PortaDoEmulador? = null): ResultadoAbertura {
        val sessao = synchronized(trava) {
            limparEncerradas()
            if (sessoes.size >= maxTotal) return ResultadoAbertura.Recusada(MotivoRecusa.LIMITE_TOTAL)
            if (dono == DonoDaSessao.AGENTE && sessoes.values.count { it.dono == DonoDaSessao.AGENTE } >= maxAgente) return ResultadoAbertura.Recusada(MotivoRecusa.LIMITE_DO_AGENTE)
            SessaoTerminal(novoId(), host, dono, dependencias, escopo, porta = porta).also { sessoes[it.id] = it }
        }
        publicar()
        sessao.iniciar(pty)
        escopo.launch { sessao.estado.collect { publicar() } }
        return ResultadoAbertura.Aberta(sessao)
    }

    fun sessao(id: String): SessaoTerminal? = synchronized(trava) { sessoes[id] }

    suspend fun fechar(id: String, motivo: MotivoEncerramento = MotivoEncerramento.USUARIO) {
        val s = synchronized(trava) { sessoes[id] } ?: return
        s.fechar(motivo)
        synchronized(trava) { sessoes.remove(id) }
        publicar()
    }

    suspend fun fecharDoHost(hostId: String) = copia().filter { it.host.id == hostId }.forEach { fechar(it.id, MotivoEncerramento.HOST_REMOVIDO) }

    suspend fun fecharDoAgente() = copia().filter { it.dono == DonoDaSessao.AGENTE }.forEach { fechar(it.id, MotivoEncerramento.AGENTE_PARADO) }

    suspend fun fecharTodas() = copia().forEach { fechar(it.id) }

    fun compartilhar(id: String, valor: Boolean) { sessao(id)?.compartilhada = valor; publicar() }

    /** As sessões que o agente enxerga: as dele e as que o usuário compartilhou. */
    fun sessoesVisiveisAoAgente(): List<SessaoTerminal> = copia().filter { it.dono == DonoDaSessao.AGENTE || it.compartilhada }

    /** Fecha as sessões **do agente** sem atividade há [ociosidadeAgenteMs]. A do usuário nunca. */
    suspend fun fecharAgenteOcioso() {
        val agora = dependencias.agoraMs()
        copia().filter { it.dono == DonoDaSessao.AGENTE && agora - it.ultimaAtividadeMs >= ociosidadeAgenteMs }.forEach { fechar(it.id, MotivoEncerramento.OCIOSIDADE) }
    }

    private fun copia(): List<SessaoTerminal> = synchronized(trava) { sessoes.values.toList() }

    private fun limparEncerradas() { sessoes.values.removeAll { it.estado.value.terminal } }

    private var vivasAntes = 0

    /** Publica a lista e emite `PrimeiraSessao`/`SemSessoes` quando o número de sessões **vivas** (não terminais) passa por zero. */
    private fun publicar() {
        val lista = copia()
        _resumos.value = lista.map { SessaoResumo(it.id, it.host.id, it.dono, it.compartilhada, it.estado.value) }
        val evento = synchronized(trava) {
            val vivas = lista.count { !it.estado.value.terminal }
            val e = when {
                vivasAntes == 0 && vivas > 0 -> EventoGerenciador.PrimeiraSessao
                vivasAntes > 0 && vivas == 0 -> EventoGerenciador.SemSessoes
                else -> null
            }
            vivasAntes = vivas
            e
        }
        if (evento != null) _eventos.tryEmit(evento)
    }
}
