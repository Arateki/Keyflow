package com.keyflow.ui.terminal

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.computador.Host
import com.keyflow.computador.app.LigacaoEmulador
import com.keyflow.computador.app.PoliticaDeColar
import com.keyflow.computador.app.PoliticaDeLinks
import com.keyflow.computador.sessao.SessaoResumo
import com.keyflow.i18n.tr
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.theme.LocalKeyFlowPalette
import org.connectbot.terminal.Terminal

const val TERMINAL_TELA_TAG = "terminal-tela"
const val TERMINAL_VAZIO_TAG = "terminal-sem-sessao"
const val TERMINAL_AVISO_TAG = "terminal-aviso"
const val TERMINAL_COLAR_DIALOGO_TAG = "terminal-colar-dialogo"
const val TERMINAL_COLAR_CONFIRMAR_TAG = "terminal-colar-confirmar"
const val TERMINAL_COLAR_CANCELAR_TAG = "terminal-colar-cancelar"
const val TERMINAL_OSC52_DIALOGO_TAG = "terminal-osc52-dialogo"
const val TERMINAL_OSC52_PERMITIR_TAG = "terminal-osc52-permitir"
const val TERMINAL_OSC52_NEGAR_TAG = "terminal-osc52-negar"
const val TERMINAL_LINK_DIALOGO_TAG = "terminal-link-dialogo"
const val TERMINAL_LINK_ABRIR_TAG = "terminal-link-abrir"
const val TERMINAL_LINK_CANCELAR_TAG = "terminal-link-cancelar"
const val TERMINAL_FONTE_ASSET = "fonts/JetBrainsMonoNerdFontMono-Regular.ttf"

/** A fonte do terminal (D16): JetBrains Mono com glifos Nerd (OFL). Sem o arquivo, a monoespaçada do sistema. */
fun fonteDoTerminal(contexto: android.content.Context): Typeface =
    try { Typeface.createFromAsset(contexto.assets, TERMINAL_FONTE_ASSET) } catch (_: RuntimeException) { Typeface.MONOSPACE }

/**
 * O terminal na tela (CMP-24, 25, 28, 35). Sem efeitos próprios: quem abre sessão, fecha aba ou abre link recebe por callback, então a
 * mesma tela serve à activity e ao teste. O emulador vem de [ligacaoDe] (vive fora da tela: girar não perde o que estava desenhado).
 *
 * Os três diálogos aqui são os da segurança das sequências: **colar várias linhas** (confirma sempre que há quebra), **`OSC 52` de escrita**
 * (padrão negar) e **link `OSC 8`** (a URL inteira, só `http`, `https` e `mailto`).
 */
