package com.keyflow.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.computador.app.texto
import com.keyflow.computador.sessao.EstadoSessao
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val TERMINAL_BARRA_TAG = "terminal-barra-estado"
const val TERMINAL_ESTADO_TAG = "terminal-estado-texto"

/** O texto do estado da sessão (CMP-31: nunca só cor; cada estado tem símbolo e palavra). */
fun textoDoEstado(estado: EstadoSessao): String = when (estado) {
    EstadoSessao.Conectando -> "⟳ " + tr("Conectando…")
    EstadoSessao.AguardandoServidor -> "⟳ " + tr("Aguardando a confirmação do servidor…")
    EstadoSessao.Autenticando -> "⟳ " + tr("Autenticando…")
    EstadoSessao.Aberta -> "● " + tr("Conectado")
    is EstadoSessao.Reconectando -> "⟳ " + tr("Reconectando… (tentativa %d)", estado.tentativa)
    EstadoSessao.PrecisaDoUsuario -> "⚠ " + tr("Toque para reconectar")
    is EstadoSessao.Encerrada -> "○ " + tr("Sessão encerrada")
    is EstadoSessao.Falhou -> "✕ " + (estado.causa.texto()?.let { tr("Caiu: %s", it) } ?: tr("Conexão cancelada"))
}

/** Barra do topo: `servidor · estado`. A cor reforça (verde/âmbar/vermelho), o texto diz. */
@Composable
fun BarraDeEstado(nomeDoHost: String, estado: EstadoSessao, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val cor = when (estado) {
        EstadoSessao.Aberta -> palette.teal
        is EstadoSessao.Falhou, is EstadoSessao.Encerrada -> palette.terracotta
        else -> palette.record
    }
    val texto = textoDoEstado(estado)
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.panel)
            .heightIn(min = 40.dp)
            .padding(horizontal = 12.dp)
            .testTag(TERMINAL_BARRA_TAG)
            .semantics { contentDescription = "$nomeDoHost, $texto" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(nomeDoHost, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        Text(texto, color = cor, fontFamily = SpaceGrotesk, fontSize = 12.sp, maxLines = 1, modifier = Modifier.testTag(TERMINAL_ESTADO_TAG))
    }
}
