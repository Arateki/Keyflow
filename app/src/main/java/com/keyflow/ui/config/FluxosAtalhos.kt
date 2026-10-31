package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.data.Settings
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.PadroesFluxo
import com.keyflow.fluxos.PoliticaDoLugar
import com.keyflow.fluxos.PoliticaGatilhos
import com.keyflow.fluxos.RecusaDeLigar
import com.keyflow.fluxos.Superficie
import com.keyflow.i18n.tr
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono

/**
 * Os lugares da orbe que existem agora (design §5.3): a flutuante sempre. A orbe da notificação e a do teclado entram nas
 * tasks FLX-T28 e FLX-T29, quando as features delas ligarem os gatilhos; até lá a modal não mostra os grupos delas.
 */
fun lugaresDisponiveis(): Set<LugarDaOrbe> = setOf(LugarDaOrbe.FLUTUANTE)

/** A política da orbe flutuante com o rótulo da tela, para o aviso de substituição nomear a ação do gesto. */
fun politicaDaFlutuante(settings: Settings): PoliticaDoLugar = PoliticaDoLugar.daFlutuante(settings, ::shortcutActionLabel)

/** O que a seção Atalhos do modal faz; quem monta liga ao DataStore (o modal) ou a um estado de teste. */
class AtalhosDoModal(
    val config: ConfigFluxos,
    val onLigar: (Superficie) -> Unit,
    val onDesligar: (Superficie) -> Unit,
    val onAdicionarBotao: () -> Unit,
    val onRemoverBotao: (String) -> Unit,
    val lugares: Set<LugarDaOrbe> = lugaresDisponiveis(),
)

fun tagLigarAtalho(toques: Int) = "fluxos-atalho-ligar-$toques"
fun tagDesligarAtalho(toques: Int) = "fluxos-atalho-desligar-$toques"
const val FLUXOS_BOTAO_ADICIONAR_TAG = "fluxos-botao-adicionar"
fun tagRemoverBotao(botaoId: String) = "fluxos-botao-remover-$botaoId"

/**
 * Seção **Atalhos** do modal (design §11.4), agrupada por lugar. Ligar numa superfície que já faz algo pede confirmação
 * ([onConfirmar], que o modal mostra como tela própria); superfície livre liga direto. O fluxo Incompleto não liga (FLX-38).
 */
@Composable
internal fun FluxosAtalhosSecao(
    fluxo: Fluxo,
    atalhos: AtalhosDoModal,
    settings: Settings,
    onConfirmar: (Superficie.Orbe, String) -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val config = atalhos.config
    val politica = politicaDaFlutuante(settings)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(tr("Atalhos"))
        val incompleto = !com.keyflow.fluxos.ValidadorFluxo.validar(fluxo).valido
        if (incompleto) SettingSub(tr("Complete o fluxo para ligar atalhos"), Modifier.testTag("fluxos-atalhos-incompleto"))
        if (LugarDaOrbe.FLUTUANTE in atalhos.lugares) {
            Text(tr("Orbe flutuante"), color = palette.fg2, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
            Superficie.Orbe.TOQUES.forEach { n ->
                val superficie = Superficie.Orbe(LugarDaOrbe.FLUTUANTE, n)
                val ligado = config.gatilho(superficie)
                val daqui = ligado?.fluxoId == fluxo.id
                val outro = ligado?.takeIf { !daqui }?.let { config.fluxo(it.fluxoId) }
                val recusa = if (daqui) null else PoliticaGatilhos.recusaDe(config, fluxo.id, superficie)
                val hoje = when {
                    daqui -> tr("Ligado")
                    outro != null -> tr("Fluxo: %s", PadroesFluxo.nomeExibido(outro))
                    else -> tr("hoje: %s", politica.rotuloDaAcaoPropria(n) ?: tr("Nenhuma"))
                }
                LinhaDeAtalho(
                    rotulo = tr("%d toques", n), estado = hoje, tagEstado = "fluxos-atalho-estado-$n",
                    textoBotao = if (daqui) tr("Desligar") else tr("Ligar"),
                    tagBotao = if (daqui) tagDesligarAtalho(n) else tagLigarAtalho(n),
                    habilitado = daqui || recusa == null,
                ) {
                    if (daqui) atalhos.onDesligar(superficie)
                    else {
                        val aviso = PoliticaGatilhos.avisoDeSubstituicao(config, superficie, fluxo.id, politica)
                        if (aviso == null) atalhos.onLigar(superficie) else onConfirmar(superficie, aviso)
                    }
                }
                if (recusa == RecusaDeLigar.LIMITE_DE_GATILHOS) SettingSub(tr("Limite de atalhos ligados"), Modifier.testTag("fluxos-atalho-limite-$n"))
            }
        }
        Text(tr("Botão do teclado"), color = palette.fg2, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        config.gatilhosDe(fluxo.id).mapNotNull { (it.superficie as? Superficie.Barra)?.botaoId }.forEach { id ->
            LinhaDeAtalho(tr("Na barra"), "", "fluxos-botao-estado-$id", tr("Remover"), tagRemoverBotao(id), true) { atalhos.onRemoverBotao(id) }
        }
        val recusaBotao = PoliticaGatilhos.recusaDe(config, fluxo.id, Superficie.Barra("novo"))
        LinhaDeAtalho(tr("Barra"), "", "fluxos-botao-novo", tr("Adicionar à barra"), FLUXOS_BOTAO_ADICIONAR_TAG, recusaBotao == null,
            larguraDoBotao = 150) { atalhos.onAdicionarBotao() }
        if (recusaBotao == RecusaDeLigar.LIMITE_DE_GATILHOS) SettingSub(tr("Limite de atalhos ligados"), Modifier.testTag("fluxos-botao-limite"))
    }
}

@Composable
private fun LinhaDeAtalho(
    rotulo: String,
    estado: String,
    tagEstado: String,
    textoBotao: String,
    tagBotao: String,
    habilitado: Boolean,
    larguraDoBotao: Int = 96,
    onClick: () -> Unit,
) {
    val palette = LocalKeyFlowPalette.current
    val forma = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().clip(forma).background(palette.card).border(1.dp, palette.line2, forma).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(rotulo, color = palette.fg, fontFamily = SpaceGrotesk, fontSize = 13.sp)
            if (estado.isNotEmpty()) Text(estado, color = palette.muted, fontFamily = SpaceMono, fontSize = 10.sp, modifier = Modifier.testTag(tagEstado))
        }
        val bf = RoundedCornerShape(10.dp)
        Row(Modifier.widthIn(min = larguraDoBotao.dp).heightIn(min = 48.dp).alpha(if (habilitado) 1f else 0.4f).clip(bf)
            .border(1.dp, palette.line, bf).clickable(enabled = habilitado, role = Role.Button, onClick = onClick).testTag(tagBotao).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(textoBotao, color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        }
    }
}

/** A tela de confirmação de ligar um gesto que já faz algo, ou a de excluir o fluxo (design §11.4, §11.5). */
@Composable
internal fun ConfirmacaoDoModal(
    linhas: List<String>,
    textoConfirmar: String,
    tagConfirmar: String,
    onCancelar: () -> Unit,
    onConfirmar: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        linhas.forEachIndexed { i, l -> SettingSub(l, Modifier.testTag("fluxos-confirmacao-texto-$i")) }
        PrimaryButton(textoConfirmar, tagConfirmar, onConfirmar, Modifier.heightIn(min = 48.dp))
        SecondaryButton(tr("Cancelar"), "fluxos-confirmacao-cancelar", onCancelar, Modifier.heightIn(min = 48.dp))
    }
}
