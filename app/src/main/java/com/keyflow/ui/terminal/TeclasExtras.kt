package com.keyflow.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.computador.app.EntradaDoTerminal
import com.keyflow.computador.app.EstadoPegajoso
import com.keyflow.computador.app.Tecla
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceMono

const val TERMINAL_TECLAS_TAG = "terminal-teclas-extras"
fun teclaExtraTag(id: String) = "terminal-tecla-$id"

/** Uma tecla extra: o rótulo curto e a descrição para o leitor de tela. [id] estável (usado nos testes). */
class TeclaExtra(val id: String, val rotulo: String, val descricao: String, val acao: (EntradaDoTerminal) -> Unit, val pegajosa: Boolean = false)

/** A lista das teclas (CMP-26): primeira linha Esc, Tab, Ctrl, Alt, setas, `| ~ / -`; segunda linha F1–F12. */
fun teclasExtras(): List<TeclaExtra> = listOf(
    TeclaExtra("esc", "Esc", tr("Tecla Esc"), { it.teclaExtra(Tecla.Esc) }),
    TeclaExtra("tab", "Tab", tr("Tecla Tab"), { it.teclaExtra(Tecla.Tab) }),
    TeclaExtra("ctrl", "Ctrl", tr("Ctrl: um toque vale para a próxima tecla, dois travam"), { it.pegajosos.tocarCtrl() }, pegajosa = true),
    TeclaExtra("alt", "Alt", tr("Alt: um toque vale para a próxima tecla, dois travam"), { it.pegajosos.tocarAlt() }, pegajosa = true),
    TeclaExtra("esq", "←", tr("Seta para a esquerda"), { it.teclaExtra(Tecla.Esquerda) }),
    TeclaExtra("baixo", "↓", tr("Seta para baixo"), { it.teclaExtra(Tecla.Baixo) }),
    TeclaExtra("cima", "↑", tr("Seta para cima"), { it.teclaExtra(Tecla.Cima) }),
    TeclaExtra("dir", "→", tr("Seta para a direita"), { it.teclaExtra(Tecla.Direita) }),
    TeclaExtra("pipe", "|", tr("Barra vertical"), { it.caractereExtra('|') }),
    TeclaExtra("til", "~", tr("Til"), { it.caractereExtra('~') }),
    TeclaExtra("barra", "/", tr("Tecla barra"), { it.caractereExtra('/') }),
    TeclaExtra("hifen", "-", tr("Hífen"), { it.caractereExtra('-') }),
)

fun teclasDeFuncao(): List<TeclaExtra> = (1..12).map { n -> TeclaExtra("f$n", "F$n", tr("Tecla F%d", n), { it.teclaExtra(Tecla.F(n)) }) }

/** As teclas extras acima do teclado (CMP-26, 31): alvos de 48 dp, descrição em todas, Ctrl/Alt mostram o estado também por texto. */
@Composable
fun TeclasExtras(entrada: EntradaDoTerminal, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    var funcoes by remember { mutableStateOf(false) }
    var versao by remember { mutableIntStateOf(0) }
    val principal = remember { teclasExtras() }
    val fs = remember { teclasDeFuncao() }

    @Composable
    fun Linha(teclas: List<TeclaExtra>) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in teclas) {
                val estado = when (t.id) { "ctrl" -> entrada.pegajosos.estadoCtrl(); "alt" -> entrada.pegajosos.estadoAlt(); else -> EstadoPegajoso.DESLIGADO }
                val textoEstado = when (estado) { EstadoPegajoso.DESLIGADO -> ""; EstadoPegajoso.UMA_VEZ -> tr("ligada para a próxima tecla"); EstadoPegajoso.TRAVADO -> tr("travada") }
                Box(
                    Modifier
                        .heightIn(min = 48.dp).widthIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (estado != EstadoPegajoso.DESLIGADO) palette.tealLight else palette.kbKey)
                        .border(1.dp, if (estado == EstadoPegajoso.TRAVADO) palette.teal else palette.kbKeyBorder, RoundedCornerShape(8.dp))
                        .clickable { t.acao(entrada); versao++ }
                        .testTag(teclaExtraTag(t.id))
                        .semantics { role = Role.Button; contentDescription = t.descricao; if (textoEstado.isNotEmpty()) stateDescription = textoEstado },
                    contentAlignment = Alignment.Center,
                ) { Text(t.rotulo + if (estado == EstadoPegajoso.TRAVADO) "•" else "", color = palette.fg, fontFamily = SpaceMono, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
            }
            if (teclas === principal) {
                Box(
                    Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(palette.kbModifier).clickable { funcoes = !funcoes }
                        .testTag(teclaExtraTag("fn")).semantics { role = Role.Button; contentDescription = tr("Mostrar ou esconder as teclas F1 a F12") },
                    contentAlignment = Alignment.Center,
                ) { Text(if (funcoes) "F ▾" else "F ▸", color = palette.fg, fontFamily = SpaceMono, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
            }
        }
    }
    // `versao` força a releitura do estado pegajoso depois de cada toque.
    @Suppress("UNUSED_EXPRESSION") versao
    Column(modifier.fillMaxWidth().background(palette.kbTray).testTag(TERMINAL_TECLAS_TAG), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Linha(principal)
        if (funcoes) Linha(fs)
    }
}
