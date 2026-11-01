package com.keyflow.notificacao

import com.keyflow.core.ButtonState
import com.keyflow.core.ShortcutOverflow
import com.keyflow.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** O que o host pede ao serviço: os tratadores que a orbe flutuante e o mic do teclado já usam (design §5, "reuso, não cópia"). */
interface AcoesDaNotificacao {
    /** `handleKeyboardMicTap`: ocioso inicia, gravando/pausado envia, cancelando descarta já, com a Visão encerra. */
    fun gravarEnviar()
    fun pausarRetomar()

    /** `handleCancel`: grava com a janela de desfazer; transcrevendo cancela a transcrição; com a Visão ativa a encerra. */
    fun descartar()
    fun desfazer()

    /** `RetrairExpandirTeclado` ou `LerTela` (o mesmo `executarAcaoDeAtalho` da orbe flutuante). */
    fun executar(acao: AcaoDaOrbe)
    fun rodarFluxo(fluxoId: String)
}

/** O que o host avisa à pessoa (Toast + vibração curta, no serviço): nunca em silêncio (NTF-14, NTF-16, L10). */
enum class AvisoDaNotificacao { BLOQUEADA, PRECISA_DESBLOQUEIO, SEM_SOBREPOSICAO, FLUXO_INCOMPLETO }

/**
 * O dono dos toques da notificação (design §5; NTF-09..11, NTF-14, NTF-16, NTF-48, NTF-49). JVM puro: tudo que é Android entra injetado.
 *
 * **Todo toque na orbe e todo botão passam antes por [PortaoDeToque]** (com `bloqueada` lida na hora, a cada toque): recusado, o toque **não é contado**
 * e **nenhum comando é executado** — nem o botão. Depois:
 * - **orbe**: [RajadaDeToques] reiniciada a cada toque; ao assentar (`tapBurstWindowMs`), [resolver] devolve o [Comando], que o host executa (as ações de
 *   Activity passam de novo pelo portão com `precisaDeActivity = true` e pela permissão de sobreposição, L10);
 * - **botão**: age na hora, **não entra na rajada**, confere o estado de agora (um botão que chega depois de o estado mudar é ignorado sem erro).
 *
 * A configuração, as `Settings` e o estado são lidos a cada toque (valem no toque seguinte, sem reiniciar o serviço, NTF-38).
 */
class NotificacaoHost(
    private val escopo: CoroutineScope,
    private val config: () -> ConfigNotificacao,
    private val settings: () -> Settings,
    private val bloqueada: () -> Boolean,
    private val relogio: () -> Long,
    private val estado: () -> ButtonState,
    private val visaoAtiva: () -> Boolean,
    private val tecladoVisivel: () -> Boolean,
    private val podeAbrirActivity: () -> Boolean,
    private val acoes: AcoesDaNotificacao,
    private val aviso: (AvisoDaNotificacao) -> Unit,
    private val vibrar: () -> Unit,
    private val fluxos: GanchoDeFluxoDaNotificacao = { null },
) {
    /** Presença em memória da sessão: dispensar nunca altera a configuração persistida. */
    val visibilidade = VisibilidadeDaNotificacao()

    private val rajada = RajadaDeToques(relogio) { settings().tapBurstWindowMs.toLong() }
    private var fechamento: Job? = null

    /** Toques contados e ainda não resolvidos (para os testes). */
    val toquesPendentes: Int get() = rajada.contagem

    fun aoTocarNaOrbe() {
        if (recusado(precisaDeActivity = false)) return
        rajada.toque()
        fechamento?.cancel()
        fechamento = escopo.launch {
            delay(rajada.faltaMs() ?: 0L)
            fecharRajada()
        }
    }

    /** Resolve a rajada agora (o `delay` do [aoTocarNaOrbe] a chama quando a janela assenta). */
    internal fun fecharRajada() {
        val toques = rajada.fechar()
        if (toques == 0) return
        val s = settings()
        val comando = resolver(
            toques = toques,
            visaoAtiva = visaoAtiva(),
            tecladoVisivel = tecladoVisivel(),
            efetiva = efetiva(config(), s),
            overflow = s.shortcutOverflow,
            fluxos = fluxos,
        )
        executar(comando)
    }

    private fun executar(comando: Comando) {
        // O portão vale no momento de agir, não só quando o toque chegou: a tela pode bloquear dentro da janela da rajada (NTF-16).
        // As ações de Activity passam por ele com `precisaDeActivity = true`.
        if (comando != Comando.Nada && comando != Comando.FluxoIncompleto) {
            val activity = comando is Comando.Executar && comando.acao.abreActivity()
            if (recusado(precisaDeActivity = activity)) return
        }
        when (comando) {
            Comando.Nada -> return
            Comando.GravarEnviar -> acoes.gravarEnviar()
            is Comando.Executar -> {
                if (comando.acao.abreActivity()) {
                    // Sem sobreposição o sistema recusaria o início da Activity (L10).
                    if (!podeAbrirActivity()) { aviso(AvisoDaNotificacao.SEM_SOBREPOSICAO); return }
                }
                acoes.executar(comando.acao)
            }
            is Comando.RodarFluxo -> acoes.rodarFluxo(comando.fluxoId)
            Comando.FluxoIncompleto -> { aviso(AvisoDaNotificacao.FLUXO_INCOMPLETO); return }
        }
        vibrar()
    }

    fun aoTocarNoBotao(botao: Botao) {
        if (recusado(precisaDeActivity = false)) return
        // O estado de agora decide: um botão atrasado (o estado já mudou) é ignorado, sem erro e sem contar como toque.
        if (botao !in BotoesDaNotificacao.para(estado(), visaoAtiva()).todos) return
        when (botao) {
            Botao.PAUSAR, Botao.RETOMAR -> acoes.pausarRetomar()
            Botao.DESCARTAR, Botao.ENCERRAR -> acoes.descartar()
            Botao.DESFAZER -> acoes.desfazer()
        }
        vibrar()
    }

    /** `true` se o portão recusou (já avisou e vibrou); o toque então não é contado nem executado. */
    private fun recusado(precisaDeActivity: Boolean): Boolean =
        when (PortaoDeToque.avaliar(bloqueada(), config().permitirComTelaBloqueada, precisaDeActivity)) {
            Portao.Aceitar -> false
            Portao.RecusarBloqueado -> { aviso(AvisoDaNotificacao.BLOQUEADA); vibrar(); true }
            Portao.RecusarPrecisaDesbloqueio -> { aviso(AvisoDaNotificacao.PRECISA_DESBLOQUEIO); vibrar(); true }
        }

    /** Solta a rajada pendente (o serviço está terminando). */
    fun parar() {
        fechamento?.cancel()
        rajada.fechar()
    }

    private fun AcaoDaOrbe.abreActivity(): Boolean = this == AcaoDaOrbe.LerTela
}
