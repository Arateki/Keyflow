package com.keyflow.ui.config

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.fluxos.AbaDaEtapa
import com.keyflow.fluxos.Efetivo
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.ModeloRef
import com.keyflow.fluxos.OpcaoDeModelo
import com.keyflow.fluxos.SeloDeModelo
import com.keyflow.fluxos.app.nomeExibido
import com.keyflow.fluxos.nomeDaOrigem
import com.keyflow.fluxos.nomeDaOrigemNaTela
import com.keyflow.i18n.tr
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

/** O id do modelo local de OCR (PaddleOCR) da Visão: ele lê o texto e ignora a instrução da etapa. */
internal const val OCR_PADDLE_ID = "ocr-paddle-latin"

fun abaDe(aba: AbaDaEtapa): ConfigTab = when (aba) {
    AbaDaEtapa.TRANSCRICAO -> ConfigTab.VOZ
    AbaDaEtapa.VISAO -> ConfigTab.VISAO
    AbaDaEtapa.RACIOCINIO -> ConfigTab.RACIOCINIO
    AbaDaEtapa.VOZ -> ConfigTab.FALA
}

internal fun tagOpcaoDeModelo(opcao: OpcaoDeModelo): String = when (opcao) {
    is OpcaoDeModelo.DaAba -> "fluxos-modelo-da-aba"
    is OpcaoDeModelo.Escolha -> "fluxos-modelo-${opcao.ref.origem.lowercase()}-${opcao.ref.modelo ?: "padrao"}"
    is OpcaoDeModelo.Conectar -> "fluxos-modelo-conectar-${opcao.aba.name.lowercase()}-${opcao.origemNome.lowercase()}"
    is OpcaoDeModelo.Indisponivel -> "fluxos-modelo-indisponivel"
}

/** Como o card da etapa resume o modelo: "Da aba · Gemma", "OpenAI · gpt-4o" ou "Indisponível". */
internal fun resumoDoModelo(opcoes: List<OpcaoDeModelo>): String =
    when (val escolhida = opcoes.firstOrNull { it.selecionada }) {
        is OpcaoDeModelo.DaAba -> tr("Da aba") + efetivoNome(escolhida.efetivo)?.let { " · $it" }.orEmpty()
        is OpcaoDeModelo.Escolha -> nomeDaOrigemNaTela(escolhida.origemNome) + escolhida.modelo?.let { " · ${it.nome}" }.orEmpty()
        is OpcaoDeModelo.Indisponivel -> tr("Indisponível")
        else -> tr("Da aba")
    }

/** O modelo da aba para a tela; sem submodelo (voz do Sistema, Custom sem nome) vale o nome da origem. */
private fun efetivoNome(efetivo: Efetivo): String? =
    efetivo.modelo?.takeIf { it.isNotBlank() } ?: efetivo.origem?.let { nomeDaOrigemNaTela(nomeDaOrigem(it)) }

/** Conteúdo do seletor: sem efeito colateral, quem chama grava o [ModeloRef] (nulo = "Da aba") e abre a aba pedida. */
@Composable
fun FluxosSeletorModeloConteudo(
    etapa: Etapa,
    opcoes: List<OpcaoDeModelo>,
    notaDoAgente: Boolean,
    onEscolher: (ModeloRef?) -> Unit,
    onConectar: (AbaDaEtapa) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    Column(modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (etapa is Etapa.Agente && notaDoAgente) SettingSub(tr("O Agente só usa ferramentas com chave de API."))
        val paddle = etapa is Etapa.Visao && opcoes.firstOrNull { it.selecionada }.let {
            it is OpcaoDeModelo.Escolha && it.ref.modelo == OCR_PADDLE_ID || it is OpcaoDeModelo.DaAba && it.efetivo.modelo == OCR_PADDLE_ID
        }
        if (paddle) InfoNote(tr("O PaddleOCR só lê o texto da imagem: a instrução desta etapa é ignorada."), "fluxos-modelo-nota-paddle")
        opcoes.forEach { opcao ->
            val forma = RoundedCornerShape(14.dp)
            val conectar = opcao as? OpcaoDeModelo.Conectar
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(forma)
                    .border(1.dp, if (opcao.selecionada) palette.teal else palette.line2, forma)
                    .then(
                        when (opcao) {
                            is OpcaoDeModelo.DaAba -> Modifier.selectable(opcao.selecionada, role = Role.RadioButton) { onEscolher(null) }
                            is OpcaoDeModelo.Escolha -> Modifier.selectable(opcao.selecionada, role = Role.RadioButton) { onEscolher(opcao.ref) }
                            is OpcaoDeModelo.Conectar -> Modifier.clickable(role = Role.Button) { onConectar(opcao.aba) }
                            is OpcaoDeModelo.Indisponivel -> Modifier.semantics(mergeDescendants = true) { selected = true }
                        },
                    )
                    .testTag(tagOpcaoDeModelo(opcao)).padding(horizontal = 12.dp, vertical = 8.dp)
                    .alpha(if (opcao is OpcaoDeModelo.Indisponivel) 0.75f else 1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (opcao) {
                    is OpcaoDeModelo.DaAba, is OpcaoDeModelo.Escolha -> KfRadio(selected = opcao.selecionada)
                    is OpcaoDeModelo.Indisponivel -> Text("⚠", color = palette.terracotta, fontSize = 16.sp)
                    is OpcaoDeModelo.Conectar -> Unit
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rotulo(opcao), color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    val sub = sub(opcao)
                    if (sub != null) Text(sub, color = if (opcao is OpcaoDeModelo.Indisponivel) palette.terracotta else palette.muted,
                        fontFamily = SpaceMono, fontSize = 10.sp)
                }
                if (conectar != null) Text("→", color = palette.fg2, fontFamily = SpaceGrotesk, fontSize = 16.sp)
            }
        }
    }
}

private fun rotulo(opcao: OpcaoDeModelo): String = when (opcao) {
    is OpcaoDeModelo.DaAba -> tr("Da aba") + (efetivoNome(opcao.efetivo)?.let { "  ·  $it" } ?: "")
    is OpcaoDeModelo.Escolha -> nomeDaOrigemNaTela(opcao.origemNome) + (opcao.modelo?.let { " · ${it.nome}" } ?: "")
    is OpcaoDeModelo.Conectar -> nomeDaOrigemNaTela(opcao.origemNome)
    is OpcaoDeModelo.Indisponivel -> tr("Indisponível: %s", listOfNotNull(nomeDaOrigemNaTela(nomeDaOrigem(opcao.ref.origem)), opcao.ref.modelo).joinToString(" · "))
}

private fun sub(opcao: OpcaoDeModelo): String? = when (opcao) {
    is OpcaoDeModelo.DaAba -> seloTexto(opcao.efetivo)
    is OpcaoDeModelo.Escolha -> if (opcao.selo == SeloDeModelo.NO_APARELHO) tr("No aparelho") else tr("Nuvem · %s", opcao.origemNome)
    is OpcaoDeModelo.Conectar -> tr("Conecte na aba %s", abaDe(opcao.aba).label)
    is OpcaoDeModelo.Indisponivel -> tr("Modelo ausente: escolha outro ou instale de novo")
}

private fun seloTexto(efetivo: Efetivo): String = when (efetivo.onde) {
    com.keyflow.fluxos.Onde.NoAparelho -> tr("No aparelho")
    is com.keyflow.fluxos.Onde.Nuvem -> tr("Nuvem · %s", efetivo.onde.provedor.nome)
    com.keyflow.fluxos.Onde.Indisponivel -> tr("Indisponível")
}
