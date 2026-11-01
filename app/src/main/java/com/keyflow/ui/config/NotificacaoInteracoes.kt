package com.keyflow.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.keyflow.i18n.tr
import com.keyflow.notificacao.AcaoDaOrbe
import com.keyflow.notificacao.ConfigEfetiva
import com.keyflow.notificacao.destinoNaNotificacao
import com.keyflow.orbe.Interacao
import com.keyflow.ui.theme.LocalKeyFlowPalette

internal val CATALOGO_NOTIFICACAO = listOf(AcaoDaOrbe.Nenhuma, AcaoDaOrbe.GravarEnviar, AcaoDaOrbe.RetrairExpandirTeclado, AcaoDaOrbe.LerTela)
internal fun AcaoDaOrbe.rotulo(): String = when (this) {
    AcaoDaOrbe.Nenhuma -> tr("Nenhuma")
    AcaoDaOrbe.GravarEnviar -> tr("Gravar / enviar")
    AcaoDaOrbe.RetrairExpandirTeclado -> tr("Retrair / expandir teclado")
    AcaoDaOrbe.LerTela -> tr("Ler a tela")
    is AcaoDaOrbe.RodarFluxo, is AcaoDaOrbe.Desconhecida -> tr("Ação indisponível")
}
internal fun AcaoDaOrbe.idCatalogo() = when (this) {
    AcaoDaOrbe.Nenhuma -> "nenhuma"
    AcaoDaOrbe.GravarEnviar -> "gravar"
    AcaoDaOrbe.RetrairExpandirTeclado -> "teclado"
    AcaoDaOrbe.LerTela -> "ler-tela"
    else -> "indisponivel"
}
internal fun rotuloToques(n: Int) = if (n == 1) tr("1 toque") else tr("%d toques", n)
internal fun avisoLerTela() = tr("Ler a tela precisa da permissão de sobreposição e do aparelho desbloqueado.")

@Composable
internal fun NotificacaoInteracoes(efetiva: ConfigEfetiva, abrir: (Int) -> Unit) {
    val palette = LocalKeyFlowPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Interações"), color = palette.fg)
        (1..3).forEach { n ->
            val acao = efetiva.acaoParaToques(n)!!
            val descricao = tr("%s na notificação: %s", rotuloToques(n), acao.rotulo())
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = !efetiva.copiado, role = Role.Button) { abrir(n) }
                .semantics { contentDescription = descricao }.testTag("notificacao-toque-$n").padding(vertical = 8.dp)) {
                Text(descricao, color = palette.fg)
                if (efetiva.copiado) Text(tr("Copiado da orbe principal"), color = palette.muted)
                if (acao == AcaoDaOrbe.LerTela) Text(avisoLerTela(), color = palette.muted, modifier = Modifier.testTag("notificacao-ler-tela-$n"))
            }
        }
        if (!efetiva.iniciaGravacao) InfoNote(tr("Nenhum toque inicia a gravação."), "notificacao-sem-gravar")
        Text(tr("Botões automáticos: Pausar, Retomar, Descartar e Desfazer, conforme o estado."), color = palette.muted)
        Text(tr("Interações que não existem na notificação"), color = palette.fg)
        efetiva.naoTem.forEach { gesto ->
            val nome = when (gesto) {
                Interacao.SEGURAR -> tr("Segurar")
                Interacao.ARRASTAR_CIMA -> tr("Arrastar para cima")
                Interacao.ARRASTAR_LADO -> tr("Arrastar para o lado")
                Interacao.ARRASTAR_BAIXO -> tr("Arrastar para baixo")
                else -> ""
            }
            val destino = destinoNaNotificacao(gesto)?.let { tr(it) }.orEmpty()
            Text("$nome: $destino", color = palette.muted, modifier = Modifier.testTag("notificacao-ausente-${gesto.name}"))
        }
        Text(tr("Segurar não existe: uma notificação só recebe toques."), color = palette.muted)
    }
}
