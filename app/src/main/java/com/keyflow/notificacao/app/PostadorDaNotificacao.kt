package com.keyflow.notificacao.app

import com.keyflow.notificacao.EventoPresenca
import com.keyflow.notificacao.VisibilidadeDaNotificacao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NTF-T11: último retrato visível vence, no máximo uma publicação por janela de 250 ms.
 * As portas isolam Android dos testes JVM. Desenho e montagem acontecem no dispatcher de fundo; o primeiro
 * plano recebe o cache pronto ou a mínima imediatamente. Dispensar só muda a presença em memória.
 * A geração impede que um desenho já em curso ressuscite a notificação dispensada ou uma sessão encerrada.
 */
class PostadorDaNotificacao<E : Any, N : Any>(
    escopo: CoroutineScope,
    private val construir: (E) -> N,
    private val minima: () -> N,
    private val permitida: () -> Boolean,
    private val publicar: (N) -> Unit,
    private val falha: (Throwable) -> Unit = {},
    private val visibilidade: VisibilidadeDaNotificacao = VisibilidadeDaNotificacao(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val janelaMs: Long = 250,
) {
    private val trava = Any()
    private val sinal = Channel<Unit>(Channel.CONFLATED)
    private var ultimo: E? = null
    private var geracao = 0L
    private var processada = -1L
    private var pronta: Pair<E, N>? = null
    private var inicialCompleta: E? = null
    private var publicado: E? = null

    @Volatile var publicacoes: Int = 0
        private set
    @Volatile var ultimaPublicada: N? = null
        private set
    @Volatile var ultimaInicial: N? = null
        private set

    val podePostar: Boolean get() = synchronized(trava) { visibilidade.podePostar }
    val temPendencia: Boolean get() = synchronized(trava) { visibilidade.podePostar && processada != geracao }

    private val trabalhador = escopo.launch(dispatcher) {
        for (ignorado in sinal) {
            delay(janelaMs)
            val pedido = synchronized(trava) {
                if (visibilidade.presenca == com.keyflow.notificacao.Presenca.DISPENSADA) null
                else ultimo?.let { geracao to it }
            } ?: continue
            val (g, estado) = pedido
            val existente = synchronized(trava) { pronta?.takeIf { it.first == estado }?.second }
            val notificacao = existente ?: try {
                construir(estado)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                falha(e); minima()
            }
            val entregar = synchronized(trava) {
                if (g != geracao) false else {
                    pronta = estado to notificacao
                    visibilidade.podePostar && publicado != estado
                }
            }
            try {
                // Binder não segura a trava usada pela main, inclusive antes de startForeground.
                // A permissão também pode bloquear: revalida a geração DEPOIS dela e antes de notify.
                if (entregar && permitida() && synchronized(trava) { g == geracao && visibilidade.podePostar }) {
                    publicar(notificacao)
                    synchronized(trava) {
                        if (g == geracao && visibilidade.podePostar) {
                            publicado = estado
                            ultimaPublicada = notificacao
                        }
                        publicacoes++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) { falha(e) }
            finally { synchronized(trava) { if (g == geracao) processada = g } }
        }
    }

    /** Também aquece o cache enquanto PARADA; DISPENSADA guarda só o último retrato, sem desenhar nem postar. */
    fun solicitar(estado: E) = synchronized(trava) {
        if (ultimo == estado) return@synchronized
        ultimo = estado
        geracao++
        if (visibilidade.presenca != com.keyflow.notificacao.Presenca.DISPENSADA) sinal.trySend(Unit)
    }

    /** Nunca espera o desenho: é a notificação obrigatória usada por startForeground, mesmo sem permissão. */
    fun inicial(estado: E): N {
        val cache = synchronized(trava) {
            solicitar(estado)
            pronta?.takeIf { it.first == estado }.also { inicialCompleta = it?.first }?.second
        }
        return (cache ?: minima()).also { ultimaInicial = it }
    }

    fun evento(evento: EventoPresenca) = synchronized(trava) {
        val repor = visibilidade.aplicar(evento)
        when (evento) {
            EventoPresenca.Dispensou, EventoPresenca.SessaoTerminou -> {
                geracao++
                processada = geracao
                publicado = null
            }
            else -> if (repor || (evento == EventoPresenca.AbaAberta && visibilidade.podePostar && publicado != ultimo)) {
                geracao++
                publicado = if (evento == EventoPresenca.SessaoIniciou) inicialCompleta else null
                sinal.trySend(Unit)
            }
        }
    }

    fun encerrar() {
        evento(EventoPresenca.SessaoTerminou)
        trabalhador.cancel()
        sinal.close()
    }
}
