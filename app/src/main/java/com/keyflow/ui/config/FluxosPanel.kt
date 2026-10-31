package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.core.KeyFlowController
import com.keyflow.data.Settings
import com.keyflow.data.SettingsRepository
import com.keyflow.fluxos.ConfigFluxos
import com.keyflow.fluxos.Fluxo
import com.keyflow.fluxos.LIMITES
import com.keyflow.fluxos.ModeloPorEtapa
import com.keyflow.fluxos.PadroesFluxo
import com.keyflow.fluxos.RestauradorFluxos
import com.keyflow.fluxos.Superficie
import com.keyflow.fluxos.ValidadorFluxo
import com.keyflow.fluxos.app.nomeExibido
import com.keyflow.fluxos.execucao.EstadoExecucao
import com.keyflow.fluxos.novoFluxo
import com.keyflow.i18n.tr
import com.keyflow.orbe.LugarDaOrbe
import com.keyflow.overlay.OverlayService
import com.keyflow.ui.common.collectAsStateOnMain
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk
import com.keyflow.ui.theme.SpaceMono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

const val FLUXOS_PANEL_TAG = "fluxos-panel"
const val FLUXOS_NEW_BUTTON_TAG = "fluxos-novo-fluxo"

/** Entrada ligada ao DataStore. Ler a lista não adota a semente nem abre o cofre; edições usam o valor da transação. */
@Composable
fun FluxosPanel(
    settings: Settings,
    repository: SettingsRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCancelar: () -> Unit = OverlayService::cancelarFluxo,
) {
    val configuracoes = remember(repository) { repository.configFluxosFlow() }
    val config by configuracoes.collectAsStateOnMain(initial = null)
    val estado by KeyFlowController.fluxo.collectAsStateOnMain()
    val scope = rememberCoroutineScope()
    var erro by remember { mutableStateOf(false) }
    val atual = config
    if (atual == null) { SettingSub(tr("Carregando fluxos…")); return }
    fun alterar(acao: suspend () -> Unit) {
        scope.launch {
            try { acao(); erro = false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { erro = true }
        }
    }
    Column(modifier) {
        if (erro) SettingSub(tr("Não foi possível salvar o fluxo."))
        FluxosLista(atual, settings, estado, onOpen, onNovo = {
            val nome = tr("Novo fluxo")
            alterar {
                var criado: Fluxo? = null
                repository.atualizarFluxos { c ->
                    criado = novoFluxo(c, nome)
                    criado?.let { c.copy(fluxos = c.fluxos + it) } ?: c
                }
                criado?.let { onOpen(it.id) }
            }
        }, onRestaurar = { alterar { repository.atualizarFluxos(RestauradorFluxos::restaurar) } }, onCancelar = onCancelar)
    }
}

/** Lista sem efeitos: o editor, os testes e as provas visuais usam os mesmos cards. */
@Composable
fun FluxosLista(
    config: ConfigFluxos,
    settings: Settings,
    estado: EstadoExecucao = EstadoExecucao.Ocioso,
    onOpen: (String) -> Unit,
    onNovo: () -> Unit,
    onRestaurar: () -> Unit,
    onCancelar: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** A galeria abre já com a confirmação de restaurar. */
    restaurarAberto: Boolean = false,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    var restaurar by remember { mutableStateOf(restaurarAberto) }
    Column(modifier.fillMaxWidth().testTag(FLUXOS_PANEL_TAG)
        .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
        .verticalScroll(scroll).padding(top = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AreaTitle(tr("Fluxos"))
        if (config.fluxos.isEmpty()) {
            InfoNote(tr("Você ainda não tem fluxos. Crie um com “+” ou restaure os padrão."), "fluxos-vazio")
        }
        config.fluxos.forEach { fluxo ->
            FluxoCard(fluxo, config, settings, estado, { onOpen(fluxo.id) }, onCancelar)
        }
        PrimaryButton(tr("Novo fluxo"), FLUXOS_NEW_BUTTON_TAG, onNovo,
            Modifier.heightIn(min = 48.dp), icon = KfIcons.Plus, enabled = config.fluxos.size < LIMITES.FLUXOS)
        if (config.fluxos.size >= LIMITES.FLUXOS) SettingSub(tr("Limite de 20 fluxos"))
        SecondaryButton(tr("Restaurar fluxos padrão"), "fluxos-restaurar", { restaurar = true }, Modifier.heightIn(min = 48.dp))
    }
    if (restaurar) {
        val previa = RestauradorFluxos.previa(config)
        ConfigModal(tr("Restaurar fluxos padrão"), { restaurar = false }, "fluxos-restaurar-fechar", accessibleHeader = true) {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (previa.vazia) SettingSub(tr("Os fluxos padrão já estão como no original"))
                if (previa.recriar.isNotEmpty()) SettingSub(tr("Será recriado: %s", previa.recriar.joinToString(", ") { nomePadrao(it) }))
                if (previa.reescrever.isNotEmpty()) SettingSub(tr("Será reescrito: %s", previa.reescrever.joinToString(", ") { nomePadrao(it) }))
                if (previa.semEspaco.isNotEmpty()) Text(tr("Sem espaço (limite de 20): %s", previa.semEspaco.joinToString(", ") { nomePadrao(it) }),
                    color = palette.terracotta, fontFamily = SpaceGrotesk, fontSize = 13.sp, modifier = Modifier.testTag("fluxos-restaurar-sem-espaco"))
                SettingSub(tr("Não muda: seus fluxos e todos os atalhos"))
                PrimaryButton(tr("Restaurar"), "fluxos-restaurar-confirmar", { onRestaurar(); restaurar = false },
                    Modifier.heightIn(min = 48.dp), enabled = !previa.nadaAGravar)
            }
        }
    }
}

private fun nomePadrao(nome: String): String = PadroesFluxo.CATALOGO.firstOrNull { it.nome == nome }?.let { tr(it.nome) } ?: nome

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FluxoCard(fluxo: Fluxo, config: ConfigFluxos, settings: Settings, estado: EstadoExecucao, abrir: () -> Unit, cancelar: () -> Unit) {
    val palette = LocalKeyFlowPalette.current
    val forma = RoundedCornerShape(18.dp)
    val valida = ValidadorFluxo.validar(fluxo)
    val rodando = (estado as? EstadoExecucao.Rodando)?.takeIf { it.fluxoId == fluxo.id }
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(forma).background(palette.card)
        .border(1.dp, if (rodando != null) palette.teal else palette.line2, forma)
        .clickable(role = Role.Button, onClick = abrir).testTag("fluxos-card-${fluxo.id}").padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(PadroesFluxo.nomeExibido(fluxo), color = palette.fg, fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        Text(fluxo.etapas.joinToString(" → ") { it.tipo.nomeExibido().lowercase() }.ifEmpty { tr("Adicione ao menos uma etapa") },
            color = palette.fg2, fontFamily = SpaceGrotesk, fontSize = 12.sp, lineHeight = 17.sp)
        if (!valida.valido) Text("⚠ " + tr("Incompleto"), color = palette.terracotta, fontFamily = SpaceMono,
            fontSize = 11.sp, modifier = Modifier.testTag("fluxos-incompleto-${fluxo.id}"))
        if (rodando != null) {
            Text(tr("Em execução · %s", rodando.etapa.nomeExibido()), color = palette.teal, fontFamily = SpaceMono, fontSize = 11.sp)
            SecondaryButton(tr("Cancelar"), "fluxos-cancelar-${fluxo.id}", cancelar, Modifier.heightIn(min = 48.dp))
        } else {
            Text(seloDoFluxo(settings, fluxo), color = palette.teal, fontFamily = SpaceMono, fontSize = 10.sp,
                modifier = Modifier.testTag("fluxos-selo-${fluxo.id}"))
        }
        val atalhos = config.gatilhos.filter { it.fluxoId == fluxo.id }.map { nomeDoAtalho(it.superficie) }
        if (atalhos.isEmpty()) Text(tr("Sem atalho"), color = palette.muted, fontFamily = SpaceMono, fontSize = 10.sp)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            atalhos.forEach { atalho -> Text(atalho, color = palette.fg2, fontFamily = SpaceMono, fontSize = 10.sp,
                modifier = Modifier.background(palette.panel, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) }
        }
    }
}

internal fun seloDoFluxo(settings: Settings, fluxo: Fluxo): String {
    val selo = ModeloPorEtapa.seloDoFluxo(settings, fluxo)
    return when {
        selo.indisponivel -> tr("Indisponível")
        selo.noAparelho -> tr("100% no aparelho")
        else -> tr("Nuvem · %s", selo.provedores.joinToString(", ") { it.nome })
    }
}

internal fun nomeDoAtalho(superficie: Superficie): String = when (superficie) {
    is Superficie.Barra -> tr("Botão do teclado")
    is Superficie.Orbe -> when (superficie.lugar) {
        LugarDaOrbe.FLUTUANTE -> tr("%d toques", superficie.toques)
        LugarDaOrbe.TECLADO -> tr("%d toques · teclado", superficie.toques)
        LugarDaOrbe.NOTIFICACAO -> tr("%d toques · notificação", superficie.toques)
    }
}
