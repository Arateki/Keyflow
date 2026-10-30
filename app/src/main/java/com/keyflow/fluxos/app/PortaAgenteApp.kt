package com.keyflow.fluxos.app

import com.keyflow.agent.AgentActivity
import com.keyflow.agent.AgentBackend
import com.keyflow.agent.AgentController
import com.keyflow.agent.AgentEngine
import com.keyflow.agent.AgentEvent
import com.keyflow.agent.ChatItem
import com.keyflow.agent.ConfirmationDecision
import com.keyflow.agent.ConfirmationGate
import com.keyflow.agent.ConfirmationRequest
import com.keyflow.agent.NoticeKind
import com.keyflow.data.Settings
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloAplicado
import com.keyflow.fluxos.ModeloPorEtapa
import com.keyflow.fluxos.execucao.CausaErro
import com.keyflow.fluxos.execucao.DestinoAba
import com.keyflow.fluxos.execucao.ErroEtapa
import com.keyflow.fluxos.execucao.ErrosDeFluxo
import com.keyflow.fluxos.execucao.PortaEtapa
import com.keyflow.fluxos.execucao.ResultadoEtapa
import com.keyflow.fluxos.execucao.Valor
import com.keyflow.i18n.tr
import com.keyflow.reasoning.ReasoningError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * A sessão do agente de **uma execução** de fluxo (FLX-24, D10): o controlador, o gate de confirmação e o que mais a execução
 * precisa soltar ao terminar. Não é o chat da aba: nasce vazia e morre com a execução, [encerrar] a descarta.
 */
class SessaoDoFluxo(
    val controller: AgentController,
    val gate: ConfirmationGate,
    /** O nome que a pessoa lê da ferramenta (`AgentTool.label`); o painel de confirmação mostra este, não o do modelo. */
    val rotuloDaFerramenta: (String) -> String = { it },
    private val aoEncerrar: () -> Unit,
) {
    private val _visivel = MutableStateFlow(false)

    /** A janela de confirmação está na tela agora (a `FluxoConfirmacaoActivity` marca em `onStart`/`onStop`). */
    val confirmacaoVisivel: StateFlow<Boolean> = _visivel.asStateFlow()

    /** O pedido de permissão aberto agora **nesta** sessão (e só nela). */
    val pendente: StateFlow<ConfirmationRequest?> get() = gate.pending

    @Volatile
    var negada: Boolean = false
        private set

    fun marcarVisivel(visivel: Boolean) {
        _visivel.value = visivel
    }

    /**
     * A resposta da janela. **Negar** — também sair dela sem responder — nega a ferramenta e **cancela a etapa** (falha fechada, FLX-25):
     * o agente não segue para outra ferramenta nem tenta de novo.
     */
    fun responder(callId: String, decisao: ConfirmationDecision): Boolean {
        if (decisao == ConfirmationDecision.DENY) negada = true
        val aceitou = controller.respond(callId, decisao)
        if (decisao == ConfirmationDecision.DENY) controller.cancel()
        return aceitou
    }

    /** Sair da janela sem resposta: nega o pedido aberto (se houver). */
    fun negarPendente() {
        negada = true
        gate.pending.value?.let { controller.respond(it.callId, ConfirmationDecision.DENY) }
        controller.cancel()
    }

    fun encerrar() {
        controller.cancel()
        aoEncerrar()
    }
}

/** A sessão cuja confirmação a `FluxoConfirmacaoActivity` mostra. Uma só: o executor roda um fluxo por vez. */
object SessaoAtiva {
    class Registro(val sessao: SessaoDoFluxo, val nomeDoFluxo: String?)

    private val atualRef = AtomicReference<Registro?>(null)

    val atual: Registro? get() = atualRef.get()

    internal fun registrar(registro: Registro) = atualRef.set(registro)

    internal fun limpar(sessao: SessaoDoFluxo) {
        atualRef.compareAndSet(atualRef.get()?.takeIf { it.sessao === sessao }, null)
    }
}

/**
 * Agente (FLX-24, FLX-25, design §7.3): manda o texto de entrada a uma sessão exclusiva da execução e entrega a **última resposta do
 * assistente**. [resolver] escolhe o backend pelas Settings da etapa (`AgentBackends.resolve`). Quando o gate da sessão pede
 * permissão para uma ferramenta, [abrirConfirmacao] abre a janela translúcida do KeyFlow; sem ela visível em [PRAZO_DA_JANELA_MS]
 * (ou se não abrir), o pedido é **negado** e a etapa termina `Cancelada`: nenhuma ferramenta que pede confirmação roda às cegas.
 */
