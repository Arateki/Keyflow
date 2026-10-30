package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.fluxos.Etapa
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.LIMITES
import com.keyflow.fluxos.OpcaoDeModelo
import com.keyflow.fluxos.Problema
import com.keyflow.fluxos.Tipo
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.ValidadorFluxo
import com.keyflow.fluxos.GrupoDeEtapa
import com.keyflow.fluxos.app.nomeExibido
import com.keyflow.fluxos.comTexto
import com.keyflow.fluxos.textoProprio
import com.keyflow.i18n.tr
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

internal fun tipoNaTela(tipo: Tipo): String = when (tipo) {
    Tipo.NADA -> tr("nada")
    Tipo.TEXTO -> tr("texto")
    Tipo.IMAGEM -> tr("imagem")
}

/**
 * A lista de etapas do modal (FLX-12): alça `≡` (arrastar depois de segurar), ▲▼ e ✕ por etapa, campo próprio da Visão e do
 * Perguntar e o botão do modelo. O arrastar vive no contêiner (e não em cada linha) para seguir a etapa enquanto ela troca de
 * posição. Nenhum efeito: [onMover] e as demais mudanças sobem para quem grava.
 */
@Composable
internal fun EtapasLista(
    fluxo: Fluxo,
    problemas: List<Problema>,
    opcoesDe: (Etapa) -> List<OpcaoDeModelo>,
    onMover: (de: Int, para: Int) -> Unit,
    onRemover: (Int) -> Unit,
    onTexto: (Int, String) -> Unit,
    onModelo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topos = remember { mutableMapOf<Int, Pair<Float, Float>>() } // índice -> (topo, altura) no contêiner
    var arrastado by remember { mutableStateOf(-1) }
    val mover by rememberUpdatedState(onMover)
    val total by rememberUpdatedState(fluxo.etapas.size)
    val alcaPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    Column(
        modifier.fillMaxWidth().testTag("fluxos-etapas").pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { inicio: Offset ->
                    arrastado = if (inicio.x <= alcaPx) topos.entries.firstOrNull { (_, v) -> inicio.y >= v.first && inicio.y < v.first + v.second }?.key ?: -1 else -1
                },
                onDrag = { change, arraste ->
                    change.consume()
                    val atual = arrastado
                    if (atual < 0) return@detectDragGesturesAfterLongPress
                    val y = change.position.y
                    val alvo = topos.entries.firstOrNull { (_, v) -> y >= v.first && y < v.first + v.second }?.key
                    if (alvo != null && alvo != atual && alvo in 0 until total) { mover(atual, alvo); arrastado = alvo }
                },
                onDragEnd = { arrastado = -1 },
                onDragCancel = { arrastado = -1 },
            )
        },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        fluxo.etapas.forEachIndexed { i, etapa ->
            EtapaCard(
                i, etapa, fluxo.etapas.size, problemas.firstOrNull { it.indice == i },
                resumoDoModelo(opcoesDe(etapa)), arrastado == i,
                onMover = onMover, onRemover = { onRemover(i) }, onTexto = { onTexto(i, it) }, onModelo = { onModelo(i) },
                Modifier.onGloballyPositioned { topos[i] = it.positionInParent().y to it.size.height.toFloat() },
            )
        }
    }
}

@Composable
private fun EtapaCard(
    i: Int,
    etapa: Etapa,
    total: Int,
    problema: Problema?,
    modelo: String,
    arrastando: Boolean,
    onMover: (Int, Int) -> Unit,
    onRemover: () -> Unit,
    onTexto: (String) -> Unit,
    onModelo: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    val forma = RoundedCornerShape(16.dp)
    val borda = when { problema != null -> palette.terracotta; arrastando -> palette.teal; else -> palette.line2 }
    Column(
        modifier.fillMaxWidth().clip(forma).background(palette.card).border(if (problema != null) 2.dp else 1.dp, borda, forma)
            .testTag("fluxos-etapa-$i").padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).testTag("fluxos-etapa-$i-alca").semantics { contentDescription = tr("Segure e arraste para reordenar") },
                contentAlignment = Alignment.Center) {
                Text("≡", color = palette.muted, fontFamily = SpaceGrotesk, fontSize = 20.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("${i + 1}  " + etapa.tipo.nomeExibido(), color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(tipoNaTela(etapa.entrada) + " → " + tipoNaTela(etapa.saida), color = palette.muted, fontFamily = SpaceMono, fontSize = 10.sp)
            }
            BotaoIcone(KfIcons.ChevronUp, tr("Mover para cima"), "fluxos-etapa-$i-subir", i > 0) { onMover(i, i - 1) }
            BotaoIcone(KfIcons.ChevronDown, tr("Mover para baixo"), "fluxos-etapa-$i-descer", i < total - 1) { onMover(i, i + 1) }
            BotaoIcone(KfIcons.Close, tr("Remover etapa"), "fluxos-etapa-$i-remover", true, onRemover)
        }
        if (etapa.usaModelo) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, palette.line, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onModelo).testTag("fluxos-etapa-$i-modelo").padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Modelo"), color = palette.muted, fontFamily = SpaceMono, fontSize = 10.sp, modifier = Modifier.padding(end = 10.dp))
                Text(modelo, color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.sp, modifier = Modifier.weight(1f))
                KfIcon(KfIcons.ChevronDown, palette.fg2, Modifier.size(16.dp))
            }
        }
        val texto = etapa.textoProprio
        if (texto != null) {
            CampoDeTexto(
                valor = texto,
                rotulo = if (etapa is Etapa.Visao) tr("Instrução (vazio: ler o texto)") else tr("Instrução do sistema (opcional)"),
                tag = "fluxos-etapa-$i-texto",
                limite = LIMITES.TEXTO_DA_ETAPA,
                onCommit = onTexto,
            )
        }
        if (problema != null) {
            Text("⚠ " + problema.motivo.texto, color = palette.terracotta, fontFamily = SpaceGrotesk, fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp).testTag("fluxos-etapa-$i-problema"))
        }
    }
}

