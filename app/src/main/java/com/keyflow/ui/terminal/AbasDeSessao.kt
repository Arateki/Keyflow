package com.keyflow.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.computador.Host
import com.keyflow.computador.sessao.DonoDaSessao
import com.keyflow.computador.sessao.SessaoResumo
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val TERMINAL_ABAS_TAG = "terminal-abas"
const val TERMINAL_NOVA_ABA_TAG = "terminal-aba-nova"
fun abaTag(id: String) = "terminal-aba-$id"
fun abaFecharTag(id: String) = "terminal-aba-fechar-$id"
fun abaHostTag(hostId: String) = "terminal-aba-host-$hostId"

/** O rótulo da aba: `Terminal N` (do usuário) ou `Agente N ●` (do agente), ou o título que o servidor pediu (já sanitizado). */
fun rotuloDaAba(indice: Int, resumo: SessaoResumo, titulo: String?): String {
    val base = titulo ?: if (resumo.dono == DonoDaSessao.AGENTE) tr("Agente %d", indice) else tr("Terminal %d", indice)
    return if (resumo.dono == DonoDaSessao.AGENTE) "$base ●" else base
}

/**
 * `[Terminal 1] [Agente 2 ●] [+]` (CMP-24). Cada aba tem alvo de pelo menos 48 dp para tocar e outro para fechar; fechar a aba **fecha a sessão**.
 * O `+` abre a lista de servidores; sem servidor cadastrado, diz o que fazer.
 */
@Composable
fun AbasDeSessao(
    sessoes: List<SessaoResumo>,
    ativaId: String?,
    hosts: List<Host>,
    titulos: Map<String, String?>,
    onSelecionar: (String) -> Unit,
    onFechar: (String) -> Unit,
    onNova: (Host) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().background(palette.panel).testTag(TERMINAL_ABAS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            sessoes.forEachIndexed { i, s ->
                val ativa = s.id == ativaId
                val rotulo = rotuloDaAba(i + 1, s, titulos[s.id])
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (ativa) palette.card else palette.panel)
                        .border(1.dp, if (ativa) palette.teal else palette.line, RoundedCornerShape(8.dp))
                        .testTag(abaTag(s.id))
                        .semantics { role = Role.Tab; selected = ativa; contentDescription = rotulo },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        rotulo, color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.sp, maxLines = 1,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 160.dp).clickable { onSelecionar(s.id) }.padding(start = 12.dp, end = 4.dp).wrapCenter(),
                    )
                    Box(
                        Modifier.size(48.dp).clickable { onFechar(s.id) }.testTag(abaFecharTag(s.id)).semantics { contentDescription = tr("Fechar a sessão %s", rotulo) },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = palette.muted, fontSize = 14.sp) }
                }
            }
        }
        Box {
            Box(
                Modifier.size(48.dp).clickable { menu = true }.testTag(TERMINAL_NOVA_ABA_TAG).semantics { contentDescription = tr("Nova sessão") },
                contentAlignment = Alignment.Center,
            ) { Text("+", color = palette.fg, fontSize = 22.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (hosts.isEmpty()) {
                    DropdownMenuItem(text = { Text(tr("Cadastre um servidor na aba Computador.")) }, onClick = { menu = false })
                }
                hosts.forEach { h ->
                    DropdownMenuItem(
                        text = { Text("${h.nome} · ${h.usuario}@${h.endereco}") },
                        onClick = { menu = false; onNova(h) },
                        modifier = Modifier.testTag(abaHostTag(h.id)).heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

private fun Modifier.wrapCenter(): Modifier = this.then(Modifier.padding(vertical = 14.dp))
