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
 * Aba Computador: **Hosts · Chaves · Sessões** (CMP-59), sem banner. Os hosts vêm do [dados]; as chaves e as sessões ganham tela nas próximas
 * etapas (CMP-T16 e T17) e aqui só avisam. Sem `delay` simulado: o "Testar conexão" é o real ([AcoesDeHost.testar]).
 */
@Composable
fun ComputadorPanel(
    hosts: ConfigHosts,
    chaves: List<ChaveSsh>,
    sessoes: List<SessaoResumo>,
    acoes: AcoesDeHost,
    modifier: Modifier = Modifier,
    secaoInicial: SecaoComputador = SecaoComputador.HOSTS,
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
            SecaoComputador.CHAVES -> InfoNote(tr("As chaves SSH ganham a própria seção na próxima etapa."), COMPUTADOR_EM_BREVE_TAG)
            SecaoComputador.SESSOES -> InfoNote(tr("A lista de sessões chega na próxima etapa."), COMPUTADOR_EM_BREVE_TAG)
        }
    }
}