class PortaAgenteApp(
    private val settings: suspend () -> Settings,
    private val resolver: (Settings) -> AgentBackend,
    private val novaSessao: () -> SessaoDoFluxo,
    private val abrirConfirmacao: () -> Boolean,
    private val nomeDoFluxo: () -> String? = { null },
) : PortaEtapa {

    /** O que o espião viu de falha do motor (o chat só mostra "[mensagem]" no balão; aqui interessa o tipo). */
    private class Espiao {
        @Volatile var erro: ReasoningError? = null

        /** O fluxo do motor lançou: o controlador pôs a mensagem da exceção no balão, e ela não é resposta. */
        @Volatile var lancou: Boolean = false

        fun registrar(e: Throwable) {
            if (e !is CancellationException) lancou = true
        }
    }

    override suspend fun executar(etapa: Etapa, entrada: Valor): ResultadoEtapa<Valor> {
        val texto = (entrada as? Valor.Texto)?.texto ?: return ResultadoEtapa.Falha(ErrosDeFluxo.motor())
        val s = when (val a = ModeloPorEtapa.aplicar(settings(), etapa)) {
            is ModeloAplicado.Ok -> a.settings
            is ModeloAplicado.Invalido -> return ResultadoEtapa.Falha(ErrosDeFluxo.modeloInvalido(DestinoAba.RACIOCINIO))
        }
        val espiao = Espiao()
        val backend = when (val b = resolver(s)) {
            is AgentBackend.Unavailable -> return ResultadoEtapa.Falha(ErroEtapa(CausaErro.INDISPONIVEL, b.message, DestinoAba.RACIOCINIO))
            is AgentBackend.WithTools -> AgentBackend.WithTools(espiar(b.engine, espiao))
            is AgentBackend.TextOnly -> AgentBackend.TextOnly { prompt ->
                b.generate(prompt).onEach { if (it is AgentEvent.Error) espiao.erro = it.kind }.catch { e -> espiao.registrar(e); throw e }
            }
        }

        val sessao = novaSessao()
        SessaoAtiva.registrar(SessaoAtiva.Registro(sessao, nomeDoFluxo()))
        try {
            if (!sessao.controller.send(texto, backend)) {
                return ResultadoEtapa.Falha(ErroEtapa(CausaErro.MOTOR, tr("O agente está ocupado.")))
            }
            val estado = coroutineScope {
                val vigia = launch { vigiarConfirmacao(sessao) }
                try {
                    // `send` põe a atividade em THINKING antes de devolver: o primeiro IDLE depois é o fim desta execução.
                    sessao.controller.state.first { it.activity == AgentActivity.IDLE }
                } finally {
                    vigia.cancel()
                }
            }
            if (sessao.negada) return ResultadoEtapa.Cancelada
            espiao.erro?.let { return ResultadoEtapa.Falha(ErrosDeFluxo.deRaciocinio(it)) }
            if (espiao.lancou) return ResultadoEtapa.Falha(ErrosDeFluxo.motor())
            estado.items.filterIsInstance<ChatItem.Notice>().lastOrNull()?.let { return ResultadoEtapa.Falha(ErroEtapa(CausaErro.MOTOR, frase(it.kind))) }
            val resposta = estado.items.filterIsInstance<ChatItem.AssistantMessage>().lastOrNull()?.text?.trim().orEmpty()
            return if (resposta.isEmpty()) ResultadoEtapa.Falha(ErrosDeFluxo.respostaEmBranco()) else ResultadoEtapa.Ok(Valor.Texto(resposta))
        } catch (e: CancellationException) {
            throw e // cancelar o fluxo: o finally cancela só a sessão dele
        } finally {
            SessaoAtiva.limpar(sessao)
            sessao.encerrar()
        }
    }

    /** Abre a janela quando o gate da sessão pede permissão e nega o pedido se ela não aparece a tempo. */
    private suspend fun vigiarConfirmacao(sessao: SessaoDoFluxo) {
        var atendido: String? = null
        sessao.pendente.collect { pedido ->
            if (pedido == null || pedido.callId == atendido) return@collect
            atendido = pedido.callId
            val abriu = try { abrirConfirmacao() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            val visivel = abriu && withTimeoutOrNull(PRAZO_DA_JANELA_MS) { sessao.confirmacaoVisivel.first { it } } == true
            if (!visivel) sessao.negarPendente()
        }
    }

    private fun espiar(motor: AgentEngine, espiao: Espiao) = object : AgentEngine {
        override fun chat(
            messages: List<com.keyflow.agent.AgentMessage>,
            tools: List<com.keyflow.agent.ToolDefinition>,
            system: String,
            maxTokens: Int,
        ) = motor.chat(messages, tools, system, maxTokens)
            .onEach { if (it is AgentEvent.Error) espiao.erro = it.kind }
            .catch { e -> espiao.registrar(e); throw e }
    }

    private fun frase(tipo: NoticeKind): String = when (tipo) {
        NoticeKind.STEP_LIMIT -> tr("Cheguei ao limite de passos desta mensagem. Peça para continuar, se quiser.")
        NoticeKind.TRUNCATED -> tr("A resposta foi cortada por limite de tamanho.")
        NoticeKind.REFUSED -> tr("O modelo recusou responder a isto.")
        NoticeKind.GENERATION_ERROR -> tr("Erro durante a geração.")
    }

    companion object {
        /** Quanto esperar a janela de confirmação aparecer antes de negar o pedido (falha fechada). */
        const val PRAZO_DA_JANELA_MS = 15_000L
    }
}