@Composable
private fun BotaoIcone(icone: com.keyflow.ui.icons.KfIconSpec, descricao: String, tag: String, habilitado: Boolean, onClick: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Box(
        Modifier.size(48.dp).clickable(enabled = habilitado, role = Role.Button, onClick = onClick).testTag(tag)
            .semantics { contentDescription = descricao }.alpha(if (habilitado) 1f else 0.3f),
        contentAlignment = Alignment.Center,
    ) { KfIcon(icone, palette.fg, Modifier.size(18.dp)) }
}

/** Campo de texto com confirmação ao perder o foco ou sair de cena; guarda no máximo [limite] caracteres. */
@Composable
internal fun CampoDeTexto(
    valor: String,
    rotulo: String,
    tag: String,
    limite: Int,
    onCommit: (String) -> Unit,
    vazioVolta: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKeyFlowPalette.current
    var texto by remember(valor) { mutableStateOf(valor) }
    var comFoco by remember { mutableStateOf(false) }
    val atual by rememberUpdatedState(texto)
    val original by rememberUpdatedState(valor)
    val gravar by rememberUpdatedState(onCommit)
    fun confirmar() {
        val t = atual.trim()
        if (vazioVolta && t.isEmpty()) { texto = original; return }
        if (t != original) gravar(t)
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { confirmar() } }
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(rotulo, color = palette.muted, fontFamily = SpaceMono, fontSize = 10.sp)
        BasicTextField(
            value = texto,
            onValueChange = { texto = it.take(limite) },
            textStyle = TextStyle(color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.sp),
            cursorBrush = SolidColor(palette.teal),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, palette.line, RoundedCornerShape(12.dp))
                .padding(12.dp).testTag(tag)
                .onFocusChanged { novo -> if (comFoco && !novo.isFocused) confirmar(); comFoco = novo.isFocused },
        )
    }
}

private fun GrupoDeEtapa.titulo(): String = when (this) {
    GrupoDeEtapa.ENTRADA -> tr("Entrada")
    GrupoDeEtapa.PROCESSAR -> tr("Processar")
    GrupoDeEtapa.SAIDA -> tr("Saída")
}

private fun TipoEtapa.descricao(): String = when (this) {
    TipoEtapa.DITAR -> tr("fala → texto")
    TipoEtapa.CAPTURAR -> tr("escolher uma área da tela")
    TipoEtapa.VISAO -> tr("lê o texto ou descreve a imagem")
    TipoEtapa.PERGUNTAR -> tr("texto → resposta")
    TipoEtapa.AGENTE -> tr("pede e usa ferramentas (com confirmação)")
    TipoEtapa.FALAR -> tr("lê o texto em voz alta")
    TipoEtapa.INSERIR -> tr("cola no campo")
    TipoEtapa.DESCONHECIDA -> ""
}

/** O seletor "+ Etapa": três grupos, itens que não cabem esmaecidos com o motivo (FLX-11). */
@Composable
internal fun SeletorDeEtapa(fluxo: Fluxo, onEscolher: (TipoEtapa) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    val itens = ValidadorFluxo.compativeis(fluxo.etapas.lastOrNull()?.saida)
    Column(modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GrupoDeEtapa.entries.forEach { grupo ->
            Eyebrow(grupo.titulo())
            itens.filter { it.grupo == grupo }.forEach { item ->
                val forma = RoundedCornerShape(14.dp)
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(forma).border(1.dp, palette.line2, forma)
                        .clickable(enabled = item.habilitada, role = Role.Button) { onEscolher(item.tipo) }
                        .testTag("fluxos-nova-etapa-${item.tipo.chave}").padding(horizontal = 14.dp, vertical = 10.dp)
                        .alpha(if (item.habilitada) 1f else 0.45f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(item.tipo.nomeExibido(), color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(item.motivo?.texto ?: item.tipo.descricao(), color = palette.muted, fontFamily = SpaceMono, fontSize = 10.sp)
                }
            }
        }
    }
}
