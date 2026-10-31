package com.keyflow.computador.app

import androidx.compose.ui.graphics.Color
import com.keyflow.computador.sessao.PortaDoEmulador
import com.keyflow.computador.sessao.SessaoTerminal
import com.keyflow.computador.ssh.PtyInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.connectbot.terminal.InlineImages
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/** Para onde vão os bytes e o tamanho da tela. A [SessaoTerminal] é quem implementa em produção; os testes trocam por um registro. */
interface DestinoDoEmulador {
    suspend fun escrever(bytes: ByteArray)
    suspend fun redimensionar(pty: PtyInfo)
}

/**
 * O elo entre a sessão SSH e o termlib (CMP-23, CMP-35): os bytes do servidor passam por [FiltroDaSaidaDoServidor] e entram no emulador
 * (`writeInput`); o que o emulador devolve (teclas e respostas) passa por [FiltroDeRespostas] e segue **em ordem** para o servidor; a
 * mudança de área vira `window-change` com colunas, linhas **e pixels**.
 *
 * Vive **mais que a tela**: o registro ([EmuladoresDeSessao]) o guarda por sessão, então girar o aparelho ou voltar para o app não perde o
 * que estava desenhado. Só [fechar] (a sessão acabou) libera o emulador. Tudo fica em memória (CMP-36).
 */
class LigacaoEmulador(
    private val escopo: CoroutineScope,
    corFrente: Color,
    corFundo: Color,
    colunas: Int = 80,
    linhas: Int = 24,
    /** Teto de linhas de histórico do emulador (a bancada da T1 mediu 5 000 e 20 000 no fork). */
    scrollback: Int = 5_000,
) : PortaDoEmulador, AutoCloseable {
    private sealed interface Saida {
        class Bytes(val dados: ByteArray) : Saida
        class Tamanho(val pty: PtyInfo) : Saida
    }

    private val fila = Channel<Saida>(Channel.UNLIMITED)
    private val destino = CompletableDeferred<DestinoDoEmulador>()
    private val filtroDeSaida = FiltroDeRespostas()
    private val filtroDeEntrada = FiltroDaSaidaDoServidor { _titulo.value = it }
    private val _titulo = MutableStateFlow<String?>(null)
    private val _pedidoDeClipboard = MutableStateFlow<String?>(null)
    @Volatile private var fechada = false

    /** O rótulo que o servidor pediu para a aba (já sanitizado), ou `null`. */
    val titulo: StateFlow<String?> = _titulo

    /** Texto que o servidor quer copiar (`OSC 52` de escrita) e que espera o consentimento da pessoa. Padrão: negar. */
    val pedidoDeClipboard: StateFlow<String?> = _pedidoDeClipboard

    val emulador: TerminalEmulator = TerminalEmulatorFactory.create(
        initialRows = linhas,
        initialCols = colunas,
        defaultForeground = corFrente,
        defaultBackground = corFundo,
        onKeyboardInput = { bytes ->
            val ok = filtroDeSaida.filtrar(bytes)
            if (ok.isNotEmpty() && !fechada) fila.trySend(Saida.Bytes(ok.copyOf()))
        },
        onResize = { dim -> if (!fechada) fila.trySend(Saida.Tamanho(PtyInfo(dim.columns, dim.rows, dim.widthPixels, dim.heightPixels))) },
        onClipboardCopy = { texto -> if (!fechada) _pedidoDeClipboard.value = texto },
        autoDetectUrls = false,
        // As imagens (kitty, iTerm2, sixel) entram na Fase 2, com a política por host.
        inlineImages = InlineImages.Off,
        maxScrollbackLines = scrollback,
    )

    init {
        escopo.launch {
            val d = destino.await()
            // Um único consumidor: a ordem em que o emulador produziu é a ordem em que o servidor recebe (um `launch` por pedaço reordenaria).
            var ultimoTamanho: PtyInfo? = null
            for (item in fila) {
                try {
                    when (item) {
                        is Saida.Bytes -> d.escrever(item.dados)
                        is Saida.Tamanho -> if (item.pty != ultimoTamanho) { ultimoTamanho = item.pty; d.redimensionar(item.pty) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // conexão caída: a sessão cuida do estado; o que o usuário digitou nesse intervalo se perde.
                }
            }
        }
    }

    /** Liga o destino (a sessão). Os pedaços produzidos antes ficam na fila e saem depois, na mesma ordem. */
    fun ligarAoDestino(d: DestinoDoEmulador) { destino.complete(d) }

    fun ligarASessao(sessao: SessaoTerminal) = ligarAoDestino(object : DestinoDoEmulador {
        override suspend fun escrever(bytes: ByteArray) = sessao.escrever(bytes)
        override suspend fun redimensionar(pty: PtyInfo) = sessao.redimensionar(pty)
    })

    override fun escreverNaTela(bytes: ByteArray) {
        if (fechada) return
        val limpo = synchronized(filtroDeEntrada) { filtroDeEntrada.processar(bytes) }
        if (limpo.isEmpty()) return
        try { emulador.writeInput(limpo) } catch (_: IllegalStateException) { /* fechou entre a checagem e a escrita */ }
    }

    override fun tamanhoAtual(): PtyInfo = emulador.dimensions.let { PtyInfo(it.columns, it.rows, it.widthPixels, it.heightPixels) }

    /** Resposta do consentimento do `OSC 52`: [permitir] devolve o texto a copiar (ou `null`). Sempre limpa o pedido. */
    fun responderClipboard(permitir: Boolean): String? {
        val texto = _pedidoDeClipboard.value
        _pedidoDeClipboard.value = null
        return if (permitir) texto else null
    }

    /** Cola [texto] já preparado por [PoliticaDeColar] (o emulador respeita o bracketed paste). */
    fun colar(texto: String) { if (!fechada && texto.isNotEmpty()) emulador.pasteText(texto) }

    /** Visível aos testes: quantos pedaços o filtro de saída descartou. */
    val descartesDeSaida: Int get() = filtroDeSaida.descartes

    override fun close() { fechar() }

    fun fechar() {
        if (fechada) return
        fechada = true
        fila.close()
        if (!destino.isCompleted) destino.cancel()
        runCatching { emulador.close() }
    }
}
