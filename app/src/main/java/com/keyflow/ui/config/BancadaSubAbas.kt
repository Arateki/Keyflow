package com.keyflow.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.keyflow.bancada.corpus.Capacidade
import com.keyflow.bancada.corpus.Corpus
import com.keyflow.bancada.executor.ModeloEscolhido
import com.keyflow.bancada.pontuacao.Pontuadores
import com.keyflow.i18n.tr
import com.keyflow.reasoning.ReasoningResolution
import com.keyflow.ui.theme.LocalKeyFlowPalette
import com.keyflow.ui.theme.SpaceGrotesk

const val BANCADA_SUBABAS_TAG = "bancada-subabas"
const val BANCADA_SUBABA_RAPIDO = "rapido"

fun bancadaSubAbaTag(id: String) = "bancada-subaba-$id"

/**
 * Uma sub-aba da faixa do Testar (BNC-01). [motivo] não nulo = o modelo escolhido não pode rodar este teste: a sub-aba aparece cinza e o motivo é
 * escrito ao abri-la (BNC-03/05). "Não se aplica" nunca vira nota 0.
 */
data class BancadaSubAba(val id: String, val rotulo: String, val motivo: String?) {
    val habilitada: Boolean get() = motivo == null
}

/** O nome de cada teste na faixa; nomes próprios e técnicos (`JSON`) ficam iguais em todos os idiomas. */
internal fun rotuloDoTeste(id: String): String = when (id) {
    "pontuacao" -> tr("Pontuação")
    "reescrita" -> tr("Reescrita")
    "json" -> "JSON"
    "acoes" -> tr("Ações")
    "contexto" -> tr("Contexto")
    else -> id
}

/**
 * As sub-abas do Testar para o modelo escolhido: **Rápido** (o Testar de hoje, sempre presente) e um teste do corpus por aba. Sem motor pronto, todas as
 * outras trazem o mesmo motivo escrito de hoje (RTS-02, BNC-05); com motor, só o que o modelo não suporta — capacidade que ele não tem, ou uma janela de
 * contexto menor que todos os casos do teste (BNC-03) — fica desabilitado. Sem corpus (não deveria acontecer: ele vem dentro do APK), só o Rápido.
 */
internal fun bancadaSubAbas(corpus: Corpus?, resolution: ReasoningResolution, modelo: ModeloEscolhido?): List<BancadaSubAba> {
    val abas = ArrayList<BancadaSubAba>()
    abas += BancadaSubAba(BANCADA_SUBABA_RAPIDO, tr("Rápido"), null)
    if (corpus == null) return abas
    val semMotor = (resolution as? ReasoningResolution.Unavailable)?.let { ReasoningTestMessages.unavailable(it.reason) }
    for (id in Pontuadores.testes.filter { it in corpus.testes }) {
        val teste = corpus.testes.getValue(id)
        val janela = modelo?.contextTokens
        val motivo = when {
            semMotor != null -> semMotor
            teste.capacidade != Capacidade.TEXTO -> tr("O modelo escolhido não tem a capacidade que este teste pede.")
            janela != null && teste.casos.all { it.tokensEntrada + it.maxTokensSaida > janela } ->
                tr("A janela deste modelo (%d tokens) é menor que a deste teste.", janela)
            else -> null
        }
        abas += BancadaSubAba(id, rotuloDoTeste(id), motivo)
    }
    return abas
}

/** A faixa rolável de sub-abas, no estilo do [Segmented]: trilho `panel`, aba ativa preenchida, desabilitada em cinza. */
@Composable
internal fun BancadaSubAbas(abas: List<BancadaSubAba>, selecionada: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalKeyFlowPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .testTag(BANCADA_SUBABAS_TAG)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.panel)
            .horizontalScroll(rememberScrollState())
            .padding(3.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        abas.forEach { aba ->
            val ativa = aba.id == selecionada
            Text(
                aba.rotulo,
                color = when {
                    ativa -> palette.onFill
                    !aba.habilitada -> palette.faint
                    else -> palette.fg2
                },
                fontFamily = SpaceGrotesk,
                fontWeight = if (ativa) FontWeight.Medium else FontWeight.Normal,
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (ativa) palette.fg else Color.Transparent)
                    // A aba desabilitada ainda abre: é onde o motivo está escrito.
                    .clickable { onSelect(aba.id) }
                    .semantics {
                        role = Role.Tab
                        selected = ativa
                        if (!aba.habilitada) disabled()
                    }
                    .testTag(bancadaSubAbaTag(aba.id))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
