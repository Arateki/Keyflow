package com.keyflow.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.keyflow.computador.ConfigHosts
import com.keyflow.computador.chaves.ChaveSsh
import com.keyflow.computador.sessao.SessaoResumo
import com.keyflow.i18n.tr
import com.keyflow.i18n.trKey
import com.keyflow.ui.common.verticalScrollbar
import com.keyflow.ui.theme.LocalKeyFlowPalette

const val COMPUTADOR_PANEL_TAG = "computador-panel"
const val COMPUTADOR_SECAO_HOSTS_TAG = "computador-secao-hosts"
const val COMPUTADOR_SECAO_CHAVES_TAG = "computador-secao-chaves"
const val COMPUTADOR_SECAO_SESSOES_TAG = "computador-secao-sessoes"
const val COMPUTADOR_EM_BREVE_TAG = "computador-secao-em-breve"

/** As três seções da aba (CMP-59). */
enum class SecaoComputador(private val rotuloPt: String) {
    HOSTS(trKey("Hosts")),
    CHAVES(trKey("Chaves")),
    SESSOES(trKey("Sessões")),
    ;
    val rotulo: String get() = tr(rotuloPt)
}

/**
 * Aba Computador: **Hosts · Chaves · Sessões** (CMP-59), sem banner. Os dados e as ações são portas explícitas;
 * as sessões ganham tela na CMP-T17. Sem `delay` simulado: o "Testar conexão" é o real ([AcoesDeHost.testar]).
 */
@Composable
fun ComputadorPanel(
    hosts: ConfigHosts,
    chaves: List<ChaveSsh>,
    sessoes: List<SessaoResumo>,
    acoes: AcoesDeHost,
    modifier: Modifier = Modifier,
    secaoInicial: SecaoComputador = SecaoComputador.HOSTS,
    acoesChaves: AcoesDeChaves? = null,
) {
    val palette = LocalKeyFlowPalette.current
    val scroll = rememberScrollState()
    var secao by remember { mutableStateOf(secaoInicial) }
    Column(
        modifier
            .fillMaxWidth()
            .testTag(COMPUTADOR_PANEL_TAG)
            .verticalScrollbar(scroll, palette.muted.copy(alpha = 0.35f), rightBleed = 9.dp)
            .verticalScroll(scroll)
            .padding(top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        Segmented(
            options = SecaoComputador.entries,
            selected = secao,
            label = { it.rotulo },
            tag = { when (it) { SecaoComputador.HOSTS -> COMPUTADOR_SECAO_HOSTS_TAG; SecaoComputador.CHAVES -> COMPUTADOR_SECAO_CHAVES_TAG; SecaoComputador.SESSOES -> COMPUTADOR_SECAO_SESSOES_TAG } },
            onSelect = { secao = it },
        )
        when (secao) {
            SecaoComputador.HOSTS -> ComputadorHosts(hosts.hosts, chaves, sessoes, acoes)
            SecaoComputador.CHAVES -> {
                val contexto = androidx.compose.ui.platform.LocalContext.current
                val app = if (acoesChaves == null) remember(contexto) { com.keyflow.computador.app.ComputadorApp.de(contexto) } else null
                if (app != null) AtividadeDasChaves(app)
                ComputadorChaves(chaves, hosts.hosts, acoesChaves ?: app!!.acoesDeChaves)
            }
            SecaoComputador.SESSOES -> InfoNote(tr("A lista de sessões chega na próxima etapa."), COMPUTADOR_EM_BREVE_TAG)
        }
    }
}

/** Liga o prompt à activity visível; sair da seção também solta a referência. */
@Composable
private fun AtividadeDasChaves(app: com.keyflow.computador.app.ComputadorApp) {
    var contexto: android.content.Context = androidx.compose.ui.platform.LocalContext.current
    while (contexto is android.content.ContextWrapper && contexto !is androidx.fragment.app.FragmentActivity) contexto = contexto.baseContext
    val atividade = contexto as? androidx.fragment.app.FragmentActivity ?: return
    val dono = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(app, atividade, dono) {
        fun atualizar() {
            if (dono.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) app.atividadeVisivel(atividade)
            else app.atividadeOculta(atividade)
        }
        val observador = androidx.lifecycle.LifecycleEventObserver { _, _ -> atualizar() }
        dono.lifecycle.addObserver(observador)
        atualizar()
        onDispose { dono.lifecycle.removeObserver(observador); app.atividadeOculta(atividade) }
    }
}
