package com.keyflow.ui.orb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.keyflow.fluxos.TipoEtapa
import com.keyflow.fluxos.app.nomeExibido
import com.keyflow.ui.icons.KfIcon
import com.keyflow.ui.icons.KfIcons
import com.keyflow.ui.theme.KeyFlowPalette

/** O Ditar mantém o centro de gravação; as demais etapas mostram o ícone da capacidade em processamento. */
@Composable
fun FluxoEtapaBadge(etapa: TipoEtapa?, palette: KeyFlowPalette) {
    if (etapa == null || etapa == TipoEtapa.DITAR) return
    val icone = when (etapa) {
        TipoEtapa.CAPTURAR, TipoEtapa.VISAO -> KfIcons.TabVisao
        TipoEtapa.PERGUNTAR -> KfIcons.TabRaciocinio
        TipoEtapa.FALAR -> KfIcons.TabBoca
        TipoEtapa.INSERIR -> KfIcons.Paste
        TipoEtapa.AGENTE -> KfIcons.TabKeyflow
        else -> KfIcons.TabFluxos
    }
    Box(Modifier.size(36.dp).background(palette.panel, CircleShape).border(2.dp, palette.teal, CircleShape)
        .semantics { contentDescription = etapa.nomeExibido() }, contentAlignment = Alignment.Center) {
        KfIcon(icone, palette.teal, Modifier.size(24.dp))
    }
}
