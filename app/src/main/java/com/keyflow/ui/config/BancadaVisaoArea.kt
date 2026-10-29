package com.keyflow.ui.config

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.keyflow.auth.AuthMethod
import com.keyflow.auth.ConnectionState
import com.keyflow.bancada.adaptadores.BancadaServicos
import com.keyflow.bancada.executor.ModeloEscolhido
import com.keyflow.bancada.executor.MotorDeCapacidade
import com.keyflow.core.VisionSource
import com.keyflow.data.Settings
import com.keyflow.data.models.FileRole
import com.keyflow.data.models.ModelCatalog
import com.keyflow.i18n.tr
import com.keyflow.vision.VisionModelCatalog
import com.keyflow.vision.VisionProvider

/** Modelo da Visão registrado na mesma forma usada pelo histórico da bancada. */
internal fun modeloDeVisao(settings: Settings, models: LocalModelsUi, connection: ConnectionState): ModeloEscolhido? {
    if (settings.visionSource == VisionSource.LOCAL) {
        val item = models.items.firstOrNull { it.spec.id == settings.visionLocalModelId && it.status == LocalModelStatus.INSTALLED } ?: return null
        return ModeloEscolhido("local", item.spec.id, item.spec.modelName.ifBlank { item.spec.id },
            sha256 = item.spec.file(FileRole.MODEL)?.sha256, backend = item.backend.name, contextTokens = item.spec.contextTokens)
    }
    val provider = when (settings.visionSource) {
        VisionSource.OPENAI -> VisionProvider.OPENAI
        VisionSource.ANTHROPIC -> VisionProvider.ANTHROPIC
        VisionSource.GOOGLE -> VisionProvider.GOOGLE
        else -> null
    }
    val nome = when (settings.visionSource) {
        VisionSource.OPENAI -> VisionModelCatalog.resolve(VisionProvider.OPENAI, settings.visionOpenAiModel)
        VisionSource.ANTHROPIC -> VisionModelCatalog.resolve(VisionProvider.ANTHROPIC, settings.visionAnthropicModel)
        VisionSource.GOOGLE -> VisionModelCatalog.resolve(VisionProvider.GOOGLE, settings.visionGoogleModel)
        VisionSource.CUSTOM -> settings.visionCustomModel.trim()
        else -> return null
    }
    if (nome.isBlank()) return null
    val origem = provider?.name?.lowercase() ?: "custom"
    return ModeloEscolhido(origem, nome, nome, backend = ModeloEscolhido.BACKEND_NUVEM,
        porAssinatura = (connection as? ConnectionState.Connected)?.method == AuthMethod.OAUTH)
}

/** Faixa da Visão: o teste rápido existente e as duas etapas de imagem da bancada. */
@Composable
internal fun BancadaVisaoArea(
    settings: Settings,
    models: LocalModelsUi,
    availability: VisionTestAvailability,
    connection: ConnectionState,
    motor: MotorDeCapacidade?,
    preparar: () -> Pair<MotorDeCapacidade, ModeloEscolhido>?,
    rapido: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val injetados = LocalBancadaServicos.current
    val corpus = remember(injetados) { injetados?.corpus ?: BancadaServicos.carregarCorpus(context.applicationContext) }
    val modelo = modeloDeVisao(settings, models, connection)
    val indisponivel = (availability as? VisionTestAvailability.Unavailable)?.let { VisionTestMessages.unavailable(it.reason) }
    val soOcr = settings.visionSource == VisionSource.LOCAL && settings.visionLocalModelId == ModelCatalog.OCR_PADDLE_LATIN.id
    val abas = buildList {
        add(BancadaSubAba(BANCADA_SUBABA_RAPIDO, tr("Rápido"), null))
        if (corpus != null) for (id in listOf("ocr", "objetos")) if (id in corpus.testes) {
            val motivo = indisponivel ?: when {
                id == "objetos" && soOcr -> tr("O modelo escolhido não tem a capacidade que este teste pede.")
                motor == null || modelo == null -> tr("Baixe e selecione um modelo para testar.")
                else -> null
            }
            add(BancadaSubAba(id, if (id == "ocr") "OCR" else tr("Objetos"), motivo))
        }
    }
    var selecionada by rememberSaveable { mutableStateOf(BANCADA_SUBABA_RAPIDO) }
    val atual = abas.firstOrNull { it.id == selecionada } ?: abas.first()
    Column {
        BancadaSubAbas(abas, atual.id, onSelect = { selecionada = it })
        Column(Modifier.padding(top = 14.dp)) {
            if (atual.id == BANCADA_SUBABA_RAPIDO || corpus == null) rapido()
            else {
                val servicos = remember(injetados) { injetados ?: BancadaServicos.of(context.applicationContext) }
                if (modelo?.online == true) {
                    SettingSub(tr("As imagens de teste serão enviadas ao provedor."), Modifier.padding(bottom = 6.dp))
                    SettingSub(tr("A estimativa não inclui os tokens das imagens."), Modifier.padding(bottom = 10.dp))
                }
                BancadaTesteConteudo(corpus.testes.getValue(atual.id), abas, atual.motivo, motor, modelo, servicos, preparar = preparar)
            }
        }
    }
}