@Composable
fun TerminalScreen(
    sessoes: List<SessaoResumo>,
    hosts: List<Host>,
    ativaId: String?,
    ligacaoDe: (String) -> LigacaoEmulador?,
    aviso: String?,
    onSelecionar: (String) -> Unit,
    onFecharAba: (String) -> Unit,
    onNovaSessao: (Host) -> Unit,
    onAbrirLink: (String) -> Unit,
    onCopiar: (String) -> Unit,
    lerAreaDeTransferencia: () -> String?,
    modifier: Modifier = Modifier,
    barraDeEntrada: @Composable (LigacaoEmulador) -> Unit = {},
    entradaDoTerminal: EntradaParaTerminal = EntradaParaTerminal.Padrao,
) {
    val palette = LocalKeyFlowPalette.current
    val contexto = LocalContext.current
    val tipo = remember { fonteDoTerminal(contexto) }
    val ativa = sessoes.firstOrNull { it.id == ativaId } ?: sessoes.firstOrNull()
    val ligacao = ativa?.let { ligacaoDe(it.id) }
    val titulos = sessoes.associate { it.id to ligacaoDe(it.id)?.titulo?.value }
    var colar by remember { mutableStateOf<PoliticaDeColar.Preparado?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var linkBloqueado by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(palette.bg).testTag(TERMINAL_TELA_TAG)) {
        if (ativa != null) {
            val nome = hosts.firstOrNull { it.id == ativa.hostId }?.nome ?: tr("Servidor")
            BarraDeEstado(nome, ativa.estado)
        }
        aviso?.let {
            Text(it, color = palette.terracotta, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(TERMINAL_AVISO_TAG))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ligacao == null || ativa == null) {
                Text(tr("Nenhuma sessão aberta. Toque em + para abrir uma."), color = palette.muted, fontSize = 14.sp, modifier = Modifier.padding(20.dp).testTag(TERMINAL_VAZIO_TAG))
            } else {
                key(ativa.id) {
                    val pedidoOsc52 by ligacao.pedidoDeClipboard.collectAsStateOnMain()
                    LaunchedEffect(palette.isDark, ligacao) { ligacao.emulador.setDefaultColors(palette.fg.toArgb(), palette.bg.toArgb()) }
                    Terminal(
                        terminalEmulator = ligacao.emulador,
                        modifier = Modifier.fillMaxSize(),
                        typeface = tipo,
                        initialFontSize = 14.sp,
                        backgroundColor = palette.bg,
                        foregroundColor = palette.fg,
                        keyboardEnabled = true,
                        showSoftKeyboard = true,
                        onHyperlinkClick = { url -> if (PoliticaDeLinks.aceitar(url) != null) link = url.trim() else linkBloqueado = true },
                        onPasteRequest = {
                            val texto = lerAreaDeTransferencia()
                            if (!texto.isNullOrEmpty()) {
                                val p = PoliticaDeColar.preparar(texto)
                                if (p.texto.isNotEmpty()) { if (p.precisaConfirmar) colar = p else ligacao.colar(p.texto) }
                            }
                        },
                        onInterceptKey = entradaDoTerminal.interceptar,
                        modifierManager = entradaDoTerminal.modificadores,
                    )
                    pedidoOsc52?.let { texto ->
                        AlertDialog(
                            modifier = Modifier.testTag(TERMINAL_OSC52_DIALOGO_TAG),
                            onDismissRequest = { ligacao.responderClipboard(false) },
                            title = { Text(tr("O servidor quer copiar texto")) },
                            text = { Text(tr("O servidor pediu para colocar %d caracteres na área de transferência. Só permita se foi você quem pediu.", texto.length)) },
                            confirmButton = {
                                TextButton(onClick = { ligacao.responderClipboard(true)?.let(onCopiar) }, modifier = Modifier.testTag(TERMINAL_OSC52_PERMITIR_TAG)) { Text(tr("Permitir")) }
                            },
                            dismissButton = {
                                TextButton(onClick = { ligacao.responderClipboard(false) }, modifier = Modifier.testTag(TERMINAL_OSC52_NEGAR_TAG)) { Text(tr("Negar")) }
                            },
                        )
                    }
                }
            }
        }
        if (ligacao != null) barraDeEntrada(ligacao)
        AbasDeSessao(sessoes, ativa?.id, hosts, titulos, onSelecionar, onFecharAba, onNovaSessao)
    }

    colar?.let { p ->
        AlertDialog(
            modifier = Modifier.testTag(TERMINAL_COLAR_DIALOGO_TAG),
            onDismissRequest = { colar = null },
            title = { Text(tr("Colar %d linhas?", p.linhas)) },
            text = { Text(tr("Elas podem ser executadas.")) },
            confirmButton = {
                TextButton(onClick = { ligacao?.colar(p.texto); colar = null }, modifier = Modifier.testTag(TERMINAL_COLAR_CONFIRMAR_TAG)) { Text(tr("Colar")) }
            },
            dismissButton = { TextButton(onClick = { colar = null }, modifier = Modifier.testTag(TERMINAL_COLAR_CANCELAR_TAG)) { Text(tr("Cancelar")) } },
        )
    }
    link?.let { url ->
        AlertDialog(
            modifier = Modifier.testTag(TERMINAL_LINK_DIALOGO_TAG),
            onDismissRequest = { link = null },
            title = { Text(tr("Abrir este link?")) },
            text = { Text(url) },
            confirmButton = { TextButton(onClick = { link = null; onAbrirLink(url) }, modifier = Modifier.testTag(TERMINAL_LINK_ABRIR_TAG)) { Text(tr("Abrir")) } },
            dismissButton = { TextButton(onClick = { link = null }, modifier = Modifier.testTag(TERMINAL_LINK_CANCELAR_TAG)) { Text(tr("Cancelar")) } },
        )
    }
    if (linkBloqueado) {
        AlertDialog(
            onDismissRequest = { linkBloqueado = false },
            title = { Text(tr("Link bloqueado")) },
            text = { Text(tr("O KeyFlow só abre links http, https e mailto.")) },
            confirmButton = { TextButton(onClick = { linkBloqueado = false }) { Text(tr("OK")) } },
        )
    }
}
