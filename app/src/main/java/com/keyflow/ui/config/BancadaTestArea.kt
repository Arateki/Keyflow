package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keyflow.bancada.adaptadores.BancadaServicos
import com.keyflow.bancada.executor.ModeloEscolhido
import com.keyflow.reasoning.ReasoningEngine
import com.keyflow.reasoning.ReasoningResolution

/** Os serviços da bancada que a interface usa. Nulo = os do processo ([BancadaServicos.of]); os testes de interface injetam os seus, com motor falso. */
val LocalBancadaServicos = compositionLocalOf<BancadaServicos?> { null }

/**
 * A área do Testar da aba Raciocínio com a faixa de sub-abas (BNC-01): **Rápido** é o [ReasoningTestArea] de hoje, com as mesmas tags e os mesmos
 * tempos, e cada teste do corpus ganha a sua sub-aba — para o modelo escolhido na origem ativa, local ou de qualquer provedor online. Sem modelo ou sem
 * credencial, as sub-abas ficam desabilitadas com o mesmo motivo escrito de hoje (BNC-05).
 *
 * Os serviços do processo (histórico em arquivos, executor) só são criados quando uma sub-aba de teste é aberta: a faixa e o Rápido não tocam em disco.
 */
@Composable
fun BancadaTestArea(
    resolution: ReasoningResolution,
    modelo: ModeloEscolhido?,
    modifier: Modifier = Modifier,
    onManageUsage: (() -> Unit)? = null,
    initialState: TestState = TestState(),
) {
    val context = LocalContext.current
    val injetados = LocalBancadaServicos.current
    val corpus = remember(injetados) { injetados?.corpus ?: BancadaServicos.carregarCorpus(context.applicationContext) }
    val abas = bancadaSubAbas(corpus, resolution, modelo)
    var selecionada by rememberSaveable { mutableStateOf(BANCADA_SUBABA_RAPIDO) }
    val atual = abas.firstOrNull { it.id == selecionada } ?: abas.first()

    Column(modifier.fillMaxWidth()) {
        BancadaSubAbas(abas, atual.id, onSelect = { selecionada = it })
        Column(Modifier.padding(top = 14.dp)) {
            if (atual.id == BANCADA_SUBABA_RAPIDO || corpus == null) {
                ReasoningTestArea(resolution, onManageUsage = onManageUsage, initialState = initialState)
            } else {
                val servicos = remember(injetados) { injetados ?: BancadaServicos.of(context.applicationContext) }
                val engine: ReasoningEngine? = (resolution as? ReasoningResolution.Ready)?.engine
                BancadaTesteConteudo(
                    def = corpus.testes.getValue(atual.id),
                    abas = abas,
                    motivo = atual.motivo,
                    engine = engine,
                    modelo = modelo,
                    servicos = servicos,
                )
            }
        }
    }
}
